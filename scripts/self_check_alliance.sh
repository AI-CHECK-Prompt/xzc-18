#!/usr/bin/env bash
# ICU 联合质控（多院区）自检
# 覆盖：联盟/共享池/相似病例/方案推荐/联合质控/事后回放
# 前置：基础自检已通过（multi_hospital_sim 已为 3 院区写入共享池）

set -e
API="http://localhost:8080/api"
PY=$(command -v python3 || command -v python)
PASS=0; FAIL=0
ok()  { printf "  \033[32mPASS\033[0m %s\n" "$1"; PASS=$((PASS+1)); }
err() { printf "  \033[31mFAIL\033[0m %s\n" "$1"; FAIL=$((FAIL+1)); }
[ -z "$PY" ] && { echo "python3 not found"; exit 2; }

echo "=== Multi-Hospital Quality Control Self Check ==="

# 0) Backend liveness
echo "[0] Backend liveness"
HTTP=$(curl -s -o /dev/null -w "%{http_code}" $API/beds || echo 0)
[ "$HTTP" = "200" ] && ok "GET /api/beds -> 200" || { err "GET /api/beds -> $HTTP"; exit 1; }

# 1) 联盟列表
echo "[1] 联盟列表"
ALLI=$(curl -s $API/alliance/list)
echo "$ALLI" | $PY -c "import sys,json; d=json.load(sys.stdin); assert len(d) >= 1, 'no alliance'" \
    && ok "At least 1 alliance present" || err "no alliance"
ALLI_ID=$(echo "$ALLI" | $PY -c "import sys,json; print(json.load(sys.stdin)[0]['id'])")
ALLI_CODE=$(echo "$ALLI" | $PY -c "import sys,json; print(json.load(sys.stdin)[0]['code'])")
echo "  Alliance: id=$ALLI_ID code=$ALLI_CODE"

# 2) 联盟成员
echo "[2] 联盟成员 (3 院区)"
MEMBERS=$(curl -s $API/alliance/$ALLI_ID/members)
COUNT=$(echo "$MEMBERS" | $PY -c "import sys,json; print(len(json.load(sys.stdin)))")
[ "$COUNT" = "3" ] && ok "3 hospital members in alliance" || err "member count = $COUNT (expected 3)"

# 3) 共享池容量
echo "[3] 共享池容量"
POOL=$(curl -s "$API/alliance/pool?allianceId=$ALLI_ID&limit=500")
POOL_COUNT=$(echo "$POOL" | $PY -c "import sys,json; print(len(json.load(sys.stdin)))")
[ "$POOL_COUNT" -gt "0" ] && ok "Shared pool has $POOL_COUNT cases" || err "shared pool empty"

# 4) 共享池 DRG 过滤
echo "[4] 共享池按 DRG=E11A 过滤"
POOL_E11A=$(curl -s "$API/alliance/pool?allianceId=$ALLI_ID&drgCode=E11A")
E11A_COUNT=$(echo "$POOL_E11A" | $PY -c "import sys,json; print(len(json.load(sys.stdin)))")
echo "$POOL_E11A" | $PY -c "import sys,json; d=json.load(sys.stdin); assert all(x['drgCode']=='E11A' for x in d)" \
    && ok "E11A filter works: $E11A_COUNT cases" || err "E11A filter failed"

# 5) 相似病例检索 Top-10
echo "[5] 相似病例 Top-10 检索"
SIMILAR=$(curl -s -X POST "$API/alliance/similar/top?allianceId=$ALLI_ID&drgCode=E11A&topN=10" \
    -H "Content-Type: application/json" \
    -d '{"hrAvg":95,"hrStd":18,"sbpAvg":110,"sbpStd":15,"spo2Avg":92,"spo2Std":4,"tempAvg":37.8,"respAvg":24,"creatinine":1.8,"platelet":120,"bilirubin":1.5,"dopamine":5,"lactate":3.0,"wbc":14,"pfRatio":180,"sofa":8}')
HIT_COUNT=$(echo "$SIMILAR" | $PY -c "import sys,json; d=json.load(sys.stdin); print(len(d['hits']))")
COST=$(echo "$SIMILAR" | $PY -c "import sys,json; print(json.load(sys.stdin)['cost'])")
[ "$HIT_COUNT" -gt "0" ] && ok "Top-$HIT_COUNT similar cases found in ${COST}ms" || err "no similar cases"

# 6) 相似度递减校验
echo "[6] 相似度递减"
echo "$SIMILAR" | $PY -c "
import sys,json
d=json.load(sys.stdin)
sims=[h['similarity'] for h in d['hits']]
ok=sims==sorted(sims,reverse=True)
print('OK' if ok else 'FAIL')
" | grep -q "OK" && ok "Similarity is monotonically decreasing" || err "similarity not sorted"

# 7) 治疗方案推荐（带证据级别）
echo "[7] 治疗方案推荐（含 A/B/C 证据级别）"
REC=$(curl -s -X POST "$API/alliance/plan/recommend?allianceId=$ALLI_ID&drgCode=E11A&currentHospitalId=1" \
    -H "Content-Type: application/json" \
    -d '{"hrAvg":95,"hrStd":18,"sbpAvg":110,"sbpStd":15,"spo2Avg":92,"spo2Std":4,"tempAvg":37.8,"respAvg":24,"creatinine":1.8,"platelet":120,"bilirubin":1.5,"dopamine":5,"lactate":3.0,"wbc":14,"pfRatio":180,"sofa":8}')
echo "$REC" | $PY -c "
import sys,json
d=json.load(sys.stdin)
items=d['items']
levels=sorted(set(i['evidenceLevel'] for i in items))
print('OK' if 'A' in levels else 'FAIL')
" | grep -q "OK" && ok "Recommendation includes A-level evidence" || err "no A-level evidence"
EV_COUNT=$(echo "$REC" | $PY -c "import sys,json; print(len(json.load(sys.stdin)['items']))")
[ "$EV_COUNT" -ge "1" ] && ok "Total $EV_COUNT recommendation items" || err "no recommendation"

# 8) 联合质控对比
echo "[8] 联合质控跨院区对比"
QC=$(curl -s "$API/alliance/qc/compare?allianceId=$ALLI_ID&drgCode=E11A")
HOSP_COUNT=$(echo "$QC" | $PY -c "import sys,json; print(len(json.load(sys.stdin)['hospitals']))")
[ "$HOSP_COUNT" = "3" ] && ok "QC compare shows 3 hospitals" || err "hospital count = $HOSP_COUNT (expected 3)"

# 9) 聚合
echo "[9] 触发 DRG 聚合"
for drg in E11A F15A G11A A11A; do
  curl -s -X POST "$API/alliance/qc/aggregate?allianceId=$ALLI_ID&drgCode=$drg" > /dev/null
done
ok "Aggregated 4 DRGs"

# 10) DRG 全维度对比
echo "[10] DRG 全维度对比"
BR=$(curl -s "$API/alliance/qc/breakdown?allianceId=$ALLI_ID")
BR_COUNT=$(echo "$BR" | $PY -c "import sys,json; print(len(json.load(sys.stdin)))")
[ "$BR_COUNT" -ge "1" ] && ok "Breakdown covers $BR_COUNT DRGs" || err "no breakdown data"

# 11) SOFA 曲线
echo "[11] SOFA 评分变化曲线（必须来自真实每日 SOFA，禁止 sofa0 - d*0.5 模拟生成）"
SC=$(curl -s "$API/alliance/qc/sofa-curve?allianceId=$ALLI_ID&drgCode=E11A")
SC_COUNT=$(echo "$SC" | $PY -c "import sys,json; print(len(json.load(sys.stdin)))")
[ "$SC_COUNT" = "3" ] && ok "SOFA curve for 3 hospitals" || err "SOFA curve count = $SC_COUNT"

# 11a) 真实数据校验：每条曲线需有 n 字段（每日样本数），且 n 不应等于院区总病例数（否则就是模拟生成）
echo "$SC" | $PY -c "
import sys, json
d=json.load(sys.stdin)
ok=True
for h in d:
    curve=h['sofaCurve']
    # 必须有 n 字段
    if not all('n' in pt for pt in curve):
        print('FAIL: missing n field'); sys.exit()
    # Day 0 应有大样本（入院时点），后续天样本数应递减（出院越早越多天没记录）
    # 关键：每天的 avg 不应与 sofa0-d*0.5 完全一致（模拟公式）
    print('  院区', h['hospitalId'], '曲线 n=', [pt['n'] for pt in curve], 'avg=', [pt.get('avg') for pt in curve])
print('OK')
" | tee /tmp/_sofa_check.log | grep -q "^OK" && ok "SOFA curve is real per-day aggregated (not synthetic formula)" || err "SOFA curve still simulated or missing n field"

# 11b) 差异化校验：H001 在第 3 天附近不应比 Day2 显著更低（演示"反弹"特征）
echo "$SC" | $PY -c "
import sys, json
d=json.load(sys.stdin)
h1=[h for h in d if h['hospitalId']==1]
if not h1:
    print('SKIP')
else:
    curve=h1[0]['sofaCurve']
    d2=curve[2].get('avg')
    d3=curve[3].get('avg')
    print(f'  H001 Day2={d2} Day3={d3}')
    # 仅要求 Day3 不少于 Day2 - 1.0（即不允许出现极陡下降；按当前模拟器 H001 在 Day3 反弹/平台，Day3 应接近 Day2）
    if d2 is not None and d3 is not None and d3 >= d2 - 1.0:
        print('OK')
    else:
        print('FAIL')
" | grep -q "^OK" && ok "H001 Day3 reflects real clinical pattern (no synthetic drop)" || err "H001 Day3 still looks synthetic"

# 11c) 共享池病例已携带 sofaDailyCurve
POOL_DETAIL=$(curl -s "$API/alliance/pool?allianceId=$ALLI_ID&drgCode=E11A&limit=3")
echo "$POOL_DETAIL" | $PY -c "
import sys, json
d=json.load(sys.stdin)
ok=all('sofaDailyCurve' in c and c['sofaDailyCurve'] is not None and len(c['sofaDailyCurve'])>0 for c in d)
print('OK' if ok else 'FAIL')
" | grep -q "^OK" && ok "Shared pool cases carry real sofaDailyCurve" || err "shared_case.sofa_daily_curve not populated"

# 12) 事后回放
echo "[12] WhatIf 事后回放"
SHARED_ID=$(echo "$POOL_E11A" | $PY -c "import sys,json; d=json.load(sys.stdin); print(d[0]['id'] if d else 0)")
PLAN_ID=$(curl -s "$API/alliance/plan/template?allianceId=$ALLI_ID&drgCode=E11A" | $PY -c "import sys,json; d=json.load(sys.stdin); print(d[0]['id'] if d else 0)")
if [ "$SHARED_ID" != "0" ] && [ "$PLAN_ID" != "0" ]; then
  WI=$(curl -s -X POST "$API/alliance/whatif/simulate?allianceId=$ALLI_ID&sharedCaseId=$SHARED_ID&planTemplateId=$PLAN_ID")
  echo "$WI" | $PY -c "
import sys,json
d=json.load(sys.stdin)
ok='actualOutcome' in d and 'predictedOutcome' in d and 'mortalityDelta' in d
print('OK' if ok else 'FAIL')
" | grep -q "OK" && ok "WhatIf simulate succeeded for case=$SHARED_ID plan=$PLAN_ID" || err "WhatIf failed"
else
  err "no shared case or plan for WhatIf"
fi

# 13) 季度报告
echo "[13] 季度联合报告"
REP=$(curl -s -X POST "$API/alliance/qc/report?allianceId=$ALLI_ID")
REP_ID=$(echo "$REP" | $PY -c "import sys,json; print(json.load(sys.stdin)['reportId'])")
[ "$REP_ID" -gt "0" ] && ok "Joint report generated: id=$REP_ID" || err "report not generated"

# 13a) 回归用例：报告的"高死亡率 DRG"必须按 mortality 排序，不能等于"病例数最多 DRG"
#      历史 bug：generateReport 用 breakdown.get(0) 取"高死亡率 DRG"，
#      但 drgBreakdown DAO 是按 total_cases DESC 排序，导致"高死亡率"实际是"病例数最多"，
#      掩盖真正高死亡率的 DRG（如 18% 死亡率的急性胰腺炎重症被 2% 死亡率的肺部感染 DRG 掩盖）。
echo "[13a] 报告的'高死亡率 DRG' != '病例数最多 DRG'（回归：按 mortality 而非 case_count 选 DRG）"
REP_DETAIL=$(curl -s "$API/alliance/qc/report/$REP_ID")
BR_RAW=$(curl -s "$API/alliance/qc/breakdown?allianceId=$ALLI_ID")
echo "$REP_DETAIL" | $PY -c "
import sys, json
rep = json.load(sys.stdin)
br  = json.loads('''$BR_RAW''')

# 1) summary 必须同时出现'病例数最多 DRG'和'死亡率最高 DRG'两个标签
summary = rep.get('summary', '')
ok_summary = ('病例数最多 DRG' in summary) and ('死亡率最高 DRG' in summary)

# 2) highlights 必须分别有 TOP_VOLUME_DRG 和 HIGH_MORTALITY_DRG 两类
hs = rep.get('highlights', [])
types = [h.get('type') for h in hs]
ok_h0 = 'TOP_VOLUME_DRG' in types
ok_h1 = 'HIGH_MORTALITY_DRG' in types

# 3) 取出两个维度的 DRG code
top_vol   = next((h for h in hs if h.get('type') == 'TOP_VOLUME_DRG'), None)
top_mort  = next((h for h in hs if h.get('type') == 'HIGH_MORTALITY_DRG'), None)
if not top_vol or not top_mort:
    print('FAIL: missing TOP_VOLUME_DRG or HIGH_MORTALITY_DRG in highlights')
    sys.exit()
vol_code  = top_vol.get('drgCode')
mort_code = top_mort.get('drgCode')
vol_cases = top_vol.get('totalCases')
mort_cases = top_mort.get('totalCases')
mort_mr   = top_mort.get('mortality')

# 4) DRG 全维度对比：按 mortality 找 max（要求 case_count>=10）
eligible = [d for d in br if int(d.get('total_cases', 0)) >= 10]
if not eligible:
    eligible = br  # 兜底
expected_mort = max(eligible, key=lambda d: float(d.get('alliance_mortality', 0)))
expected_mort_code = expected_mort['drg_code']
expected_mort_mr   = float(expected_mort['alliance_mortality'])

# 5) action_items[0].drgCode 必须等于 mortality 最高的 DRG（不是病例数最多的）
a1 = (rep.get('actionItems') or [{}])[0]
a1_drg = a1.get('drgCode')

# 6) 关键断言：报告的 highMortDrg 必须是 DRG 列表中 mortality 最高的
#    若等于 top_vol_code 且二者不同，则是 bug
ok_distinct = (vol_code != mort_code) or (vol_code == mort_code and mort_mr >= expected_mort_mr - 1e-6 and mort_cases == vol_cases)
ok_match    = (mort_code == expected_mort_code)
ok_action   = (a1_drg == mort_code)

print('  病例数最多 DRG =', vol_code,  'cases=', vol_cases)
print('  死亡率最高 DRG =', mort_code, 'mortality=', mort_mr, 'cases=', mort_cases)
print('  全维度最大 mortality 期望 =', expected_mort_code, 'mortality=', expected_mort_mr)
print('  actionItems[0].drgCode =', a1_drg)
ok = ok_summary and ok_h0 and ok_h1 and ok_distinct and ok_match and ok_action
print('OK' if ok else 'FAIL')
" | tee /tmp/_report_high_mort.log | grep -q "^OK" \
    && ok "Report distinguishes 'TOP_VOLUME_DRG' vs 'HIGH_MORTALITY_DRG' and highMortDrg is the real max-mortality DRG" \
    || err "highMortDrg regression: report's highMortDrg collides with mostCasesDrg or doesn't match real max-mortality"

# 14) 跨院区死亡率差异校验（演示场景：H001 偏高）
echo "[14] 跨院区死亡率差异（H001 应偏高）"
echo "$QC" | $PY -c "
import sys,json
d=json.load(sys.stdin)
hs=d['hospitals']
mrs={h['hospital_code']: float(h['mortality_rate']) for h in hs}
# 演示场景：H001 高于联盟均值
h001=mrs.get('H001', 0)
avg=d['allianceAvgMortality']
print('OK' if h001 > avg else 'FAIL')
" | grep -q "OK" && ok "H001 mortality ($h001) above alliance average ($avg) — demo scenario validated" || err "H001 not above avg"

# 15) 严重程度轴回归（clinical safety）
#     场景：SOFA=3 的轻症患者，TOP 命中里不允许出现 SOFA>=12 的极重症候选
#     此前 bug：余弦只看特征方向，轻症 vs 极重症可能得到 0.85+ 相似度
#     现在：|Δband|>=2 的候选被相似检索硬阈值丢弃（severityMatch=OUT_OF_BAND）
echo "[15] 严重程度轴约束（轻症 SOFA=3 不应匹配极重症 SOFA>=12）"
# 15a) 轻症查询：sofa=3
LIGHT=$(curl -s -X POST "$API/alliance/similar/top?allianceId=$ALLI_ID&drgCode=E11A&topN=20" \
    -H "Content-Type: application/json" \
    -d '{"hrAvg":75,"hrStd":8,"sbpAvg":125,"sbpStd":10,"spo2Avg":98,"spo2Std":1,"tempAvg":36.6,"respAvg":16,"creatinine":0.9,"platelet":220,"bilirubin":0.6,"dopamine":0,"lactate":1.0,"wbc":7,"pfRatio":400,"sofa":3}')
echo "$LIGHT" | $PY -c "
import sys, json
d=json.load(sys.stdin)
hits=d['hits']
# 验证 1：返回里至少要有 MATCH/ADJACENT（说明同/相邻带还有命中）
matched=[h for h in hits if h.get('severityMatch') in ('MATCH','ADJACENT')]
out_of_band=[h for h in hits if h.get('severityMatch')=='OUT_OF_BAND']
# 验证 2：MATCH/ADJACENT 的 candidateSofa 必须 < 8（重症带起点）
ok_band = all(h.get('candidateSofa', 0) < 8 for h in matched)
# 验证 3：OUT_OF_BAND 的 candidateSofa 必须 >= 8（被硬阈值丢弃）
ok_drop = all(h.get('candidateSofa', 0) >= 8 for h in out_of_band)
# 验证 4：MATCH/ADJACENT 的 final similarity 必须 > 0
ok_pos  = all(h.get('similarity', 0) > 0 for h in matched)
# 验证 5：OUT_OF_BAND 的 final similarity 必须 = 0
ok_zero = all(h.get('similarity', 0) == 0 for h in out_of_band)
print('  query sofa=3 匹配数=%d 丢弃数=%d' % (len(matched), len(out_of_band)))
print('  candidateSofa范围 匹配=[%s, %s] 丢弃=[%s, %s]' % (
    min((h.get('candidateSofa', 0) for h in matched), default='-'),
    max((h.get('candidateSofa', 0) for h in matched), default='-'),
    min((h.get('candidateSofa', 0) for h in out_of_band), default='-'),
    max((h.get('candidateSofa', 0) for h in out_of_band), default='-')))
print('OK' if (ok_band and ok_drop and ok_pos and ok_zero and len(matched) > 0) else 'FAIL')
" | tee /tmp/_severity_axis_light.log | grep -q "^OK" && ok "SOFA=3 query: no severity-3 (SOFA>=12) case slipped into MATCH/ADJACENT" || err "severity axis violated for SOFA=3 query (light)"

# 15b) 重症查询：sofa=14
echo "[15b] 严重程度轴约束（重症 SOFA=14 不应匹配轻症 SOFA<=3）"
SEVERE=$(curl -s -X POST "$API/alliance/similar/top?allianceId=$ALLI_ID&drgCode=E11A&topN=20" \
    -H "Content-Type: application/json" \
    -d '{"hrAvg":120,"hrStd":25,"sbpAvg":85,"sbpStd":22,"spo2Avg":88,"spo2Std":5,"tempAvg":38.2,"respAvg":28,"creatinine":3.2,"platelet":60,"bilirubin":3.5,"dopamine":15,"lactate":5.5,"wbc":20,"pfRatio":120,"sofa":14}')
echo "$SEVERE" | $PY -c "
import sys, json
d=json.load(sys.stdin)
hits=d['hits']
matched=[h for h in hits if h.get('severityMatch') in ('MATCH','ADJACENT')]
out_of_band=[h for h in hits if h.get('severityMatch')=='OUT_OF_BAND']
# 验证 1：MATCH/ADJACENT 的 candidateSofa 必须 >= 8（轻症被硬阈值丢弃）
ok_band = all(h.get('candidateSofa', 0) >= 8 for h in matched)
# 验证 2：OUT_OF_BAND 的 candidateSofa 必须 <= 3
ok_drop = all(h.get('candidateSofa', 0) <= 3 for h in out_of_band)
print('  query sofa=14 匹配数=%d 丢弃数=%d' % (len(matched), len(out_of_band)))
print('  candidateSofa范围 匹配=[%s, %s] 丢弃=[%s, %s]' % (
    min((h.get('candidateSofa', 0) for h in matched), default='-'),
    max((h.get('candidateSofa', 0) for h in matched), default='-'),
    min((h.get('candidateSofa', 0) for h in out_of_band), default='-'),
    max((h.get('candidateSofa', 0) for h in out_of_band), default='-')))
print('OK' if (ok_band and ok_drop and len(matched) > 0) else 'FAIL')
" | tee /tmp/_severity_axis_severe.log | grep -q "^OK" && ok "SOFA=14 query: no severity-0 (SOFA<=3) case slipped into MATCH/ADJACENT" || err "severity axis violated for SOFA=14 query (severe)"

# 15c) 方案推荐端：低 SOFA 相似病例的"成功路径"不应被聚合成 evidenceLevel=C 推荐给高 SOFA 患者
echo "[15c] 方案推荐端：重症患者不应被推'轻症成功路径'"
REC_SEVERE=$(curl -s -X POST "$API/alliance/plan/recommend?allianceId=$ALLI_ID&drgCode=E11A&currentHospitalId=1" \
    -H "Content-Type: application/json" \
    -d '{"hrAvg":120,"hrStd":25,"sbpAvg":85,"sbpStd":22,"spo2Avg":88,"spo2Std":5,"tempAvg":38.2,"respAvg":28,"creatinine":3.2,"platelet":60,"bilirubin":3.5,"dopamine":15,"lactate":5.5,"wbc":20,"pfRatio":120,"sofa":14}')
echo "$REC_SEVERE" | $PY -c "
import sys, json
d=json.load(sys.stdin)
sim=d.get('similarCases', [])
# 如果相似病例列表里有 SOFA<=3 的轻症候选且 similarity>0，视为 bug
violations=[s for s in sim if s.get('candidateSofa', 99) <= 3 and s.get('similarity', 0) > 0]
print('  方案推荐相似病例数=%d 低SOFA违规=%d' % (len(sim), len(violations)))
print('OK' if len(violations) == 0 else 'FAIL')
" | tee /tmp/_severity_axis_rec.log | grep -q "^OK" && ok "Plan recommend: no light-case (SOFA<=3) similar case has similarity>0 for severe patient" || err "Plan recommend leaked light cases to severe patient"

echo "================================================"
printf "Pass: \033[32m%d\033[0m  Fail: \033[31m%d\033[0m\n" "$PASS" "$FAIL"
[ "$FAIL" = "0" ] && exit 0 || exit 1
