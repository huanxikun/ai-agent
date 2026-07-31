(function () {
  "use strict";

  var canvas = document.getElementById("board");
  var ctx = canvas.getContext("2d");
  var scoreEl = document.getElementById("score");
  var overlay = document.getElementById("overlay");
  var overlayTitle = document.getElementById("overlay-title");
  var overlayHint = document.getElementById("overlay-hint");
  var startBtn = document.getElementById("startBtn");

  var GRID = 20;          // 20x20 格子
  var CELL = canvas.width / GRID;

  var snake, dir, nextDir, food, score, running, paused, timer, speed;

  function randEmptyCell() {
    while (true) {
      var x = Math.floor(Math.random() * GRID);
      var y = Math.floor(Math.random() * GRID);
      var occupied = snake.some(function (s) { return s.x === x && s.y === y; });
      if (!occupied) return { x: x, y: y };
    }
  }

  function reset() {
    snake = [{ x: 10, y: 10 }, { x: 9, y: 10 }, { x: 8, y: 10 }];
    dir = { x: 1, y: 0 };
    nextDir = { x: 1, y: 0 };
    score = 0;
    speed = 150;
    food = randEmptyCell();
    scoreEl.textContent = "0";
    running = false;
    paused = false;
  }

  // 初始静止画面
  reset();
  draw();

  function drawBoard() {
    ctx.fillStyle = "#171919";
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    ctx.strokeStyle = "#202322";
    ctx.lineWidth = 0.5;
    for (var i = 1; i < GRID; i++) {
      ctx.beginPath();
      ctx.moveTo(i * CELL, 0);
      ctx.lineTo(i * CELL, canvas.height);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(0, i * CELL);
      ctx.lineTo(canvas.width, i * CELL);
      ctx.stroke();
    }
  }

  function drawSnake() {
    snake.forEach(function (seg, idx) {
      ctx.fillStyle = idx === 0 ? "#d8ff65" : "#a9c94d";
      ctx.fillRect(seg.x * CELL + 1, seg.y * CELL + 1, CELL - 2, CELL - 2);
    });

    var head = snake[0];
    ctx.fillStyle = "#0c0d0d";
    var px = head.x * CELL + (dir.x > 0 ? 12 : dir.x < 0 ? 3 : 7);
    var py = head.y * CELL + (dir.y > 0 ? 12 : dir.y < 0 ? 3 : 7);
    ctx.beginPath();
    ctx.arc(px, py, 1.8, 0, Math.PI * 2);
    ctx.fill();
    ctx.beginPath();
    ctx.arc(px + dir.x * 3, py + dir.y * 3, 1.8, 0, Math.PI * 2);
    ctx.fill();
  }

  function drawFood() {
    ctx.fillStyle = "#ff8877";
    var cx = food.x * CELL + CELL / 2;
    var cy = food.y * CELL + CELL / 2;
    ctx.beginPath();
    ctx.arc(cx, cy, CELL / 3, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = "#ffb8ad";
    ctx.beginPath();
    ctx.arc(cx - 1, cy - 1, CELL / 6, 0, Math.PI * 2);
    ctx.fill();
  }

  function draw() {
    drawBoard();
    drawSnake();
    drawFood();
  }

  function step() {
    dir = nextDir;
    var head = { x: snake[0].x + dir.x, y: snake[0].y + dir.y };

    // 撞墙
    if (head.x < 0 || head.x >= GRID || head.y < 0 || head.y >= GRID) {
      return gameOver("撞墙了");
    }

    // 撞自己（尾部不在移动中移除时）
    var willRemove = snake[snake.length - 1];
    var willEat = head.x === food.x && head.y === food.y;
    if (!willEat &&
        snake.some(function (s) { return s.x === head.x && s.y === head.y; })) {
      return gameOver("撞到自己了");
    }

    snake.unshift(head);

    if (willEat) {
      score += 1;
      scoreEl.textContent = String(score);
      food = randEmptyCell();
      if (speed > 70) speed -= 4;
      clearInterval(timer);
      timer = setInterval(step, speed);
    } else {
      snake.pop();
    }

    draw();
  }

  function gameOver(reason) {
    running = false;
    clearInterval(timer);
    overlayTitle.textContent = "游戏结束";
    overlayHint.innerHTML = reason + "，得分 " + score + "<br />点击重新开始";
    overlay.classList.remove("hidden");
    startBtn.textContent = "再来一局";
  }

  function start() {
    reset();
    running = true;
    overlay.classList.add("hidden");
    startBtn.textContent = "开始游戏";
    draw();
    clearInterval(timer);
    timer = setInterval(step, speed);
  }

  function togglePause() {
    if (!running) return;
    paused = !paused;
    if (paused) {
      clearInterval(timer);
      overlayTitle.textContent = "已暂停";
      overlayHint.innerHTML = "按空格 或 点击继续";
      overlay.classList.remove("hidden");
      startBtn.textContent = "继续";
    } else {
      overlay.classList.add("hidden");
      timer = setInterval(step, speed);
    }
  }

  var KEYMAP = {
    37: { x: -1, y: 0 },
    65: { x: -1, y: 0 },
    38: { x: 0, y: -1 },
    87: { x: 0, y: -1 },
    39: { x: 1, y: 0 },
    68: { x: 1, y: 0 },
    40: { x: 0, y: 1 },
    83: { x: 0, y: 1 }
  };

  document.addEventListener("keydown", function (e) {
    if (e.code === "Space") {
      e.preventDefault();
      if (!running) start();
      else togglePause();
      return;
    }

    var nv = KEYMAP[e.keyCode];
    if (!nv) return;
    e.preventDefault();
    // 禁止 180° 掉头
    if (nv.x === -dir.x && nv.y === -dir.y) return;
    if (running && !paused) nextDir = nv;
  });

  startBtn.addEventListener("click", function () {
    if (running && paused) {
      togglePause();
    } else {
      start();
    }
  });
})();
