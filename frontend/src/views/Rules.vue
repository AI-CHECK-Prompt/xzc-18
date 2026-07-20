<template>
  <div>
    <div class="card">
      <h2>评分规则（基于 Drools，支持医院自定义动态加载）</h2>
      <table>
        <thead><tr><th>ID</th><th>编码</th><th>名称</th><th>版本</th><th>启用</th></tr></thead>
        <tbody>
          <tr v-for="r in rules" :key="r.id">
            <td>{{ r.id }}</td><td>{{ r.code }}</td><td>{{ r.name }}</td>
            <td>{{ r.version }}</td><td>{{ r.enabled }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="card">
      <h3>新增/更新自定义规则</h3>
      <div style="display:flex; gap:8px; margin-bottom:8px;">
        <input class="input" placeholder="编码" v-model="form.code" />
        <input class="input" placeholder="名称" v-model="form.name" />
        <input class="input" placeholder="医院 ID" v-model.number="form.hospitalId" type="number" />
        <input class="input" placeholder="版本" v-model.number="form.version" type="number" />
      </div>
      <textarea class="input" rows="10" style="width:100%; font-family:monospace;"
                v-model="form.drlContent"
                placeholder="package custom;&#10;import com.icu.monitor.scoring.ScoreContext;&#10;..."></textarea>
      <div style="margin-top:8px;">
        <button class="btn" @click="save">保存并热加载</button>
      </div>
    </div>

    <div class="card">
      <h3>评分结果查询</h3>
      <div style="display:flex; gap:8px;">
        <input class="input" type="number" placeholder="患者 ID" v-model.number="patientId" />
        <select class="input" v-model="ruleCode">
          <option>MEWS</option><option>SOFA</option>
        </select>
        <button class="btn" @click="query">查询</button>
      </div>
      <table v-if="results.length" style="margin-top:8px;">
        <thead><tr><th>时间</th><th>分数</th><th>级别</th><th>详情</th></tr></thead>
        <tbody>
          <tr v-for="(r, i) in results" :key="i">
            <td>{{ new Date(r.time).toLocaleString('zh-CN') }}</td>
            <td>{{ r.score }}</td>
            <td><span class="badge" :class="'badge-' + r.level">{{ r.level }}</span></td>
            <td><code style="font-size:11px;">{{ r.detail }}</code></td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import api from '../api'

const rules = ref<any[]>([])
const results = ref<any[]>([])
const patientId = ref<number>(1)
const ruleCode = ref('MEWS')

const form = reactive({
  code: 'CUSTOM_LVH', name: '自定义-左心衰评分', hospitalId: 1, version: 1, enabled: true,
  drlContent: `package custom;
import com.icu.monitor.scoring.ScoreContext
import com.icu.monitor.scoring.ScoreResult

rule "LHF 自定义评分"
    when
        $ctx: ScoreContext(ruleCode == "CUSTOM_LVH")
    then
        double s = 0;
        if ($ctx.getHr() != null && $ctx.getHr() > 100) s += 2;
        if ($ctx.getSbp() != null && $ctx.getSbp() < 90) s += 2;
        if ($ctx.getRr() != null && $ctx.getRr() > 24) s += 1;
        if ($ctx.getTemp() != null && $ctx.getTemp() > 38.5) s += 1;
        String level = s >= 4 ? "CRITICAL" : (s >= 2 ? "WARN" : "NORMAL");
        insert(new ScoreResult(s, level) {{ setRuleCode("CUSTOM_LVH"); }});
end`
})

async function load() { rules.value = (await api.listRules()).data }
async function save() {
  await api.saveRule(form)
  await load()
}
async function query() { results.value = (await api.scoreResult(patientId.value, ruleCode.value)).data }

onMounted(load)
</script>
