package com.example.agent.recovery;

import com.example.agent.DeepSeekClient;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * S11 recovery policy for output truncation and transient API failures.
 * prompt_too_long remains an Agent-loop transition because it mutates context.
 */
public final class ErrorRecovery {
    public static final int DEFAULT_MAX_TOKENS = 8_000;
    public static final int ESCALATED_MAX_TOKENS = 64_000;
    public static final int MAX_CONTINUATIONS = 3;
    public static final int MAX_TRANSIENT_RETRIES = 10;
    public static final String CONTINUATION_PROMPT = """
            Output token limit hit. Resume directly — no apology, no recap of
            what you were doing. Pick up mid-thought if that is where the cut
            happened. Break remaining work into smaller pieces.
            """.strip();

    private static final long BASE_DELAY_MS = 500;
    private static final long MAX_BASE_DELAY_MS = 32_000;
    private static final int FALLBACK_AFTER_529 = 3;

    private final String primaryModel;
    private final String fallbackModel;
    private final Sleeper sleeper;
    private final Jitter jitter;

    public ErrorRecovery(String primaryModel, String fallbackModel) {
        this(
                primaryModel,
                fallbackModel,
                Thread::sleep,
                upperExclusive -> ThreadLocalRandom.current()
                        .nextLong(upperExclusive)
        );
    }

    ErrorRecovery(
            String primaryModel,
            String fallbackModel,
            Sleeper sleeper,
            Jitter jitter
    ) {
        this.primaryModel = primaryModel;
        this.fallbackModel = fallbackModel == null
                ? ""
                : fallbackModel.trim();
        this.sleeper = sleeper;
        this.jitter = jitter;
    }

    public RecoveryState newState() {
        return new RecoveryState(primaryModel);
    }

    public DeepSeekClient.ModelResponse withRetry(
            AttemptCall call,
            RecoveryState state,
            Consumer<RecoveryEvent> listener
    ) throws Exception {
        for (int attempt = 0; attempt <= MAX_TRANSIENT_RETRIES; attempt++) {
            try {
                DeepSeekClient.ModelResponse response = call.call(
                        state.maxTokens(),
                        state.currentModel()
                );
                state.resetConsecutive529();
                return response;
            } catch (Exception exception) {
                TransientKind kind = transientKind(exception);
                if (kind == null || attempt == MAX_TRANSIENT_RETRIES) {
                    throw exception;
                }
                if (kind == TransientKind.OVERLOADED_529) {
                    state.incrementConsecutive529();
                    if (state.consecutive529() >= FALLBACK_AFTER_529
                            && !fallbackModel.isBlank()
                            && !fallbackModel.equals(state.currentModel())) {
                        state.switchModel(fallbackModel);
                        state.resetConsecutive529();
                        listener.accept(new RecoveryEvent(
                                "fallback_model",
                                attempt + 1,
                                0,
                                "连续 3 次 529，切换到 " + fallbackModel
                        ));
                    }
                } else {
                    state.resetConsecutive529();
                }

                long delay = retryDelayMillis(
                        attempt,
                        retryAfterMillis(exception)
                );
                listener.accept(new RecoveryEvent(
                        kind == TransientKind.RATE_LIMIT_429
                                ? "429"
                                : "529",
                        attempt + 1,
                        delay,
                        "瞬态错误，指数退避后重试"
                ));
                sleeper.sleep(delay);
            }
        }
        throw new IllegalStateException("不可达的重试状态");
    }

    public MaxTokensAction handleMaxTokens(RecoveryState state) {
        if (!state.hasEscalated()) {
            state.escalate();
            return MaxTokensAction.ESCALATE_AND_RETRY;
        }
        if (state.continuations() < MAX_CONTINUATIONS) {
            state.incrementContinuation();
            return MaxTokensAction.APPEND_AND_CONTINUE;
        }
        return MaxTokensAction.RECOVERY_EXHAUSTED;
    }

    public long retryDelayMillis(int attempt, Long retryAfterMs) {
        if (retryAfterMs != null) return Math.max(0, retryAfterMs);
        long multiplier = 1L << Math.min(attempt, 16);
        long base = Math.min(
                BASE_DELAY_MS * multiplier,
                MAX_BASE_DELAY_MS
        );
        long jitterBound = Math.max(1, base / 4 + 1);
        return base + jitter.nextLong(jitterBound);
    }

    private TransientKind transientKind(Throwable error) {
        for (Throwable current = error;
             current != null;
             current = current.getCause()) {
            if (current instanceof DeepSeekClient.DeepSeekException api) {
                if (api.statusCode() == 429) {
                    return TransientKind.RATE_LIMIT_429;
                }
                if (api.statusCode() == 529) {
                    return TransientKind.OVERLOADED_529;
                }
            }
            String message = current.getMessage();
            if (message == null) continue;
            String normalized = message.toLowerCase();
            if (normalized.contains("429")
                    || normalized.contains("rate limit")) {
                return TransientKind.RATE_LIMIT_429;
            }
            if (normalized.contains("529")
                    || normalized.contains("overloaded")) {
                return TransientKind.OVERLOADED_529;
            }
        }
        return null;
    }

    private Long retryAfterMillis(Throwable error) {
        for (Throwable current = error;
             current != null;
             current = current.getCause()) {
            if (current instanceof DeepSeekClient.DeepSeekException api
                    && api.retryAfterMs() != null) {
                return api.retryAfterMs();
            }
        }
        return null;
    }

    @FunctionalInterface
    public interface AttemptCall {
        DeepSeekClient.ModelResponse call(
                int maxTokens,
                String model
        ) throws Exception;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long milliseconds) throws InterruptedException;
    }

    @FunctionalInterface
    interface Jitter {
        long nextLong(long upperExclusive);
    }

    public enum MaxTokensAction {
        ESCALATE_AND_RETRY,
        APPEND_AND_CONTINUE,
        RECOVERY_EXHAUSTED
    }

    private enum TransientKind {
        RATE_LIMIT_429,
        OVERLOADED_529
    }

    public record RecoveryEvent(
            String kind,
            int attempt,
            long delayMs,
            String detail
    ) {
    }

    public static final class RecoveryState {
        private int maxTokens = DEFAULT_MAX_TOKENS;
        private boolean escalated;
        private int continuations;
        private int consecutive529;
        private boolean reactiveCompactAttempted;
        private String currentModel;

        private RecoveryState(String primaryModel) {
            this.currentModel = primaryModel;
        }

        public int maxTokens() {
            return maxTokens;
        }

        public boolean hasEscalated() {
            return escalated;
        }

        public int continuations() {
            return continuations;
        }

        public int consecutive529() {
            return consecutive529;
        }

        public boolean reactiveCompactAttempted() {
            return reactiveCompactAttempted;
        }

        public String currentModel() {
            return currentModel;
        }

        public void markReactiveCompactAttempted() {
            reactiveCompactAttempted = true;
        }

        private void escalate() {
            maxTokens = ESCALATED_MAX_TOKENS;
            escalated = true;
        }

        private void incrementContinuation() {
            continuations++;
        }

        private void incrementConsecutive529() {
            consecutive529++;
        }

        private void resetConsecutive529() {
            consecutive529 = 0;
        }

        private void switchModel(String model) {
            currentModel = model;
        }
    }
}
