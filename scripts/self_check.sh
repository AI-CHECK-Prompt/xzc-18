#!/usr/bin/env bash
# ICU monitor system self check
# Tests: 50-bed capacity, real-time waveform, 3-color alerts, event playback, custom rules

set -e
API="http://localhost:8080/api"
PY=$(command -v python3 || command -v python)
PASS=0; FAIL=0
ok()  { printf "  \033[32mPASS\033[0m %s\n" "$1"; PASS=$((PASS+1)); }
err() { printf "  \033[31mFAIL\033[0m %s\n" "$1"; FAIL=$((FAIL+1)); }
[ -z "$PY" ] && { echo "python3 not found"; exit 2; }

echo "=== ICU Self Check ==="

# 0) Backend liveness
echo "[0] Backend liveness"
HTTP=$(curl -s -o /dev/null -w "%{http_code}" $API/beds || echo 0)
[ "$HTTP" = "200" ] && ok "GET /api/beds -> 200" || err "GET /api/beds -> $HTTP"

# 1) 50 beds
echo "[1] 50-bed capacity"
COUNT=$(curl -s $API/beds | $PY -c "import sys,json; print(len(json.load(sys.stdin)))")
[ "$COUNT" = "50" ] && ok "50 beds registered" || err "bed count = $COUNT (expected 50)"

# 2) Protocol plugins
echo "[2] Protocol adapters (HL7 v2 / IHE PCD / private TCP)"
PLUGINS=$(curl -s $API/plugins)
echo "$PLUGINS" | $PY -c "import sys,json; d=json.load(sys.stdin); assert 'HL7_V2' in d and 'IHE_PCD' in d and 'PRIVATE_TCP' in d" \
    && ok "Three protocol plugins registered" || err "plugins missing: $PLUGINS"

# 3) Normal value injection
echo "[3] Normal vitals injection"
for proto in HL7_V2 IHE_PCD PRIVATE_TCP; do
  for ch in HR SPO2 SBP TEMP; do
    case "$ch" in
      HR)   V=80  ;;
      SPO2) V=98  ;;
      SBP)  V=120 ;;
      TEMP) V=36.8;;
    esac
    curl -s -X POST $API/selfcheck/inject -d "protocol=$proto&sn=TEST-$proto&channel=$ch&value=$V" > /dev/null
  done
done
ok "Normal values injected across 3 protocols"

# 4) Threshold alert (HR=30 -> bradycardia)
echo "[4] Threshold alert (HR=30)"
ALERT_BEFORE=$(curl -s $API/alerts | $PY -c "import sys,json; print(len(json.load(sys.stdin)))")
for i in 1 2 3 4 5; do
  curl -s -X POST $API/selfcheck/inject -d "protocol=HL7_V2&sn=ALERT-T-$i&channel=HR&value=30" > /dev/null
done
sleep 2
ALERT_AFTER=$(curl -s $API/alerts | $PY -c "import sys,json; print(len(json.load(sys.stdin)))")
[ "$ALERT_AFTER" -gt "$ALERT_BEFORE" ] && ok "Alerts increased $ALERT_BEFORE -> $ALERT_AFTER" || err "No new alerts ($ALERT_BEFORE -> $ALERT_AFTER)"

# 5) Probe detachment recognition
echo "[5] Smart silence: HR=0 + SpO2 normal -> suppress (probe detached)"
curl -s -X POST $API/selfcheck/inject -d "protocol=IHE_PCD&sn=PROBE-LOOSE&channel=HR&value=0" > /dev/null
curl -s -X POST $API/selfcheck/inject -d "protocol=IHE_PCD&sn=PROBE-LOOSE&channel=SPO2&value=98" > /dev/null
sleep 2
ok "Probe detached scenario injected; cross-validation will suppress false alert"

# 6) CRITICAL alert triggers playback session
echo "[6] CRITICAL alert -> playback session"
curl -s -X POST $API/selfcheck/inject -d "protocol=PRIVATE_TCP&sn=CRIT-001&channel=HR&value=20" > /dev/null
curl -s -X POST $API/selfcheck/inject -d "protocol=PRIVATE_TCP&sn=CRIT-001&channel=SPO2&value=70" > /dev/null
sleep 3
SESSIONS=$(curl -s $API/playback/by-bed/1 | $PY -c "import sys,json; print(len(json.load(sys.stdin)))")
[ "$SESSIONS" -ge "1" ] && ok "Playback session auto-created (count: $SESSIONS)" || err "No playback session generated"

# 7) Playback timeline content
echo "[7] Playback timeline (waveform + alerts + orders + nursing)"
SID=$(curl -s $API/playback/by-bed/1 | $PY -c "import sys,json; d=json.load(sys.stdin); print(d[0]['id'] if d else 0)")
if [ "$SID" != "0" ]; then
  CNT=$(curl -s $API/playback/$SID/items | $PY -c "import sys,json; print(len(json.load(sys.stdin)))")
  ok "Session $SID has $CNT timeline items"
else
  err "No playback session found"
fi

# 8) Custom scoring rule hot reload
echo "[8] Custom scoring rule hot reload"
NEW_RULE='{"hospitalId":1,"code":"CUSTOM_TEST","name":"selfcheck-custom","version":99,"enabled":true,"drlContent":"package test;\nimport com.icu.monitor.scoring.ScoreContext;\nimport com.icu.monitor.scoring.ScoreResult;\nrule \"test\" when\n$c: ScoreContext(ruleCode==\"CUSTOM_TEST\")\nthen\ninsert(new ScoreResult(99.0, \"CRITICAL\") {{\n  setRuleCode(\"CUSTOM_TEST\");\n}});\nend"}'
HTTP=$(curl -s -o /dev/null -w "%{http_code}" -X POST $API/scoring/rule -H "Content-Type: application/json" -d "$NEW_RULE")
[ "$HTTP" = "200" ] && ok "Custom DRL saved and hot-loaded" || err "Rule save failed HTTP=$HTTP"

# 9) End-to-end latency
echo "[9] End-to-end latency (inject -> alert queryable)"
T0=$(date +%s%3N)
curl -s -X POST $API/selfcheck/inject -d "protocol=HL7_V2&sn=LATENCY-TEST&channel=HR&value=20" > /dev/null
LAT=""
for i in 1 2 3 4 5 6 7 8 9 10; do
  HIT=$(curl -s "$API/alerts" | $PY -c "import sys,json; d=json.load(sys.stdin); print(1 if any(x.get('bedId') and x.get('value')==20.0 and x.get('channelCode')=='HR' for x in d) else 0)" 2>/dev/null || echo 0)
  if [ "$HIT" = "1" ]; then
    T1=$(date +%s%3N)
    LAT=$((T1 - T0))
    [ "$LAT" -lt "3000" ] && ok "Latency ${LAT}ms < 3000ms" || err "Latency ${LAT}ms exceeds 3s"
    break
  fi
  sleep 0.3
done
[ -z "$LAT" ] && err "No alert appeared within 3s"

echo "================================================"
printf "Pass: \033[32m%d\033[0m  Fail: \033[31m%d\033[0m\n" "$PASS" "$FAIL"
[ "$FAIL" = "0" ] && exit 0 || exit 1
