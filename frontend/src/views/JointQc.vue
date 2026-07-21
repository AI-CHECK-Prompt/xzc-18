<template>
  <div class="page">
    <h2>联合质控</h2>

    <section class="card">
      <h3>① 操作</h3>
      <div class="row">
        <label>联盟 <select v-model="allianceId">
          <option v-for="a in alliances" :key="a.id" :value="a.id">{{ a.name }}</option>
        </select></label>
        <label>季度 <input v-model="quarter" placeholder="2026Q3" /></label>
        <button @click="loadBreakdown">加载 DRG 全维度对比</button>
        <button @click="loadCompareForE11A">对比 DRG=E11A 跨院区</button>
        <button @click="aggregateAll">触发聚合</button>
        <button @click="generateReport">生成季度报告</button>
      </div>
    </section>

    <section class="card" v-if="breakdown.length > 0">
      <h3>② 联盟 DRG 全维度对比（{{ quarter }}）</h3>
      <table>
        <thead>
          <tr>
            <th>DRG</th><th>总例数</th><th>死亡数</th>
            <th>联盟死亡率</th><th>平均住院日</th><th>感染率</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in breakdown" :key="d.drg_code">
            <td><b>{{ d.drg_code }}</b></td>
            <td>{{ d.total_cases }}</td>
            <td>{{ d.total_deaths }}</td>
            <td :class="mortalityClass(d.alliance_mortality)">{{ formatPct(d.alliance_mortality) }}</td>
            <td>{{ d.avg_los }}</td>
            <td>{{ formatPct(d.infection_rate) }}</td>
            <td><button class="btn-small" @click="loadCompare(d.drg_code)">查看院间对比</button></td>
          </tr>
        </tbody>
      </table>
    </section>

    <section class="card" v-if="compareResult">
      <h3>③ DRG {{ compareResult.drgCode }} - 跨院区对比（{{ compareResult.periodQuarter }}）</h3>
      <div class="kpi">
        <div class="kpi-box">
          <div class="kpi-label">联盟平均死亡率</div>
          <div class="kpi-val">{{ formatPct(compareResult.allianceAvgMortality) }}</div>
        </div>
        <div class="kpi-box" v-if="compareResult.outlierHospitalId">
          <div class="kpi-label">异常院区</div>
          <div class="kpi-val" style="color: #d62828;">院区 #{{ compareResult.outlierHospitalId }}</div>
          <div class="kpi-sub">高于联盟均值 {{ formatPct(compareResult.outlierDelta) }}</div>
        </div>
      </div>
      <table>
        <thead>
          <tr>
            <th>院区</th><th>编码</th><th>病例数</th><th>死亡数</th>
            <th>死亡率</th><th>平均住院日</th><th>感染率</th><th>vs 联盟均值</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="h in compareResult.hospitals" :key="h.hospital_id"
              :class="h.hospital_id === compareResult.outlierHospitalId ? 'row-outlier' : ''">
            <td>{{ h.hospital_name }}</td>
            <td>{{ h.hospital_code }}</td>
            <td>{{ h.case_count }}</td>
            <td>{{ h.death_count }}</td>
            <td :class="mortalityClass(h.mortality_rate)">{{ formatPct(h.mortality_rate) }}</td>
            <td>{{ h.avg_los_days }}</td>
            <td>{{ formatPct(h.infection_rate) }}</td>
            <td :class="deltaClass(h.mortality_rate, compareResult.allianceAvgMortality)">
              {{ deltaStr(h.mortality_rate, compareResult.allianceAvgMortality) }}
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <section class="card" v-if="sofaCurveData.length > 0">
      <h3>④ SOFA 评分变化曲线（Day 0 ~ 7）</h3>
      <div v-for="sc in sofaCurveData" :key="sc.hospitalId" class="curve-row">
        <div class="curve-label">院区 #{{ sc.hospitalId }}</div>
        <div class="curve-bars">
          <div v-for="(pt, i) in sc.sofaCurve" :key="i" class="curve-bar-wrap">
            <div class="curve-bar" :style="{ height: (pt.avg * 8) + 'px' }" :title="`D${pt.day}: ${pt.avg}`"></div>
            <div class="curve-day">D{{ pt.day }}</div>
            <div class="curve-val">{{ pt.avg }}</div>
          </div>
        </div>
      </div>
    </section>

    <section class="card" v-if="reports.length > 0">
      <h3>⑤ 季度联合报告</h3>
      <div v-for="r in reports" :key="r.id" class="report-item">
        <div class="report-header">
          <b>{{ r.title }}</b>
          <span class="report-time">{{ formatTime(r.generatedAt) }}</span>
        </div>
        <p>{{ r.summary }}</p>
        <button class="btn-small" @click="viewReport(r.id)">查看完整报告</button>
      </div>
    </section>

    <section class="card" v-if="reportDetail">
      <h3>报告详情 #{{ reportDetail.id }}</h3>
      <pre style="max-height: 500px; overflow: auto; background: #1a1d23; color: #4cc9f0; padding: 12px; border-radius: 4px; font-size: 12px;">{{ JSON.stringify(reportDetail, null, 2) }}</pre>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import api from '../api'

const alliances = ref<any[]>([])
const allianceId = ref<number>(1)
const quarter = ref('')
const breakdown = ref<any[]>([])
const compareResult = ref<any>(null)
const sofaCurveData = ref<any[]>([])
const reports = ref<any[]>([])
const reportDetail = ref<any>(null)

onMounted(async () => {
  const r = await api.alliance.list()
  alliances.value = r.data
  if (r.data.length > 0) allianceId.value = r.data[0].id
  // 计算当前季度
  const d = new Date()
  const q = Math.floor(d.getMonth() / 3) + 1
  quarter.value = `${d.getFullYear()}Q${q}`
  await Promise.all([loadBreakdown(), loadReports()])
})

async function loadBreakdown() {
  const r = await api.alliance.qcBreakdown({ allianceId: allianceId.value, quarter: quarter.value })
  breakdown.value = r.data
}

async function loadCompare(drg: string) {
  const r = await api.alliance.qcCompare({ allianceId: allianceId.value, drgCode: drg, quarter: quarter.value })
  compareResult.value = r.data
  // 加载 SOFA 曲线
  const c = await api.alliance.sofaCurve({ allianceId: allianceId.value, drgCode: drg, quarter: quarter.value })
  sofaCurveData.value = c.data
}

async function loadCompareForE11A() { loadCompare('E11A') }

async function aggregateAll() {
  for (const d of breakdown.value) {
    await api.alliance.qcAggregate({ allianceId: allianceId.value, drgCode: d.drg_code, quarter: quarter.value })
  }
  await loadBreakdown()
  if (compareResult.value) await loadCompare(compareResult.value.drgCode)
}

async function generateReport() {
  await api.alliance.qcReport({ allianceId: allianceId.value, quarter: quarter.value })
  await loadReports()
}

async function loadReports() {
  const r = await api.alliance.reportList(allianceId.value)
  reports.value = r.data
}

async function viewReport(id: number) {
  const r = await api.alliance.report(id)
  reportDetail.value = r.data
}

function formatPct(v: any) {
  if (v == null) return '-'
  return (Number(v) * 100).toFixed(2) + '%'
}
function formatTime(s: string) { return new Date(s).toLocaleString('zh-CN') }
function mortalityClass(v: any) {
  v = Number(v)
  if (v >= 0.3) return 'crit'
  if (v >= 0.2) return 'warn'
  return 'normal'
}
function deltaClass(v: number, avg: number) {
  if (v - avg > 0.02) return 'crit'
  if (v - avg < -0.02) return 'good'
  return ''
}
function deltaStr(v: number, avg: number) {
  const d = (v - avg) * 100
  return (d >= 0 ? '+' : '') + d.toFixed(2) + 'pp'
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
button:hover { background: #5dd5fb; }
.btn-small { padding: 2px 8px; font-size: 11px; }

table { width: 100%; border-collapse: collapse; font-size: 13px; }
th, td { padding: 6px 8px; text-align: left; border-bottom: 1px solid #2a2e34; }
th { color: #6c757d; }
.crit { color: #d62828; font-weight: 600; }
.warn { color: #f9c74f; }
.normal { color: #06d6a0; }
.good { color: #06d6a0; }
.row-outlier { background: rgba(214, 40, 40, 0.1); }

.kpi { display: flex; gap: 16px; margin-bottom: 16px; }
.kpi-box { background: #0f1115; padding: 12px 20px; border-radius: 4px; min-width: 160px; }
.kpi-label { color: #6c757d; font-size: 12px; }
.kpi-val { font-size: 24px; font-weight: 700; margin: 4px 0; }
.kpi-sub { color: #6c757d; font-size: 11px; }

.curve-row { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.curve-label { min-width: 100px; color: #adb5bd; }
.curve-bars { display: flex; gap: 4px; align-items: flex-end; height: 100px; }
.curve-bar-wrap { display: flex; flex-direction: column; align-items: center; gap: 2px; }
.curve-bar { width: 24px; background: linear-gradient(180deg, #4cc9f0, #06d6a0); border-radius: 2px 2px 0 0; }
.curve-day { font-size: 10px; color: #6c757d; }
.curve-val { font-size: 10px; color: #adb5bd; }

.report-item { padding: 8px 12px; background: #0f1115; border-radius: 3px; margin-bottom: 6px; }
.report-header { display: flex; justify-content: space-between; }
.report-time { color: #6c757d; font-size: 11px; }
</style>
