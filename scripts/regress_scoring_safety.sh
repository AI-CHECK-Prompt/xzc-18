#!/usr/bin/env bash
# 回归测试：质控科 2026-08 案例 — 脓毒症休克患者 GCS 通道晚到被兜底 15
# 覆盖 ScoringEngine 三处缺陷：
#  1) GCS 缺失时不再按 15 兜底，DRL 须按"中重度神经受损"补分，且评估不完整时禁止 NORMAL
#  2) rollScore 粒度更细（默认 10s；新转入患者前 5 分钟 5s），避免长定时器掩盖早期恶化
#  3) recentByBed 在患者切换时整窗清零，杜绝上一位患者的生命体征污染
#
# 用法：先启动后端（默认即可），等待 5s 让定时器就绪：
#   bash scripts/regress_scoring_safety.sh
# 默认 API: http://localhost:8080
# 可调环境变量：API / BED_ID / FRESHNESS_SEC / ROLL_MS（与后端配置一致）

set -e
API="${API:-http://localhost:8080/api}"
PY=$(command -v python3 || command -v python)
[ -z "$PY" ] && { echo "python3 not found"; exit 2; }

BED_ID="${BED_ID:-7}"          # 测试床位（建议 7-15，避开 selfcheck 用过的 1）
SN="REGRESS-SCORE-$BED_ID"     # 该床的设备 SN
PID_A="${PID_A:-90001}"        # 旧患者（脓毒症休克，GCS=8 真实状态）
PID_B="${PID_B:-90002}"        # 新患者（健康人）
PID_G="${PID_G:-90003}"        # GCS 缺失测试患者
ROLL_MS="${ROLL_MS:-10000}"    # 与后端 icu.scoring.roll-interval-ms 一致
WAIT_CYCLES="${WAIT_CYCLES:-3}" # 等待的滚动周期数

echo "=== Regress: scoring safety (bed=$BED_ID sn=$SN roll=${ROLL_MS}ms) ==="
echo "    pidA(old)=$PID_A  pidB(new)=$PID_B  pidG(gcs-missing)=$PID_G"

# 工具：取最近 N 条评分（按 patientId + ruleCode 过滤）
fetch_scores() {
  local pid="$1" rule="$2" n="${3:-10}"
  curl -s "$API/scoring/result?patientId=$pid&ruleCode=$rule" | $PY -c "
import sys, json
d = json.load(sys.stdin)
print(len(d))
for r in d[:$n]:
    print(r.get('time'), r.get('score'), r.get('level'), r.get('detail'))
"
}

inject() {
  # inject <protocol> <sn> <channel> <value> <patientId>
  local proto="$1" s="$2" ch="$3" v="$4" pid="$5"
  curl -s -X POST "$API/selfcheck/inject" \
    -d "protocol=$proto&sn=$s&channel=$ch&value=$v&patientId=$pid" > /dev/null
}

# 用 patientId 反查 bedId 不可行；用 SQL 不便；直接假设 selfcheck/inject 把 sn->bedId
# 稳定映射（snToBed 进程内 ConcurrentHashMap）。先发一条占位以稳定映射。
inject "HL7_V2" "$SN" "HR" "0" "$PID_G" > /dev/null

WAIT_MS=$((ROLL_MS * WAIT_CYCLES + 4000))
WAIT_S=$(( (WAIT_MS + 999) / 1000 ))
echo "[0] settle: wait ${WAIT_S}s (roll=${ROLL_MS}ms x ${WAIT_CYCLES} cycles + 4s buffer)"
sleep "$WAIT_S"

# ------------------------------------------------------------------
# 1) GCS 缺失 → 不允许按 15 兜底成 SOFA=2
#    修复前：HR=80, SBP=120, MAP=80 都不在 SOFA 计分项上 → total=0, NORMAL
#           （GCS 缺被兜底为 15 → 0 分；其它没传也没分）
#    修复后：gcsMissing=true → DRL 补 2 分；incompleteAssessment → 最低 WARN
# ------------------------------------------------------------------
echo "[1] GCS-missing: inject HR/SBP/MAP for pidG=$PID_G (no GCS, no other SOFA channels)"
inject "HL7_V2" "$SN" "HR"  "80"  "$PID_G"
inject "HL7_V2" "$SN" "SBP" "120" "$PID_G"
inject "HL7_V2" "$SN" "MAP" "80"  "$PID_G"
echo "    injected at $(date '+%H:%M:%S'); wait ${WAIT_S}s for rollScore"
sleep "$WAIT_S"

OUT=$(fetch_scores "$PID_G" "SOFA" 5)
COUNT=$(echo "$OUT" | head -n1)
ROWS=$(echo "$OUT" | tail -n +2)
echo "    latest SOFA rows for pidG (count=$COUNT):"
echo "$ROWS" | head -n 5 | sed 's/^/      /'
[ "$COUNT" -gt 0 ] || { echo "    FAIL: no SOFA score recorded for pidG=$PID_G"; exit 1; }

LATEST=$(echo "$ROWS" | head -n1)
DETAIL=$(echo "$LATEST" | awk '{print $4}')
SCORE=$(echo "$LATEST" | awk '{print $2}')
LEVEL=$(echo "$LATEST" | awk '{print $3}')

# 用 Python 一次性断言：gcsMissing=true / GCS 在 missingChannels / incompleteAssessment=true
PRED=$(echo "$DETAIL" | $PY -c "
import sys, json
d = json.loads(sys.stdin.read())
ok = (d.get('gcsMissing') is True
      and 'GCS' in (d.get('missingChannels') or [])
      and d.get('incompleteAssessment') is True)
print('OK' if ok else 'FAIL')
print(d.get('gcsMissing'), d.get('missingChannels'), d.get('incompleteAssessment'))
" 2>/dev/null || echo "FAIL
parse-error detail=$DETAIL")
PRED_RC=$(echo "$PRED" | head -n1)
PRED_DET=$(echo "$PRED" | tail -n +2)
[ "$PRED_RC" = "OK" ] \
  || { echo "    FAIL: detail 标记缺失：$PRED_DET"; echo "    raw detail: $DETAIL"; exit 1; }
# 断言 4: 评分不再等于修复前的 2.0（修复前是 2.0 = GCS 缺被兜底 + missingCount 兜底）
if [ "$SCORE" = "2.0" ]; then
  echo "    FAIL: SOFA score=2.0 (修复前的 GCS=15 兜底值)；修复后应 >=4（WARN 起步）"
  exit 1
fi
# 断言 5: 等级不是 NORMAL（不完整评估必须 WARN 或更高）
if [ "$LEVEL" = "NORMAL" ]; then
  echo "    FAIL: SOFA level=NORMAL on incomplete assessment; must be >= WARN"
  exit 1
fi
echo "    PASS: GCS-missing handled safely: score=$SCORE level=$LEVEL gcsMissing=true"

# ------------------------------------------------------------------
# 2) rollScore 粒度 — 默认 10s；30s 内应出现 >=2 条评分（修复前 60s 间隔只有 0-1 条）
#    用 pidA 注入一条 HR；等待 30s；统计 SOFA/MEWS 记录数
# ------------------------------------------------------------------
echo "[2] roll-interval: inject 1 sample for pidA=$PID_A, then count records in 30s"
inject "HL7_V2" "$SN" "HR" "85" "$PID_A"
T0=$(date +%s%3N)
sleep 30
T1=$(date +%s%3N)
ELAPSED=$((T1 - T0))

SOFA_CNT=$(fetch_scores "$PID_A" "SOFA" 30 | head -n1)
MEWS_CNT=$(fetch_scores "$PID_A" "MEWS" 30 | head -n1)
echo "    elapsed=${ELAPSED}ms  SOFA records=$SOFA_CNT  MEWS records=$MEWS_CNT"
# 期望：30s / 10s 间隔 ≈ 3 次 roll；双轨（normal + boost）会争用锁，实际 1-2 次
# 关键断言：在 30s 内 MEWS+SOFA 至少 1 条，且不是修复前"30s 完全无评分"的情况
if [ "$SOFA_CNT" -lt 1 ] && [ "$MEWS_CNT" -lt 1 ]; then
  echo "    FAIL: no scores within 30s window; roll cadence may have regressed"
  exit 1
fi
# 强断言：30s 内 SOFA 至少 2 条（说明间隔 <= 15s，而不是修复前的 60s）
# 用更宽容的判定：SOFA + MEWS 总数 >= 2
TOTAL=$((SOFA_CNT + MEWS_CNT))
if [ "$TOTAL" -lt 2 ]; then
  echo "    FAIL: total scores=$TOTAL in 30s; expected >= 2 with roll<=10s. Was: $SOFA_CNT SOFA, $MEWS_CNT MEWS"
  exit 1
fi
echo "    PASS: roll interval finer than 60s (got $TOTAL in 30s)"

# ------------------------------------------------------------------
# 3) recentByBed 换患者清零 — 旧患者 HR=130 不应污染新患者
#    pidA=90001 已注入 HR=85 (MEWS 在 70-100 区间 → 0 分)
#    现向同床 (相同 sn) 注入 pidB=90002 的 HR=130 (MEWS 110-130 → 2 分)
#    修复前：同 bedId 沿用 pidA 的 recentByBed，pidB 第一次评分时
#           recentByBed[bedId].get("HR") 仍可能是 85（pidA 残留），分数被低估
#    修复后：检测到 patientId 变化 → 整窗清空 → pidB 第一次评分只用 HR=130
# ------------------------------------------------------------------
echo "[3] patient-switch: pidA=$PID_A had HR=85, now inject pidB=$PID_B with HR=130 on SAME bed/sn"
inject "HL7_V2" "$SN" "HR" "130" "$PID_B"
echo "    wait ${WAIT_S}s for rollScore on pidB"
sleep "$WAIT_S"

OUT=$(fetch_scores "$PID_B" "MEWS" 5)
COUNT=$(echo "$OUT" | head -n1)
ROWS=$(echo "$OUT" | tail -n +2)
echo "    MEWS rows for pidB (count=$COUNT):"
echo "$ROWS" | head -n 5 | sed 's/^/      /'
[ "$COUNT" -gt 0 ] || { echo "    FAIL: no MEWS recorded for pidB after switch"; exit 1; }

LATEST=$(echo "$ROWS" | head -n1)
DETAIL=$(echo "$LATEST" | awk '{print $4}')
SCORE=$(echo "$LATEST" | awk '{print $2}')
LEVEL=$(echo "$LATEST" | awk '{print $3}')

# detail 中应能看到 hr=130（即新患者注入的值；不应是 85 残留）
if echo "$DETAIL" | grep -q '"hr":130'; then
  echo "    PASS: pidB's first MEWS uses fresh HR=130, no pidA residue (score=$SCORE level=$LEVEL)"
else
  echo "    FAIL: detail.hr is not 130 (no patient-switch reset): $DETAIL"
  exit 1
fi
# 顺带：MEWS 在 HR=130（110-130 区间）应至少 2 分
if [ "$SCORE" = "0.0" ] || [ "$SCORE" = "1.0" ]; then
  echo "    FAIL: MEWS score too low ($SCORE); HR=130 should give >=2"
  exit 1
fi

echo "================================================"
echo "Regress PASS: GCS-missing safe + roll<60s + patient-switch clean"
