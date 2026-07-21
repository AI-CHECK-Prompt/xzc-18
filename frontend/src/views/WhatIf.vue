<template>
  <div class="page">
    <h2>事后回放 - "如果当时采用推荐方案"</h2>

    <section class="card">
      <h3>① 选择历史病例 + 推荐方案</h3>
      <div class="row">
        <label>联盟 <select v-model="allianceId">
          <option v-for="a in alliances" :key="a.id" :value="a.id">{{ a.name }}</option>
        </select></label>
        <label>历史病例 ID <input v-model.number="sharedCaseId" type="number" /></label>
        <label>推荐方案 ID <input v-model.number="planTemplateId" type="number" /></label>
        <button @click="loadPool">加载共享池</button>
        <button @click="loadTemplates">加载方案模板</button>
        <button @click="simulate" :disabled="loading || !sharedCaseId || !planTemplateId">推演 WhatIf</button>
      </div>
    </section>

    <section class="card" v-if="pool.length > 0">
      <h3>② 共享池（选一个作为推演对象）</h3>
      <table>
        <thead>
          <tr><th>ID</th><th>院区</th><th>DRG</th><th>SOFA</th><th>转归</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="p in pool.slice(0, 30)" :key="p.id">
            <td>{{ p.id }}</td>
            <td>{{ p.sourceHospital }}</td>
            <td><b>{{ p.drgCode }}</b></td>
            <td>{{ p.sofaAdmission }}</td>
            <td :class="outcomeClass(p.outcome)">{{ p.outcome }}</td>
            <td><button class="btn-small" @click="sharedCaseId = p.id">选择</button></td>
          </tr>
        </tbody>
      </table>
    </section>

    <section class="card" v-if="templates.length > 0">
      <h3>③ 方案模板（按证据等级排序）</h3>
      <table>
        <thead>
          <tr><th>ID</th><th>DRG</th><th>标题</th><th>证据</th><th>基于</th><th>支撑数</th><th>成功率</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="t in templates" :key="t.id">
            <td>{{ t.id }}</td>
            <td><b>{{ t.drgCode }}</b></td>
            <td>{{ t.title }}</td>
            <td><span :class="['ev-tag', 'ev-' + t.evidenceLevel]">{{ t.evidenceLevel }}</span></td>
            <td>{{ t.basedOn }}</td>
            <td>{{ t.supportCount }}</td>
            <td>{{ t.successRate ? (t.successRate * 100).toFixed(1) + '%' : '-' }}</td>
            <td><button class="btn-small" @click="planTemplateId = t.id">选择</button></td>
          </tr>
        </tbody>
      </table>
    </section>

    <section class="card" v-if="whatIfResult">
      <h3>④ 推演结果</h3>
      <div class="kpi">
        <div class="kpi-box">
          <div class="kpi-label">实际转归</div>
          <div :class="['kpi-val', outcomeClass(whatIfResult.actualOutcome)]">{{ whatIfResult.actualOutcome }}</div>
          <div class="kpi-sub">死亡率 {{ (whatIfResult.actualMortality * 100).toFixed(1) }}%</div>
        </div>
        <div class="kpi-box">
          <div class="kpi-label">推演转归</div>
          <div :class="['kpi-val', outcomeClass(whatIfResult.predictedOutcome)]">{{ whatIfResult.predictedOutcome }}</div>
          <div class="kpi-sub">死亡率 {{ (whatIfResult.predictedMortality * 100).toFixed(1) }}%</div>
        </div>
        <div class="kpi-box">
          <div class="kpi-label">死亡率变化</div>
          <div :class="['kpi-val', whatIfResult.mortalityDelta < 0 ? 'good' : 'crit']">
            {{ whatIfResult.mortalityDelta >= 0 ? '+' : '' }}{{ (whatIfResult.mortalityDelta * 100).toFixed(1) }}%
          </div>
          <div class="kpi-sub">{{ whatIfResult.mortalityDelta < 0 ? '推荐方案可降低' : '推荐方案未改善' }}</div>
        </div>
      </div>
    </section>

    <section class="card" v-if="whatIfResult">
      <h3>⑤ 时间轴对比（实际 + 推荐叠加）</h3>
      <div class="timeline">
        <div v-for="(step, i) in whatIfResult.timeline" :key="i" class="timeline-item">
          <span class="t-time">{{ step.time }}</span>
          <span class="t-source" :class="step.source === 'RECOMMENDED' ? 't-rec' : 't-act'">
            {{ step.source === 'RECOMMENDED' ? '推荐' : '实际' }}
          </span>
          <span class="t-action">{{ step.action || step.itemName || step.type }}</span>
          <span v-if="step.evidenceLevel" class="t-ev">{{ step.evidenceLevel }}</span>
        </div>
      </div>
    </section>

    <section class="card" v-if="whatIfResult">
      <h3>⑥ 证据链</h3>
      <div v-for="(e, i) in whatIfResult.evidenceChain" :key="i" class="evidence-chain">
        <span :class="['ev-type', 'ev-type-' + e.type]">{{ e.type }}</span>
        <span v-if="e.outcome">转归：<b>{{ e.outcome }}</b></span>
        <span v-if="e.mortality !== undefined">死亡率 {{ (e.mortality * 100).toFixed(1) }}%</span>
        <span v-if="e.evidenceLevel">证据等级 <b>{{ e.evidenceLevel }}</b></span>
        <span v-if="e.basedOn">基于 {{ e.basedOn }}</span>
        <a v-if="e.url" :href="e.url" target="_blank">↗ 来源</a>
        <span v-if="e.riskReduction">风险降低 {{ (e.riskReduction * 100).toFixed(1) }}%</span>
      </div>
    </section>

    <section class="card" v-if="history.length > 0">
      <h3>⑦ 历史推演记录</h3>
      <table>
        <thead>
          <tr><th>Session</th><th>病例</th><th>方案</th><th>实际</th><th>推演</th><th>Δ 死亡率</th><th>时间</th></tr>
        </thead>
        <tbody>
          <tr v-for="h in history" :key="h.id">
            <td>{{ h.id }}</td>
            <td>{{ h.sharedCaseId }}</td>
            <td>{{ h.planTemplateId }}</td>
            <td :class="outcomeClass(h.actualOutcome)">{{ h.actualOutcome }}</td>
            <td :class="outcomeClass(h.predictedOutcome)">{{ h.predictedOutcome }}</td>
            <td :class="h.mortalityDelta < 0 ? 'good' : 'crit'">
              {{ h.mortalityDelta >= 0 ? '+' : '' }}{{ (h.mortalityDelta * 100).toFixed(1) }}%
            </td>
            <td>{{ formatTime(h.createdAt) }}</td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import api from '../api'

const alliances = ref<any[]>([])
const allianceId = ref<number>(1)
const sharedCaseId = ref<number | null>(null)
const planTemplateId = ref<number | null>(null)
const pool = ref<any[]>([])
const templates = ref<any[]>([])
const whatIfResult = ref<any>(null)
const history = ref<any[]>([])
const loading = ref(false)

onMounted(async () => {
  const r = await api.alliance.list()
  alliances.value = r.data
  if (r.data.length > 0) allianceId.value = r.data[0].id
  await Promise.all([loadPool(), loadTemplates(), loadHistory()])
})

async function loadPool() {
  const r = await api.alliance.pool({ allianceId: allianceId.value, limit: 100 })
  pool.value = r.data
}

async function loadTemplates() {
  const r = await api.alliance.planTemplate(allianceId.value)
  templates.value = r.data
}

async function loadHistory() {
  const r = await api.alliance.whatIfList(allianceId.value)
  history.value = r.data
}

async function simulate() {
  loading.value = true
  try {
    const r = await api.alliance.whatIf({
      allianceId: allianceId.value,
      sharedCaseId: sharedCaseId.value!,
      planTemplateId: planTemplateId.value!
    })
    whatIfResult.value = r.data
    await loadHistory()
  } finally { loading.value = false }
}

function formatTime(s: string) { return new Date(s).toLocaleString('zh-CN') }
function outcomeClass(o: string) {
  if (o === 'DECEASED') return 'crit'
  if (o === 'TRANSFERRED') return 'warn'
  return 'good'
}
</script>

<style scoped>
.page { padding: 24px; }
.card { background: #14171c; padding: 16px; border-radius: 6px; margin-bottom: 16px; }
.card h3 { margin-top: 0; }
.row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.row label { display: flex; gap: 4px; align-items: center; font-size: 13px; color: #adb5bd; }
input, select { padding: 4px 8px; background: #0f1115; color: #e0e0e0; border: 1px solid #2a2e34; border-radius: 4px; }
button { padding: 6px 12px; background: #4cc9f0; color: #14171c; border: none; border-radius: 4px; cursor: pointer; font-weight: 500; }
button:disabled { background: #2a2e34; color: #6c757d; cursor: not-allowed; }
.btn-small { padding: 2px 8px; font-size: 11px; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th, td { padding: 6px 8px; text-align: left; border-bottom: 1px solid #2a2e34; }
th { color: #6c757d; }
.crit { color: #d62828; font-weight: 600; }
.warn { color: #f9c74f; }
.good { color: #06d6a0; }
.ev-tag { padding: 1px 5px; border-radius: 2px; font-weight: 700; font-size: 11px; }
.ev-A { background: #06d6a0; color: #14171c; }
.ev-B { background: #4cc9f0; color: #14171c; }
.ev-C { background: #f9c74f; color: #14171c; }
.kpi { display: flex; gap: 16px; margin-bottom: 12px; }
.kpi-box { background: #0f1115; padding: 12px 20px; border-radius: 4px; min-width: 160px; }
.kpi-label { color: #6c757d; font-size: 12px; }
.kpi-val { font-size: 24px; font-weight: 700; margin: 4px 0; }
.kpi-sub { color: #6c757d; font-size: 11px; }

.timeline { max-height: 400px; overflow-y: auto; }
.timeline-item { display: flex; gap: 8px; align-items: center; padding: 4px 8px; border-bottom: 1px dashed #2a2e34; font-size: 12px; }
.t-time { color: #6c757d; min-width: 60px; }
.t-source { padding: 1px 5px; border-radius: 2px; font-size: 10px; font-weight: 700; }
.t-act { background: #4cc9f0; color: #14171c; }
.t-rec { background: #06d6a0; color: #14171c; }
.t-action { flex: 1; color: #e0e0e0; }
.t-ev { color: #f9c74f; }

.evidence-chain { display: flex; gap: 8px; align-items: center; padding: 4px 8px; border-bottom: 1px dashed #2a2e34; font-size: 12px; }
.ev-type { padding: 2px 6px; border-radius: 2px; font-weight: 700; font-size: 11px; }
.ev-type-ACTUAL { background: #4cc9f0; color: #14171c; }
.ev-type-RECOMMENDED { background: #06d6a0; color: #14171c; }
.ev-type-PREDICTED { background: #f9c74f; color: #14171c; }
</style>
