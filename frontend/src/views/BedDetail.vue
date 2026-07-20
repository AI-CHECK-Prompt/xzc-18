<template>
  <div>
    <div class="card">
      <div style="display:flex; gap:12px; align-items:center;">
        <router-link to="/" class="btn btn-secondary">← 返回</router-link>
        <h2 style="margin:0;">床位 {{ bedCode }} · 实时监护</h2>
        <span v-if="bed?.patient" style="color:#adb5bd;">患者：{{ bed.patient.nameMask }} (MRN {{ bed.patient.mrn }})</span>
      </div>
    </div>

    <div class="card">
      <h3>实时衍生指标（1min）</h3>
      <div ref="metricChart" style="height:260px;"></div>
    </div>

    <div class="card">
      <h3>原始波形（近 5 分钟抽样）</h3>
      <div ref="waveChart" style="height:240px;"></div>
    </div>

    <div class="card">
      <h3>床位告警历史</h3>
      <table>
        <thead><tr><th>时间</th><th>级别</th><th>类型</th><th>通道</th><th>数值</th><th>消息</th></tr></thead>
        <tbody>
          <tr v-for="a in alerts" :key="a.id">
            <td>{{ a.time ? new Date(a.time).toLocaleString('zh-CN') : '' }}</td>
            <td><span class="badge" :class="'badge-' + a.level">{{ a.level }}</span></td>
            <td>{{ a.alertType }}</td>
            <td>{{ a.channelCode }}</td>
            <td>{{ a.value }}</td>
            <td>{{ a.message }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import * as echarts from 'echarts'
import api from '../api'

const route = useRoute()
const bedId = Number(route.params.id)
const bedCode = ref('')
const bed = ref<any>(null)
const alerts = ref<any[]>([])
const metricChart = ref<HTMLDivElement>()
const waveChart = ref<HTMLDivElement>()
let mChart: echarts.ECharts | null = null
let wChart: echarts.ECharts | null = null
let timer: number | null = null

async function loadBed() {
  const r = await api.bed(bedId)
  bed.value = r.data
  bedCode.value = r.data.bed.code
}

async function loadAlerts() {
  const r = await api.alerts(bedId)
  alerts.value = r.data
}

async function loadMetric() {
  const r = await api.metric(bedId, 30)
  const xs = r.data.map((x: any) => new Date(x.time).toLocaleTimeString('zh-CN'))
  const ys = r.data.map((x: any) => x.last_value ?? x.avg_value)
  if (mChart && metricChart.value) {
    mChart.setOption({
      backgroundColor: 'transparent',
      textStyle: { color: '#e0e1dd' },
      tooltip: { trigger: 'axis' },
      grid: { left: 40, right: 20, top: 20, bottom: 30 },
      xAxis: { type: 'category', data: xs, axisLabel: { color: '#adb5bd' } },
      yAxis: { type: 'value', axisLabel: { color: '#adb5bd' }, splitLine: { lineStyle: { color: '#415a77' } } },
      series: [{ type: 'line', data: ys, smooth: true, areaStyle: {}, lineStyle: { color: '#4cc9f0' }, itemStyle: { color: '#4cc9f0' } }]
    })
  }
}

async function loadWave() {
  const r = await api.waveform(bedId, 200)
  const xs = r.data.map((x: any) => new Date(x.time).toLocaleTimeString('zh-CN'))
  const ys = r.data.map((x: any) => x.value_num)
  if (wChart && waveChart.value) {
    wChart.setOption({
      backgroundColor: 'transparent',
      textStyle: { color: '#e0e1dd' },
      tooltip: { trigger: 'axis' },
      grid: { left: 40, right: 20, top: 20, bottom: 30 },
      xAxis: { type: 'category', data: xs, axisLabel: { color: '#adb5bd' } },
      yAxis: { type: 'value', axisLabel: { color: '#adb5bd' }, splitLine: { lineStyle: { color: '#415a77' } } },
      series: [{ type: 'line', data: ys, showSymbol: false, lineStyle: { color: '#e63946' }, itemStyle: { color: '#e63946' } }]
    })
  }
}

onMounted(async () => {
  await loadBed()
  await loadAlerts()
  if (metricChart.value) mChart = echarts.init(metricChart.value)
  if (waveChart.value)   wChart = echarts.init(waveChart.value)
  await loadMetric()
  await loadWave()
  timer = window.setInterval(() => { loadMetric(); loadWave(); loadAlerts() }, 3000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
  if (mChart) mChart.dispose()
  if (wChart) wChart.dispose()
})
</script>
