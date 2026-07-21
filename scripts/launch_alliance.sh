#!/usr/bin/env bash
# 多院区联盟一键拉起 + 数据灌入 + 自检
# 流程：
#   1) docker compose up -d
#   2) 等待 backend 就绪
#   3) 跑原始自检（确保单院区基础能力 OK）
#   4) 跑多院区模拟器（3 院区各 200 例）
#   5) 跑联盟自检
#
# 用法：bash scripts/launch_alliance.sh
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$ROOT_DIR"

API="http://localhost:8080/api"

echo "================================================"
echo " 多院区联盟质控 - 一键拉起"
echo "================================================"

# 1) 启动容器
echo ""
echo "[1/5] docker compose up -d"
docker compose up -d

# 2) 等待 backend 健康
echo "[2/5] 等待 backend 就绪（最多 120s）"
for i in $(seq 1 40); do
  if curl -s -o /dev/null -w "%{http_code}" $API/beds 2>/dev/null | grep -q "200"; then
    echo "  backend ready after ${i}x3s"
    break
  fi
  sleep 3
done

# 3) 基础自检
echo "[3/5] 基础自检（单院区）"
bash scripts/self_check.sh || { echo "基础自检失败，请先排查"; exit 1; }

# 4) 多院区模拟
echo "[4/5] 灌入 3 院区数据（各 200 例）"
ALLI_ID=$(curl -s $API/alliance/list | python -c "import sys,json; d=json.load(sys.stdin); print(d[0]['id'] if d else 1)" 2>/dev/null || echo 1)
echo "  Alliance ID = $ALLI_ID"

for hosp in 1 2 3; do
  START_PID=$((10000 + (hosp - 1) * 1000))
  echo "  -- 院区 #$hosp (startPid=$START_PID) --"
  python3 simulator/multi_hospital_sim.py --alliance-id $ALLI_ID --hospital-id $hosp \
      --patients 200 --start-pid $START_PID
done

# 5) 联盟自检
echo "[5/5] 多院区联合质控自检"
bash scripts/self_check_alliance.sh

echo ""
echo "================================================"
echo " 一键拉起完成"
echo " - 后端 API:     http://localhost:8080"
echo " - 前端 UI:      http://localhost:5173"
echo " - 数据库:       localhost:5432 (icu_monitor / icu / icu_pwd_2026)"
echo "================================================"
