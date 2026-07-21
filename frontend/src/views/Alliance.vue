<template>
  <div class="page">
    <h2>多院区联盟</h2>
    <div class="row" style="margin-bottom: 16px;">
      <select v-model="selectedAllianceId" @change="loadMembers">
        <option v-for="a in alliances" :key="a.id" :value="a.id">{{ a.name }} ({{ a.code }})</option>
      </select>
      <button @click="loadAlliances">刷新联盟</button>
      <button @click="loadMembers">刷新成员</button>
    </div>

    <section class="card">
      <h3>联盟基本信息</h3>
      <table v-if="currentAlliance">
        <tr><th>名称</th><td>{{ currentAlliance.name }}</td></tr>
        <tr><th>编码</th><td>{{ currentAlliance.code }}</td></tr>
        <tr><th>描述</th><td>{{ currentAlliance.description }}</td></tr>
        <tr><th>脱敏启用</th><td>{{ currentAlliance.deidEnabled ? '是' : '否' }}</td></tr>
        <tr><th>共享池容量</th><td>{{ poolCount }} 例</td></tr>
      </table>
    </section>

    <section class="card">
      <h3>成员院区（{{ members.length }}）</h3>
      <table>
        <thead>
          <tr><th>院区编码</th><th>院区名</th><th>角色</th><th>加入时间</th></tr>
        </thead>
        <tbody>
          <tr v-for="m in members" :key="m.memberId">
            <td>{{ m.code }}</td>
            <td>{{ m.name }}</td>
            <td><span :class="m.role === 'LEADER' ? 'tag-leader' : 'tag-member'">{{ m.role }}</span></td>
            <td>{{ formatTime(m.joinedAt) }}</td>
          </tr>
        </tbody>
      </table>
    </section>

    <section class="card">
      <h3>共享池（按 DRG 分组）</h3>
      <div class="row" style="margin-bottom: 8px;">
        <input v-model="filterDrg" placeholder="DRG 编码过滤（如 E11A）" />
        <button @click="loadPool">查询</button>
      </div>
      <table>
        <thead>
          <tr><th>ID</th><th>来源院区</th><th>DRG</th><th>MDC</th><th>年龄段</th><th>SOFA</th><th>转归</th><th>住院日</th><th>感染</th><th>入院时间</th></tr>
        </thead>
        <tbody>
          <tr v-for="p in pool" :key="p.id">
            <td><a href="#" @click.prevent="viewPoolDetail(p.id)">{{ p.id }}</a></td>
            <td>{{ p.sourceHospital }}</td>
            <td><b>{{ p.drgCode }}</b></td>
            <td>{{ p.mdcCode }}</td>
            <td>{{ p.ageBand }}</td>
            <td>{{ p.sofaAdmission }}</td>
            <td><span :class="outcomeClass(p.outcome)">{{ p.outcome }}</span></td>
            <td>{{ p.losDays }}</td>
            <td>{{ p.infection ? '是' : '-' }}</td>
            <td>{{ formatTime(p.admissionAt) }}</td>
          </tr>
        </tbody>
      </table>
    </section>

    <section class="card" v-if="poolDetail">
      <h3>共享病例详情 #{{ poolDetail.id }}（已脱敏）</h3>
      <pre style="max-height: 400px; overflow: auto; background: #1a1d23; color: #4cc9f0; padding: 12px; border-radius: 4px; font-size: 12px;">{{ JSON.stringify(poolDetail, null, 2) }}</pre>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import api from '../api'

const alliances = ref<any[]>([])
const selectedAllianceId = ref<number | null>(null)
const members = ref<any[]>([])
const pool = ref<any[]>([])
const poolCount = ref(0)
const filterDrg = ref('')
const poolDetail = ref<any>(null)

const currentAlliance = computed(() => alliances.value.find(a => a.id === selectedAllianceId.value))

onMounted(loadAlliances)

async function loadAlliances() {
  const r = await api.alliance.list()
  alliances.value = r.data
  if (!selectedAllianceId.value && alliances.value.length > 0) {
    selectedAllianceId.value = alliances.value[0].id
  }
  if (selectedAllianceId.value) await Promise.all([loadMembers(), loadPool()])
}

async function loadMembers() {
  if (!selectedAllianceId.value) return
  const r = await api.alliance.members(selectedAllianceId.value)
  members.value = r.data
}

async function loadPool() {
  if (!selectedAllianceId.value) return
  const r = await api.alliance.pool({ allianceId: selectedAllianceId.value, drgCode: filterDrg.value || null })
  pool.value = r.data
  poolCount.value = r.data.length
}

async function viewPoolDetail(id: number) {
  const r = await api.alliance.poolDetail(id)
  poolDetail.value = r.data
}

function formatTime(s: string) {
  if (!s) return '-'
  return new Date(s).toLocaleString('zh-CN')
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
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th, td { padding: 6px 8px; text-align: left; border-bottom: 1px solid #2a2e34; }
th { color: #6c757d; }
.row { display: flex; gap: 8px; align-items: center; }
.row input, .row select { padding: 4px 8px; background: #0f1115; color: #e0e0e0; border: 1px solid #2a2e34; border-radius: 4px; }
button { padding: 6px 12px; background: #4cc9f0; color: #14171c; border: none; border-radius: 4px; cursor: pointer; font-weight: 500; }
button:hover { background: #5dd5fb; }
.tag-leader { background: #d62828; color: #fff; padding: 2px 6px; border-radius: 3px; }
.tag-member { background: #6c757d; color: #fff; padding: 2px 6px; border-radius: 3px; }
.tag-deceased { color: #d62828; font-weight: 600; }
.tag-transferred { color: #f9c74f; }
.tag-survived { color: #06d6a0; }
</style>
