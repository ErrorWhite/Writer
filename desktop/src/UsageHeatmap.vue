<script setup>
import { computed, onMounted, ref } from 'vue'
import { localRequest } from './local-service.js'
import { aiIntentLabels } from './ai-activity.js'
const records = ref({})
const error = ref('')
const chosen = ref(null)
const localDate = date => `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}`
const days = computed(() => {
  const today = new Date()
  const base = new Date(today)
  base.setDate(today.getDate() - today.getDay() - 25 * 7)
  return Array.from({ length: 25 * 7 + today.getDay() + 1 }, (_, index) => {
    const day = new Date(base)
    day.setDate(day.getDate() + index)
    const date = localDate(day)
    const record = records.value[date]
    const requests = record?.requests || 0
    return { date, ...record, requests, level: requests ? Math.min(4, Math.ceil(Math.log2(requests + 1))) : 0 }
  })
})
const totals = computed(() => Object.values(records.value).reduce((sum, item) => ({
  requests: sum.requests + item.requests, successful: sum.successful + item.successful,
  tokens: sum.tokens + item.input + item.output,
}), { requests: 0, successful: 0, tokens: 0 }))
const selected = computed(() => chosen.value || days.value.at(-1))
onMounted(async () => {
  try { records.value = await localRequest('/api/ai/usage') }
  catch (reason) { error.value = reason.message }
})
</script>
<template>
  <section class="usage-workbench">
    <div class="resource-heading"><h2>AI 使用量</h2></div>
    <dl class="library-stats"><div><dt>请求次数</dt><dd>{{ totals.requests }}</dd></div><div><dt>成功生成</dt><dd>{{ totals.successful }}</dd></div><div><dt>已上报 Token</dt><dd>{{ totals.tokens.toLocaleString('zh-CN') }}</dd></div></dl>
    <div class="usage-scroll"><div class="usage-heatmap" aria-label="近 26 周使用热力图">
      <button v-for="day in days" :key="day.date" :data-level="day.level" :aria-label="`${day.date} · ${day.requests} 次`" :aria-pressed="selected?.date === day.date"
        :title="`${day.date} · ${day.requests} 次`" @click="chosen = day" />
    </div></div>
    <div v-if="selected" class="resource-record usage-day"><strong>{{ selected.date }} · {{ selected.requests }} 次</strong><p v-for="(count, intent) in selected.tasks" :key="intent">{{ aiIntentLabels[intent] || intent }} · {{ count }} 次</p></div>
    <p v-if="error" class="field-error" role="alert">{{ error }}</p>
  </section>
</template>
