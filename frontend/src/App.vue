<template>
  <div>
    <header class="app-header">
      <h1>ICU 多床位监护与早期预警系统</h1>
      <nav>
        <router-link to="/">总览</router-link>
        <router-link to="/playback">事件回放</router-link>
        <router-link to="/rules">评分规则</router-link>
      </nav>
      <div style="margin-left:auto; font-size:12px;">
        推送在线：<b style="color:#4cc9f0">{{ onlineCount }}</b>
        <span style="margin-left:16px; color:#adb5bd;">{{ time }}</span>
      </div>
    </header>
    <main class="app-main">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import api from './api'

const onlineCount = ref(0)
const time = ref('')
let ws: WebSocket | null = null
let timer: number | null = null

onMounted(() => {
  refreshOnline()
  timer = window.setInterval(() => { time.value = new Date().toLocaleString('zh-CN') }, 1000)
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  ws = new WebSocket(`${proto}://${location.host}/ws/alert`)
  ws.onmessage = () => { refreshOnline() }
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
  if (ws) ws.close()
})

async function refreshOnline() {
  try { const r = await api.online(); onlineCount.value = r.data.sessions } catch {}
}
</script>
