<template>
  <div>
    <div class="card">
      <div style="display:flex; gap:12px; align-items:center;">
        <h2 style="margin:0; flex:1;">抢救事件回放</h2>
        <select class="input" v-model="bedId">
          <option v-for="b in beds" :key="b.bedId" :value="b.bedId">{{ b.code }}</option>
        </select>
        <button class="btn" @click="loadSessions">查询</button>
        <button class="btn btn-secondary" @click="manual">手工建立</button>
      </div>
    </div>

    <div class="card" v-if="sessions.length">
      <h3>回放会话</h3>
      <table>
        <thead><tr><th>会话 ID</th><th>床位</th><th>触发告警</th><th>起始</th><th>结束</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="s in sessions" :key="s.id">
            <td>{{ s.id }}</td>
            <td>{{ bedCodeOf(s.bedId) }}</td>
            <td>{{ s.triggerAlertId || '手工' }}</td>
            <td>{{ new Date(s.startAt).toLocaleString('zh-CN') }}</td>
            <td>{{ new Date(s.endAt).toLocaleString('zh-CN') }}</td>
            <td>{{ s.status }}</td>
            <td><button class="btn" @click="loadItems(s.id)">展开时间轴</button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="card" v-if="items.length">
      <h3>时间轴（触发前后 30 分钟）</h3>
      <div class="timeline">
        <div v-for="i in items" :key="i.id" class="timeline-item" :class="i.sourceType">
          <div class="ts">{{ new Date(i.time).toLocaleString('zh-CN') }} · {{ i.sourceType }}</div>
          <div>{{ i.payload }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import api from '../api'

const beds = ref<any[]>([])
const bedId = ref<number>(1)
const sessions = ref<any[]>([])
const items = ref<any[]>([])

function bedCodeOf(id: number) { const b = beds.value.find(x => x.bedId === id); return b ? b.code : id }

async function loadBeds() { beds.value = (await api.beds()).data; if (beds.value.length) bedId.value = beds.value[0].bedId }
async function loadSessions() { sessions.value = (await api.playbackByBed(bedId.value)).data; items.value = [] }
async function loadItems(id: number) { items.value = (await api.playbackItems(id)).data }
async function manual() {
  await api.playbackManual(bedId.value)
  await loadSessions()
}

onMounted(loadBeds)
</script>
