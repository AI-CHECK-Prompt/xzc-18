<template>
  <div>
    <div class="card">
      <div style="display:flex; align-items:center; gap:12px;">
        <h2 style="margin:0; flex:1;">多床位总览</h2>
        <span class="badge badge-INFO">提示 {{ stats.INFO }}</span>
        <span class="badge badge-WARN">警告 {{ stats.WARN }}</span>
        <span class="badge badge-CRITICAL">危急 {{ stats.CRITICAL }}</span>
        <button class="btn btn-secondary" @click="load">刷新</button>
      </div>
    </div>

    <div class="card">
      <div class="bed-grid">
        <router-link v-for="b in beds" :key="b.bedId"
                     :to="`/bed/${b.bedId}`"
                     style="text-decoration:none; color:inherit;">
          <div class="bed-card" :class="b.latestAlert ? b.latestAlert.level : ''">
            <div class="code">{{ b.code }}</div>
            <div class="sub">患者：{{ b.patientId || '-' }}</div>
            <div class="sub" v-if="b.latestAlert">
              <span class="badge" :class="'badge-' + b.latestAlert.level">{{ b.latestAlert.level }}</span>
              {{ b.latestAlert.channelCode }} {{ b.latestAlert.value }}
            </div>
            <div class="sub" v-else>生命体征平稳</div>
          </div>
        </router-link>
      </div>
    </div>

    <div class="card">
      <h3>实时告警流</h3>
      <table>
        <thead>
          <tr><th>时间</th><th>床位</th><th>患者</th><th>通道</th><th>级别</th><th>类型</th><th>数值</th><th>消息</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="a in alerts" :key="a.id">
            <td>{{ formatTime(a.time) }}</td>
            <td>{{ bedCode(a.bedId) }}</td>
            <td>{{ a.patientId || '-' }}</td>
            <td>{{ a.channelCode }}</td>
            <td><span class="badge" :class="'badge-' + a.level">{{ a.level }}</span></td>
            <td>{{ a.alertType }}</td>
            <td>{{ a.value }}</td>
            <td>{{ a.message }}</td>
            <td>
              <button v-if="a.status === 'OPEN'" class="btn" @click="ack(a.id)">确认</button>
              <span v-else class="badge" :class="'badge-' + a.status">{{ a.status }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import api from '../api'

const beds = ref<any[]>([])
const alerts = ref<any[]>([])
const stats = reactive({ INFO: 0, WARN: 0, CRITICAL: 0 })
let timer: number | null = null
let ws: WebSocket | null = null

function formatTime(t: string) { return t ? new Date(t).toLocaleTimeString('zh-CN') : '' }
function bedCode(id: number) { const b = beds.value.find(x => x.bedId === id); return b ? b.code : id }

async function load() {
  const [b, a] = await Promise.all([api.beds(), api.alerts()])
  beds.value = b.data
  alerts.value = a.data
  stats.INFO = a.data.filter((x: any) => x.level === 'INFO' && x.status === 'OPEN').length
  stats.WARN = a.data.filter((x: any) => x.level === 'WARN' && x.status === 'OPEN').length
  stats.CRITICAL = a.data.filter((x: any) => x.level === 'CRITICAL' && x.status === 'OPEN').length
}

async function ack(id: number) { await api.ackAlert(id); await load() }

onMounted(() => {
  load()
  timer = window.setInterval(load, 5000)
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  ws = new WebSocket(`${proto}://${location.host}/ws/alert`)
  ws.onmessage = (e) => {
    try {
      const a = JSON.parse(e.data)
      alerts.value.unshift(a)
      if (a.level === 'INFO') stats.INFO++
      if (a.level === 'WARN') stats.WARN++
      if (a.level === 'CRITICAL') stats.CRITICAL++
    } catch {}
  }
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
  if (ws) ws.close()
})
</script>
