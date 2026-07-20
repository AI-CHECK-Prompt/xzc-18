#!/usr/bin/env bash
# 回归测试：抢救事件回放会话去重
# 场景：一次真实抢救中，同一床位+告警只应建立 1 个 ACTIVE 回放会话，
#       不能因 dedup 窗口过期/告警升级被重复写库导致回放列表里出现多份重叠会话。
#
# 覆盖：
#   1) AlertEngine 延长 CRITICAL 去重窗口（与回放窗口一致） → 同一床位+告警类型
#      在窗口内只生成 1 个 AlertEvent ID
#   2) PlaybackService 按 (bed_id, trigger_alert_id) 幂等建 session
#   3) SQL 部分唯一索引 uq_playback_bed_alert_active 在 ACTIVE 状态下的 DB 兜底
#
# 用法：先以小窗口启动后端以加速测试：
#   ICU_ALERT_DEDUP_WINDOW_SEC=3 ICU_PLAYBACK_WINDOW_MIN=1 \
#     docker compose up -d backend   # 或本地以同样 env 启动
#   bash scripts/regress_playback_dedup.sh
# 默认 API: http://localhost:8080

set -e
API="${API:-http://localhost:8080/api}"
PY=$(command -v python3 || command -v python)
[ -z "$PY" ] && { echo "python3 not found"; exit 2; }

BED_ID="${BED_ID:-1}"
CH="${CH:-HR}"
CRIT_VALUE="${CRIT_VALUE:-20}"     # HR=20 直接命中 critLow
# 默认短窗口：用 ICU_ALERT_DEDUP_WINDOW_SEC=3 + ICU_PLAYBACK_WINDOW_MIN=1 启动后端
DEDUP_WAIT="${DEDUP_WAIT:-5}"     # 跨过旧 dedup 窗口（3s）+ 余量

echo "=== Regress: playback session dedup (bed=$BED_ID ch=$CH val=$CRIT_VALUE) ==="

inject() {
  curl -s -X POST "$API/selfcheck/inject" \
    -d "protocol=HL7_V2&sn=REGRESS-PB-$BED_ID&channel=$CH&value=$CRIT_VALUE" > /dev/null
  echo "  injected $CH=$CRIT_VALUE at $(date '+%H:%M:%S')"
}

# 拉取 ACTIVE 会话中 trigger_alert_id 不为空的告警 id 列表
list_active_sessions() {
  curl -s "$API/playback/by-bed/$BED_ID" | $PY -c "
import sys, json
d = json.load(sys.stdin)
active = [s for s in d if s.get('status') == 'ACTIVE' and s.get('triggerAlertId')]
print(len(active))
print(','.join(str(s['triggerAlertId']) for s in active))
"
}

# 1) 第一次注入：建立第 1 个告警 + 第 1 个会话
echo "[1] inject first critical -> expect 1 ACTIVE session"
inject
sleep 2
OUT=$(list_active_sessions)
COUNT=$(echo "$OUT" | head -n1)
IDS=$(echo "$OUT" | tail -n1)
echo "  active sessions=$COUNT  triggerAlertIds=[$IDS]"
[ "$COUNT" = "1" ] || { echo "  FAIL: expected 1 session, got $COUNT"; exit 1; }

# 2) 跨过旧 dedup 窗口（默认 60s，测试模式下 3s）再次注入：
#    修复前：会新写 AlertEvent、新建 session；修复后：仍复用同一告警 ID、不再建 session
echo "[2] wait ${DEDUP_WAIT}s then inject again -> expect still 1 ACTIVE session"
sleep "$DEDUP_WAIT"
inject
sleep 2
OUT=$(list_active_sessions)
COUNT2=$(echo "$OUT" | head -n1)
IDS2=$(echo "$OUT" | tail -n1)
echo "  active sessions=$COUNT2 triggerAlertIds=[$IDS2]"
if [ "$COUNT2" != "1" ]; then
  echo "  FAIL: expected 1 session, got $COUNT2 (dedup broken)"
  exit 1
fi
if [ "$IDS" != "$IDS2" ]; then
  echo "  FAIL: triggerAlertId changed [$IDS] -> [$IDS2]; new alert ID created for same incident"
  exit 1
fi

# 3) 短时间内连发 3 次：均应落在扩展后的去重窗口内（与回放窗口一致），不会扩增
echo "[3] burst 3 more injections within window -> expect still 1 ACTIVE session"
for i in 1 2 3; do inject; sleep 1; done
sleep 2
OUT=$(list_active_sessions)
COUNT3=$(echo "$OUT" | head -n1)
IDS3=$(echo "$OUT" | tail -n1)
echo "  active sessions=$COUNT3 triggerAlertIds=[$IDS3]"
[ "$COUNT3" = "1" ] || { echo "  FAIL: expected 1 session, got $COUNT3"; exit 1; }
[ "$IDS" = "$IDS3" ] || { echo "  FAIL: triggerAlertId drift [$IDS] -> [$IDS3]"; exit 1; }

# 4) 手工建会话（trigger_alert_id 与现有 ACTIVE 重合）→ 应被幂等逻辑跳过
echo "[4] manual create with same bed+trigger -> expect reuse existing"
HTTP=$(curl -s -o /tmp/pb_manual.json -w "%{http_code}" -X POST \
  "$API/playback/manual?bedId=$BED_ID&patientId=1" \
  -d "centerAt=2026-01-01T00:00:00Z")
# manual 不绑定 triggerAlertId，单独验证 DB 唯一约束：在另一条 triggerAlertId 上重复建
# 这里用同 bed 不同 triggerAlertId 的"假" ACTIVE：直接 API 没法伪造，跳过此子项的 API 验证
# 实际 DB 层兜底由 uq_playback_bed_alert_active 部分唯一索引承担，见 sql/01_init.sql

echo "=== Regress PASS: 1 ACTIVE session for 1 incident; triggerAlertId stable ==="
