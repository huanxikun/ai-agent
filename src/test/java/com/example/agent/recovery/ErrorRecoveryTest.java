package com.example.agent.recovery;

import com.example.agent.DeepSeekClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorRecoveryTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void retries429WithExponentialBackoffAndZeroJitter() throws Exception {
        List<Long> sleeps = new ArrayList<>();
        ErrorRecovery recovery = recovery("", sleeps);
        ErrorRecovery.RecoveryState state = recovery.newState();
        AtomicInteger calls = new AtomicInteger();
        List<ErrorRecovery.RecoveryEvent> events = new ArrayList<>();

        DeepSeekClient.ModelResponse response = recovery.withRetry(
                (maxTokens, model) -> {
                    if (calls.getAndIncrement() < 2) {
                        throw new DeepSeekClient.DeepSeekException(
                                429,
                                "rate_limit",
                                "rate limited"
                        );
                    }
                    return response("ok");
                },
                state,
                events::add
        );

        assertEquals("ok", response.text());
        assertEquals(3, calls.get());
        assertEquals(List.of(500L, 1_000L), sleeps);
        assertEquals(List.of("429", "429"), events.stream()
                .map(ErrorRecovery.RecoveryEvent::kind)
                .toList());
    }

    @Test
    void retryAfterHeaderTakesPriority() throws Exception {
        List<Long> sleeps = new ArrayList<>();
        ErrorRecovery recovery = recovery("", sleeps);
        AtomicInteger calls = new AtomicInteger();

        recovery.withRetry(
                (maxTokens, model) -> {
                    if (calls.getAndIncrement() == 0) {
                        throw new DeepSeekClient.DeepSeekException(
                                429,
                                "rate_limit",
                                "rate limited",
                                2_500L
                        );
                    }
                    return response("ok");
                },
                recovery.newState(),
                event -> {
                }
        );

        assertEquals(List.of(2_500L), sleeps);
    }

    @Test
    void switchesFallbackAfterThreeConsecutive529() throws Exception {
        List<Long> sleeps = new ArrayList<>();
        ErrorRecovery recovery = recovery("fallback-model", sleeps);
        ErrorRecovery.RecoveryState state = recovery.newState();
        List<String> models = new ArrayList<>();
        List<ErrorRecovery.RecoveryEvent> events = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();

        recovery.withRetry(
                (maxTokens, model) -> {
                    models.add(model);
                    if (calls.getAndIncrement() < 3) {
                        throw new DeepSeekClient.DeepSeekException(
                                529,
                                "overloaded",
                                "overloaded"
                        );
                    }
                    return response("fallback worked");
                },
                state,
                events::add
        );

        assertEquals(
                List.of(
                        "primary-model",
                        "primary-model",
                        "primary-model",
                        "fallback-model"
                ),
                models
        );
        assertEquals("fallback-model", state.currentModel());
        assertTrue(events.stream().anyMatch(
                event -> "fallback_model".equals(event.kind())
        ));
    }

    @Test
    void maxTokensEscalatesOnceThenAllowsThreeContinuations() {
        ErrorRecovery recovery = recovery("", new ArrayList<>());
        ErrorRecovery.RecoveryState state = recovery.newState();

        assertEquals(
                ErrorRecovery.MaxTokensAction.ESCALATE_AND_RETRY,
                recovery.handleMaxTokens(state)
        );
        assertEquals(64_000, state.maxTokens());
        for (int count = 1; count <= 3; count++) {
            assertEquals(
                    ErrorRecovery.MaxTokensAction.APPEND_AND_CONTINUE,
                    recovery.handleMaxTokens(state)
            );
            assertEquals(count, state.continuations());
        }
        assertEquals(
                ErrorRecovery.MaxTokensAction.RECOVERY_EXHAUSTED,
                recovery.handleMaxTokens(state)
        );
    }

    @Test
    void nonTransientErrorIsNotRetried() {
        ErrorRecovery recovery = recovery("", new ArrayList<>());
        AtomicInteger calls = new AtomicInteger();

        assertThrows(
                IllegalArgumentException.class,
                () -> recovery.withRetry(
                        (maxTokens, model) -> {
                            calls.incrementAndGet();
                            throw new IllegalArgumentException("bad request");
                        },
                        recovery.newState(),
                        event -> {
                        }
                )
        );
        assertEquals(1, calls.get());
    }

    private ErrorRecovery recovery(
            String fallback,
            List<Long> sleeps
    ) {
        return new ErrorRecovery(
                "primary-model",
                fallback,
                sleeps::add,
                upperExclusive -> 0
        );
    }

    private DeepSeekClient.ModelResponse response(String text) {
        ObjectNode message = JSON.createObjectNode();
        message.put("role", "assistant");
        message.put("content", text);
        return new DeepSeekClient.ModelResponse(
                text,
                List.of(),
                message,
                "end_turn"
        );
    }
}
