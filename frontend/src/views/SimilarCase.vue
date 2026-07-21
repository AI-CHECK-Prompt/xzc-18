<template>
  <div class="page">
    <h2>相似病例检索 & 治疗方案推荐</h2>

    <section class="card">
      <h3>① 新患者特征输入（入院 24h 内）</h3>
      <div class="form-grid">
        <label>DRG 编码 <input v-model="drgCode" placeholder="E11A" /></label>
        <label>当前院区 <input v-model.number="currentHospitalId" type="number" /></label>
        <label>HR 均值 <input v-model.number="features.hrAvg" type="number" step="0.1" /></label>
        <label>HR 标准差 <input v-model.number="features.hrStd" type="number" step="0.1" /></label>
        <label>SBP 均值 <input v-model.number="features.sbpAvg" type="number" step="0.1" /></label>
        <label>SBP 标准差 <input v-model.number="features.sbpStd" type="number" step="0.1" /></label>
        <label>SpO2 均值 <input v-model.number="features.spo2Avg" type="number" step="0.1" /></label>
        <label>SpO2 标准差 <input v-model.number="features.spo2Std" type="number" step="0.1" /></label>
        <label>体温 <input v-model.number="features.tempAvg" type="number" step="0.1" /></label>
        <label>呼吸 <input v-model.number="features.respAvg" type="number" step="0.1" /></label>
        <label>肌酐 <input v-model.number="features.creatinine" type="number" step="0.1" /></label>
        <label>血小板 <input v-model.number="features.platelet" type="number" step="1" /></label>
        <label>胆红素 <input v-model.number="features.bilirubin" type="number" step="0.1" /></label>
        <label>多巴胺 <input v-model.number="features.dopamine" type="number" step="0.1" /></label>
        <label>乳酸 <input v-model.number="features.lactate" type="number" step="0.1" /></label>
        <label>WBC <input v-model.number="features.wbc" type="number" step="0.1" /></label>
        <label>P/F <input v-model.number="features.pfRatio" type="number" step="1" /></label>
        <label>SOFA <input v-model.number="features.sofa" type="number" step="0.1" /></label>
      </div>
      <div class="row" style="margin-top: 12px;">
        <button @click="searchSimilar" :disabled="loading">检索 Top-{{ topN }} 相似病例</button>
        <button @click="recommend" :disabled="loading">推荐治疗方案</button>
        <span v-if="lastCost">耗时 {{ lastCost }} ms</span>
      </div>
    </section>

    <section class="card" v-if="similarHits.length > 0">
      <h3>② Top-{{ similarHits.length }} 相似病例治疗路径</h3>
      <div v-for="(hit, idx) in similarHits" :key="hit.sharedCaseId" class="hit-row">
        <div class="hit-header">
          <span class="rank">#{{ idx + 1 }}</span>
          <span class="sim-bar" :style="{ width: hit.similarity * 200 + 'px' }"></span>
          <span class="sim-val">相似度 {{ (hit.similarity * 100).toFixed(1) }}%</span>
          <span>院区 #{{ hit.sourceHospital }}</span>
          <span>DRG <b>{{ hit.drgCode }}</b></span>
          <span :class="outcomeClass(hit.outcome)">{{ hit.outcome }}</span>
          <span>住院 {{ hit.losDays }}d</span>
          <button @click="toggleDetail(hit)" class="btn-small">▶ 路径</button>
        </div>
        <div v-if="expandedId === hit.sharedCaseId" class="timeline">
          <div v-for="(step, i) in hit.treatmentPath" :key="i" class="timeline-item">
            <span class="t-time">{{ step.time }}</span>
            <span class="t-name">{{ step.itemName }}</span>
            <span class="t-status">{{ step.status }}</span>
          </div>
          <div v-if="hit.rescueEvents && hit.rescueEvents.length > 0" class="rescue">
            <b>抢救事件：</b>
            <span v-for="(r, i) in hit.rescueEvents" :key="i" class="rescue-tag">{{ r.type }} @ {{ r.time }}</span>
          </div>
        </div>
      </div>
    </section>

    <section class="card" v-if="recommendResult">
      <h3>③ 治疗方案推荐（按证据级别排序）</h3>
      <div v-for="(item, i) in recommendResult.items" :key="i" class="rec-item">
        <div class="rec-header">
          <span :class="['evidence', 'ev-' + item.evidenceLevel]">{{ item.evidenceLevel }}</span>
          <b>{{ item.title }}</b>
          <span class="based-on">来源：{{ item.basedOn }}</span>
          <a v-if="item.url" :href="item.url" target="_blank" class="ev-link">证据链接 ↗</a>
        </div>
        <p v-if="item.summary" class="rec-summary">{{ item.summary }}</p>
        <div v-if="item.steps" class="rec-steps">
          <div v-for="(s, k) in item.steps" :key="k" class="step">
            <code v-if="typeof s === 'string'">{{ s }}</code>
            <template v-else>
              <code>{{ s.action || s.key }}</code>
              <span v-if="s.freq !== undefined" class="freq">×{{ s.freq }} 成功率 {{ s.successRate }}%</span>
            </template>
          </div>
        </div>
        <div v-if="item.supportCount > 0" class="support">
          支撑病例：{{ item.supportCount }} 例，相似病例成功率 {{ item.successRate }}%
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import api from '../api'

const drgCode = ref('E11A')
const currentHospitalId = ref<number>(1)
const topN = ref(10)
const loading = ref(false)
const lastCost = ref(0)
const features = ref({
  hrAvg: 95, hrStd: 18,
  sbpAvg: 110, sbpStd: 15,
  spo2Avg: 92, spo2Std: 4,
  tempAvg: 37.8, respAvg: 24,
  creatinine: 1.8, platelet: 120, bilirubin: 1.5,
  dopamine: 5, lactate: 3.0, wbc: 14,
  pfRatio: 180, sofa: 8
})
const similarHits = ref<any[]>([])
const recommendResult = ref<any>(null)
const expandedId = ref<number | null>(null)
let currentAllianceId = 1

onMounted(async () => {
  const r = await api.alliance.list()
  if (r.data.length > 0) currentAllianceId = r.data[0].id
})

async function searchSimilar() {
  loading.value = true
  try {
    const r = await api.alliance.topSimilar(
      { allianceId: currentAllianceId, drgCode: drgCode.value, topN: topN.value, currentHospitalId: currentHospitalId.value },
      features.value
    )
    similarHits.value = r.data.hits
    lastCost.value = r.data.cost
  } finally { loading.value = false }
}

async function recommend() {
  loading.value = true
  try {
    const r = await api.alliance.recommend(
      { allianceId: currentAllianceId, drgCode: drgCode.value, currentHospitalId: currentHospitalId.value },
      features.value
    )
    recommendResult.value = r.data
    similarHits.value = r.data.similarCases
  } finally { loading.value = false }
}

function toggleDetail(hit: any) {
  expandedId.value = expandedId.value === hit.sharedCaseId ? null : hit.sharedCaseId
}

function outcomeClass(o: string) {
  if (o === 'DECEASED') return 'tag-deceased'
  if (o === 'TRANSFERRED') return 'tag-transferred'
  return 'tag-survived'
}
</script>

<style scoped>
.page { padding: 24px; }
.card { background: #14171c; padding: 16px; border-radius: 6px; margin-bottom: 16px; }
.card h3 { margin-top: 0; }
.form-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.form-grid label { display: flex; flex-direction: column; font-size: 12px; color: #adb5bd; }
.form-grid input { padding: 4px 8px; background: #0f1115; color: #e0e0e0; border: 1px solid #2a2e34; border-radius: 4px; }
.row { display: flex; gap: 8px; align-items: center; }
button { padding: 6px 12px; background: #4cc9f0; color: #14171c; border: none; border-radius: 4px; cursor: pointer; font-weight: 500; }
button:disabled { background: #2a2e34; color: #6c757d; cursor: not-allowed; }
.btn-small { padding: 2px 8px; font-size: 11px; }

.hit-row { padding: 8px 12px; background: #0f1115; border-left: 3px solid #4cc9f0; margin-bottom: 6px; border-radius: 3px; }
.hit-header { display: flex; gap: 12px; align-items: center; font-size: 13px; }
.rank { background: #4cc9f0; color: #14171c; padding: 1px 8px; border-radius: 3px; font-weight: 700; }
.sim-bar { display: inline-block; height: 6px; background: linear-gradient(90deg, #4cc9f0, #06d6a0); border-radius: 3px; }
.sim-val { color: #06d6a0; font-weight: 600; }
.tag-deceased { color: #d62828; font-weight: 600; }
.tag-transferred { color: #f9c74f; }
.tag-survived { color: #06d6a0; }

.timeline { margin-top: 8px; padding: 8px; background: #14171c; border-radius: 3px; }
.timeline-item { display: flex; gap: 12px; font-size: 12px; padding: 2px 0; border-bottom: 1px dashed #2a2e34; }
.t-time { color: #6c757d; min-width: 60px; }
.t-name { flex: 1; color: #e0e0e0; }
.t-status { color: #06d6a0; }
.rescue { margin-top: 8px; font-size: 12px; }
.rescue-tag { background: #d62828; color: #fff; padding: 1px 6px; border-radius: 3px; margin-right: 6px; }

.rec-item { padding: 10px 12px; background: #0f1115; border-left: 3px solid #6c757d; margin-bottom: 8px; border-radius: 3px; }
.rec-header { display: flex; gap: 8px; align-items: center; font-size: 13px; }
.evidence { padding: 2px 6px; border-radius: 3px; font-weight: 700; font-size: 12px; }
.ev-A { background: #06d6a0; color: #14171c; }
.ev-B { background: #4cc9f0; color: #14171c; }
.ev-C { background: #f9c74f; color: #14171c; }
.based-on { color: #6c757d; font-size: 11px; }
.ev-link { color: #4cc9f0; font-size: 12px; }
.rec-summary { color: #adb5bd; font-size: 12px; margin: 6px 0; }
.rec-steps { display: flex; flex-wrap: wrap; gap: 6px; }
.step { background: #14171c; padding: 4px 8px; border-radius: 3px; font-size: 12px; }
.step code { color: #06d6a0; }
.freq { color: #adb5bd; font-size: 11px; margin-left: 4px; }
.support { font-size: 11px; color: #6c757d; margin-top: 6px; }
</style>
