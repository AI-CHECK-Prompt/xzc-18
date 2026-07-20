#!/usr/bin/env bash
# 回归测试：1 分钟均值漂移问题
# 场景：连续 6 个分钟向 5 床注入"正常"心率（70-85 区间随机），再分别回查每分钟的 avg_value。
#       修复前：因为 sum/count 持续累计，每分钟的 avg 都是"启动至今"的累积平均，
#              会从首个样本附近的均值缓慢漂向全样本均值（例如 80 → 78 → 76 → 74 → 72 → 70）。
#       修复后：每分钟的 avg 仅由该分钟内的样本决定，应当稳定在 70-85 区间内，
#              不应出现单调下降趋势。
#
# 用法：bash scripts/regress_agg_drift.sh
# 依赖：API 已在 http://localhost:8080 启动；simulator 或 selfcheck/inject 任一可用。

set -e
API="${API:-http://localhost:8080/api}"
PY=$(command -v python3 || command -v python)
[ -z "$PY" ] && { echo "python3 not found"; exit 2; }

BED_ID="${BED_ID:-5}"
CH="${CH:-HR}"
MINUTES="${MINUTES:-6}"
INTERVAL="${INTERVAL:-65}"   # 略大于 60s，确保能完整跨过 1 分钟边界

echo "=== Regress: 1min mean drift fix (bed=$BED_ID ch=$CH minutes=$MINUTES) ==="

inject() {
  # 70-85 区间随机心率，模拟正常波形
  local v=$(( (RANDOM % 16) + 70 ))
  curl -s -X POST "$API/selfcheck/inject" \
    -d "protocol=HL7_V2&sn=REGRESS-$BED_ID&channel=$CH&value=$v" > /dev/null
  echo "  injected HR=$v at $(date '+%H:%M:%S')"
}

echo "[1] inject samples spread across $MINUTES minutes"
for i in $(seq 1 $MINUTES); do
  inject
  if [ "$i" -lt "$MINUTES" ]; then sleep "$INTERVAL"; fi
done

# 等待 flushAgg 把最后一分钟也刷出
echo "[2] wait 35s for last bucket flush"
sleep 35

echo "[3] query sample_metric for last $((MINUTES+1)) minutes"
ROWS=$(curl -s "$API/metric?bedId=$BED_ID&minutes=$((MINUTES+1))")
echo "$ROWS" | $PY -m json.tool

echo "[4] assertions"
echo "$ROWS" | $PY - <<'PY' || exit 1
import sys, json
data = json.load(sys.stdin)
if not data:
    print("  FAIL: empty metric rows")
    sys.exit(1)
# /api/metric 返回该床所有通道的行；为简单起见只看 sample_count > 0 且 avg_value 非空的行
rows = [r for r in data if r.get("avg_value") is not None and (r.get("sample_count") or 0) > 0]
if len(rows) < 3:
    print(f"  FAIL: too few rows ({len(rows)})")
    sys.exit(1)
rows.sort(key=lambda r: r["time"])
avgs = [r["avg_value"] for r in rows]
print(f"  observed avg sequence: {avgs}")
diffs = [round(avgs[i+1] - avgs[i], 3) for i in range(len(avgs)-1)]
monotonic_down = all(d < -0.5 for d in diffs)
if monotonic_down:
    print(f"  FAIL: avg monotonically decreasing (diffs={diffs}), bug not fixed")
    sys.exit(1)
in_range = all(60 <= a <= 95 for a in avgs)
if not in_range:
    print(f"  FAIL: avg out of expected range: {avgs}")
    sys.exit(1)
print(f"  PASS: avg no longer drifts; {len(rows)} minute buckets all in [60,95]; diffs={diffs}")
PY

echo "=== Regress PASS ==="
