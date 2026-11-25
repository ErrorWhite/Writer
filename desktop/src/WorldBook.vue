<script setup>
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { downloadFile } from './manuscript.js'
import { importWorldBook, worldEntry, worldKeys, worldMatches } from './world-book.js'

const props = defineProps({ book: { type: Object, required: true }, chapter: { type: Object, required: true } })
const world = computed(() => props.book.creative.worldBook)
const query = ref('')
const filter = ref('')
const draft = ref(null)
const picker = ref()
const scan = computed(() => worldMatches(world.value, query.value || props.chapter.text.slice(-3500), props.book.context.budget))
const included = computed(() => new Set(scan.value.chosen.map(item => item.id)))
const visible = computed(() => world.value.entries.filter(item => !filter.value ||
  [item.title, ...item.keys].some(text => text.toLowerCase().includes(filter.value.toLowerCase()))))

function edit(item) {
  const entry = item || worldEntry({ title: '新条目' })
  draft.value = { ...entry, keys: entry.keys.join('、'), secondary: entry.secondary.join('、') }
}
function save() {
  const entry = worldEntry({ ...draft.value, keys: worldKeys(draft.value.keys), secondary: worldKeys(draft.value.secondary) })
  if (!entry.content || (!entry.always && !entry.keys.length)) { ElMessage.warning('请填写内容，并指定触发词或常驻。'); return }
  const index = world.value.entries.findIndex(item => item.id === entry.id)
  if (index < 0) world.value.entries.push(entry)
  else world.value.entries.splice(index, 1, entry)
  draft.value = null
}
async function remove(entry) {
  try { await ElMessageBox.confirm(`删除「${entry.title}」？`, '删除世界书条目') } catch { return }
  world.value.entries = world.value.entries.filter(item => item.id !== entry.id)
}
async function upload(event) {
  const [file] = event.target.files
  event.target.value = ''
  if (!file) return
  const target = world.value
  try {
    if (file.size > 5 * 1024 * 1024) throw new Error('世界书文件不能超过 5 MB。')
    const imported = importWorldBook(JSON.parse(await file.text()))
    if (target !== world.value) return
    const seen = new Set(target.entries.map(item => item.title + '\n' + item.content))
    const unique = imported.filter(item => {
      const key = item.title + '\n' + item.content
      if (seen.has(key)) return false
      seen.add(key)
      return true
    }).slice(0, 800 - target.entries.length)
    target.entries.push(...unique)
    ElMessage.success(`导入 ${unique.length} 条世界书条目`)
  } catch (error) { ElMessage.error(error.message) }
}
function exportWorld() {
  downloadFile(`${props.book.title} · 世界书`, new Blob([JSON.stringify(world.value, null, 2)], { type: 'application/json' }), 'json')
}
</script>

<template>
  <section class="world-book">
    <div class="resource-heading"><h2>世界书</h2><div class="plot-actions">
      <input ref="picker" type="file" hidden accept=".json,application/json" @change="upload" />
      <el-button @click="picker.click()">导入酒馆世界书</el-button><el-button @click="exportWorld">导出世界书</el-button><el-button type="primary" @click="edit()">新建条目</el-button>
    </div></div>
    <el-input v-model="filter" class="world-search" placeholder="搜索标题或触发词" aria-label="搜索世界书" clearable />
    <details class="world-testing">
      <summary>触发预览与预算</summary>
      <div class="world-test-fields">
        <label class="field-stack">测试段落<el-input v-model="query" placeholder="留空使用本章末尾" maxlength="3500" aria-label="测试段落" /></label>
        <label class="field-stack">注入字符预算<el-input-number v-model="world.budget" aria-label="注入字符预算" :min="100" :max="16000" :step="200" :value-on-clear="1800" /></label>
        <label class="field-stack">关联触发层数<el-input-number v-model="world.recursion" aria-label="关联触发层数" :min="0" :max="3" :value-on-clear="0" /></label>
      </div>
      <p class="library-muted" role="status">命中 {{ scan.active.length }} 条 · 实际注入 {{ included.size }} 条 · {{ scan.used }} / {{ scan.budget }} 字符</p>
    </details>
    <form v-if="draft" class="library-editor creative-form" @submit.prevent="save">
      <div class="creative-grid">
        <label class="field-stack">标题<el-input v-model="draft.title" maxlength="80" /></label>
        <label class="field-stack">优先级<el-input-number v-model="draft.priority" :min="0" :max="1000" /></label>
        <label class="field-stack">触发词<el-input v-model="draft.keys" placeholder="多个词用顿号或逗号分隔" /></label>
        <label class="field-stack">次级词<el-input v-model="draft.secondary" /></label>
        <label class="field-stack">次级词条件<el-select v-model="draft.logic"><el-option label="命中任一" value="any" /><el-option label="全部命中" value="all" /><el-option label="全部不匹配" value="not-any" /><el-option label="不全匹配" value="not-all" /></el-select></label>
        <label class="field-stack">最早生效层数<el-input-number v-model="draft.recursionDelay" :min="0" :max="3" /></label>
      </div>
      <div class="creative-controls">
        <el-checkbox v-model="draft.always" label="常驻" />
        <el-checkbox v-model="draft.caseSensitive" label="区分大小写" />
        <el-checkbox v-model="draft.excludeRecursion" label="仅由正文触发" />
        <el-checkbox v-model="draft.preventRecursion" label="不触发其他条目" />
      </div>
      <label class="field-stack">注入内容<el-input v-model="draft.content" type="textarea" :rows="4" maxlength="9000" /></label>
      <div class="dialog-actions"><el-button @click="draft = null">取消</el-button><el-button native-type="submit" type="primary">保存条目</el-button></div>
    </form>
    <div class="library-record-grid">
      <article v-for="entry in visible" :key="entry.id" class="resource-record">
        <div class="resource-record-title"><strong>{{ entry.title }}</strong><span v-if="included.has(entry.id)" class="revision-label approved">{{ scan.discovered.get(entry.id) ? `第 ${scan.discovered.get(entry.id)} 层关联命中` : '已命中' }}</span></div>
        <p class="library-muted">{{ entry.always ? '常驻' : entry.keys.join(' · ') }}</p><p>{{ entry.content }}</p>
        <div class="resource-record-actions"><el-checkbox v-model="entry.enabled" label="启用" /><button class="text-action" @click="edit(entry)">编辑</button><button class="text-action danger" @click="remove(entry)">删除</button></div>
      </article>
    </div>
  </section>
</template>
