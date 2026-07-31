/* 贪吃蛇 · 游戏逻辑 */
(() => {
  "use strict";

  // ---- DOM 引用 ----
  const board = document.getElementById("board");
  const ctx = board.getContext("2d");
  const scoreEl = document.getElementById("score");
  const highScoreEl = document.getElementById("highScore");
  const lengthEl = document.getElementById("length");
  const overlay = document.getElementById("overlay");
  const overlayTitle = document.getElementById("overlayTitle");
  const overlayText = document.getElementById("overlayText");
  const startBtn = document.getElementById("startBtn");

  // ---- 游戏常量 ----
  const CELL = 20; // 每个格子边长（px），画布 400x400 → 20x20 格子
  const GRID = board.width / CELL; // 20
  const BASE_SPEED = 120; // 每步基础毫秒数
  const MIN_SPEED = 60; // 最快速度
  const SPEED_STEP = 3; // 每吃一个食物的提速

  const DIRS = {
    up: { x: 0, y: -1 },
    down: { x: 0, y: 1 },
    left: { x: -1, y: 0 },
    right: { x: 1, y: 0 }
  };
  const OPPOSITE = {
    up: "down",
    down: "up",
    left: "right",
    right: "left"
  };

  // ---- 状态 ----
  let snake = [];       // {x, y} 数组，头在前
  let food = null;      // {x, y}
  let dir = "right";    // 当前移动方向
  let pendingDir = "right";
  let score = 0;
  let timer = null;
  let running = false;  // true = 游戏进行中
  let gameOver = false; // 是否已结束（失败）

  const HIGH_SCORE_KEY = "snakeHighScore";

  // ---- 工具 ----
  function getHighScore() {
    try {
      return parseInt(localStorage.getItem(HIGH_SCORE_KEY), 10) || 0;
    } catch (e) {
      return 0;
    }
  }

  function setHighScore(value) {
    try {
      localStorage.setItem(HIGH_SCORE_KEY, String(value));
    } catch (e) {
      /* 忽略：localStorage 不可用时静默失败 */
    }
  }

  function saveScore() {
    if (score > getHighScore()) {
      setHighScore(score);
    }
  }

  // ---- 初始化 / 重置 ----
  function resetGame() {
    // 初始蛇：从中心偏左横向排开
    const cx = Math.floor(GRID / 2);
    const cy = Math.floor(GRID / 2);
    snake = [
      { x: cx, y: cy },
      { x: cx - 1, y: cy },
      { x: cx - 2, y: cy }
    ];
    dir = "right";
    pendingDir = "right";
    score = 0;
    gameOver = false;
    spawnFood();
    render();
  }

  // ---- 生成食物（不与蛇重叠）----
  function spawnFood() {
    let pos;
    let attempts = 0;
    do {
      pos = {
        x: Math.floor(Math.random() * GRID),
        y: Math.floor(Math.random() * GRID)
      };
      attempts++;
    } while (snake.some(s => s.x === pos.x && s.y === pos.y) && attempts < 500);
    food = pos;
  }

  // ---- 步进逻辑 ----
  function step() {
    if (!running || gameOver) return;

    dir = pendingDir;

    const head = snake[0];
    const next = {
      x: head.x + DIRS[dir].x,
      y: head.y + DIRS[dir].y
    };

    // 撞墙
    if (next.x < 0 || next.x >= GRID || next.y < 0 || next.y >= GRID) {
      endGame("撞墙了!");
      return;
    }
    // 撞自己（注意：尾巴即将移开的格子不算撞）
    const willMoveTail = !(food.x === next.x && food.y === next.y);
    const bodyToCheck = willMoveTail ? snake.slice(0, -1) : snake;
    if (bodyToCheck.some(s => s.x === next.x && s.y === next.y)) {
      endGame("咬到自己了!");
      return;
    }

    snake.unshift(next);

    // 吃到食物
    if (food.x === next.x && food.y === next.y) {
      score += 10;
      spawnFood();
    } else {
      snake.pop();
    }

    render();
  }

  // ---- 结束 ----
  function endGame(reason) {
    running = false;
    gameOver = true;
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
    saveScore();

    overlayTitle.textContent = "游戏结束";
    overlayText.textContent = reason + " 得分 " + score + " 分";
    startBtn.textContent = "再来一局";
    startBtn.dataset.over = "1";
    overlay.classList.remove("hidden");
    render();
  }

  // ---- 绘制 ----
  function render() {
    // 背景
    ctx.fillStyle = "#0c0c0a";
    ctx.fillRect(0, 0, board.width, board.height);

    // 网格线
    ctx.strokeStyle = "rgba(216, 255, 101, 0.06)";
    ctx.lineWidth = 1;
    for (let i = 1; i < GRID; i++) {
      ctx.beginPath();
      ctx.moveTo(i * CELL, 0);
      ctx.lineTo(i * CELL, board.height);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(0, i * CELL);
      ctx.lineTo(board.width, i * CELL);
      ctx.stroke();
    }

    // 食物（红色）
    if (food) {
      ctx.fillStyle = "#e53935";
      ctx.beginPath();
      ctx.arc(
        food.x * CELL + CELL / 2,
        food.y * CELL + CELL / 2,
        CELL / 2 - 3,
        0,
        Math.PI * 2
      );
      ctx.fill();
      // 高光
      ctx.fillStyle = "rgba(255,255,255,0.35)";
      ctx.beginPath();
      ctx.arc(
        food.x * CELL + CELL / 2 - 2,
        food.y * CELL + CELL / 2 - 2,
        2,
        0,
        Math.PI * 2
      );
      ctx.fill();
    }

    // 蛇身
    snake.forEach((seg, i) => {
      const pad = i === 0 ? 1 : 2;
      ctx.fillStyle = i === 0 ? "#d8ff65" : "#9fce3f";
      const x = seg.x * CELL + pad;
      const y = seg.y * CELL + pad;
      const size = CELL - pad * 2;
      ctx.fillRect(x, y, size, size);
      // 圆角感
      ctx.strokeStyle = "#1a1a18";
      ctx.lineWidth = 1;
      ctx.strokeRect(x + 0.5, y + 0.5, size - 1, size - 1);
    });

    // 眼睛（蛇头）
    const head = snake[0];
    if (head) {
      const ex = (head.x + DIRS[dir].x * 0.35) * CELL + CELL / 2;
      const ey = (head.y + DIRS[dir].y * 0.35) * CELL + CELL / 2;
      ctx.fillStyle = "#15150f";
      ctx.beginPath();
      ctx.arc(ex - 3, ey - 3, 2, 0, Math.PI * 2);
      ctx.arc(ex + 3, ey - 3, 2, 0, Math.PI * 2);
      ctx.fill();
    }

    // 更新统计
    scoreEl.textContent = String(score);
    highScoreEl.textContent = String(getHighScore());
    lengthEl.textContent = String(snake.length);
  }

  // ---- 开始 / 暂停 ----
  function startGame() {
    if (gameOver) {
      // 从失败状态重新开始
      resetGame();
    }
    running = true;
    pendingDir = dir;
    overlay.classList.add("hidden");
    if (timer) clearInterval(timer);
    let speed = Math.max(MIN_SPEED, BASE_SPEED - score * SPEED_STEP);
    timer = setInterval(step, speed);
    render();
  }

  function pauseGame() {
    running = false;
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
    overlayTitle.textContent = "已暂停";
    overlayText.textContent = "按 空格 继续";
    startBtn.textContent = "继续";
    startBtn.dataset.over = "";
    overlay.classList.remove("hidden");
  }

  // ---- 事件 ----
  startBtn.addEventListener("click", () => {
    if (running) {
      pauseGame();
    } else {
      startGame();
    }
  });

  document.addEventListener("keydown", (e) => {
    const key = e.key.toLowerCase();
    // 方向键 / WASD
    let nextDir = null;
    if (e.key === "ArrowUp" || key === "w") nextDir = "up";
    else if (e.key === "ArrowDown" || key === "s") nextDir = "down";
    else if (e.key === "ArrowLeft" || key === "a") nextDir = "left";
    else if (e.key === "ArrowRight" || key === "d") nextDir = "right";
    else if (e.key === " " || key === "spacebar") {
      // 空格：开始 / 暂停
      e.preventDefault();
      if (running) pauseGame();
      else startGame();
      return;
    }

    if (nextDir && running && !gameOver) {
      e.preventDefault();
      if (nextDir !== OPPOSITE[dir]) {
        pendingDir = nextDir;
      }
    }
  });

  // ---- 启动 ----
  resetGame();
  overlayTitle.textContent = "准备好了吗？";
  overlayText.textContent = "按 空格 开始游戏";
  startBtn.textContent = "开始游戏";
  startBtn.dataset.over = "";
  overlay.classList.remove("hidden");
  render();
})();
