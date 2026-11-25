<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { diffChars, diffLines } from 'diff'
const props = defineProps({ review: { type: Object, required: true } })
const emit = defineEmits(['close', 'apply', 'reject'])
const layout = ref('side')
const selected = ref(0)
const current = computed(() => props.review.items[selected.value])
const picked = computed(() => props.review.items.filter(item => item.checked))
watch(() => props.review.items.length, length => { selected.value = Math.min(selected.value, Math.max(0, length - 1)) })
const diffs = computed(() => {
  if (!current.value) return { left: [], right: [] }
  const { before, after } = current.value
  const compare = before.length + after.length < 13000 ? diffChars : diffLines
  const result = compare(before, after, { timeout: 600, maxEditLength: 60000 })
  return result ? { left: result.filter(part => !part.added), right: result.filter(part => !part.removed) }
    : { left: [{ value: before }], right: [{ value: after }] }
})
function accept(all = false) {
  const items = all ? props.review.items : picked.value
  if (items.length) emit('apply', items)
}
function shortcuts(event) {
  if ((event.ctrlKey || event.metaKey) && event.shiftKey && event.key === 'Enter') {
    event.preventDefault()
    event.stopImmediatePropagation()
    accept(true)
  }
}
onMounted(() => window.addEventListener('keydown', shortcuts, true))
onUnmounted(() => window.removeEventListener('keydown', shortcuts, true))
</script>

<template>
  <el-dialog :model-value="true" :title="review.title" fullscreen append-to-body class="fullscreen-review" @update:model-value="value => { if (!value) emit('close') }">
    <div class="review-tools">
      <el-radio-group v-model="layout" size="small" aria-label="对比布局"><el-radio-button value="side">左右</el-radio-button><el-radio-button value="stack">上下</el-radio-button></el-radio-group>
      <span>已选 {{ picked.length }} / {{ review.items.length }}</span>
      <div class="review-actions"><el-button @click="emit('close')">稍后处理</el-button><el-button v-if="review.kind === 'batch'" :disabled="!picked.length" @click="emit('reject', picked)">拒绝选中</el-button><el-button @click="accept(true)">全部接受 · Ctrl+Shift+Enter</el-button><el-button type="primary" :disabled="!picked.length" @click="accept()">接受选中</el-button></div>
    </div>
    <div v-if="review.kind === 'batch' || review.items.length > 1" class="review-list">
      <div v-for="(item, index) in review.items" :key="item.id" class="review-choice">
        <el-checkbox v-model="item.checked" :aria-label="`选择${item.label}`" />
        <button type="button" class="text-action" :aria-pressed="selected === index" @click="selected = index">{{ item.label }}</button>
      </div>
    </div>
    <div v-if="current" class="review-comparison" :class="layout">
      <section><h3>原文</h3><pre><span v-for="(part, i) in diffs.left" :key="i" :class="{ removed: part.removed }">{{ part.value }}</span><span v-if="!current.before" class="library-muted">暂无原文</span></pre></section>
      <section><h3>生成稿</h3><pre><span v-for="(part, i) in diffs.right" :key="i" :class="{ added: part.added }">{{ part.value }}</span></pre>
        <details><summary>手动调整候选</summary><el-input v-model="current.after" :aria-label="`修改${current.label}`" type="textarea" :rows="8" /></details>
      </section>
    </div>
  </el-dialog>
</template>
