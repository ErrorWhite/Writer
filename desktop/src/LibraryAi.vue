<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { Sparkles } from '@lucide/vue'
import { evidenceMaterial, libraryKinds } from './creative-tools.js'
import { ensureContextBudget, providerReady } from './context-options.js'
import { localRequest } from './local-service.js'
import { sameAnchor, syncParagraphs } from './revisions.js'

const props = defineProps({
  book: { type: Object, required: true }, chapters: { type: Array, required: true }, connection: { type: Object, required: true },
})
const emit = defineEmits(['connection'])
const sources = computed(() => props.chapters.filter(({ chapter }) => chapter.text.trim()))
const chapterId = ref(sources.value.find(({ chapter }) => chapter.id === props.book.activeId)?.chapter.id || sources.value[0]?.chapter.id || '')
const source = computed(() => sources.value.find(({ chapter }) => chapter.id === chapterId.value)?.chapter)
const start = ref(1)
const end = ref(1)
const kinds = ref(['character', 'rule', 'scene', 'foreshadow', 'event'])
const candidates = ref([])
const error = ref('')
const status = ref('')
const busy = ref(false)
let request
const normalize = value => typeof value === 'string' ? value.trim().normalize('NFC').toLowerCase() : ''

watch(chapterId, () => {
  request?.abort()
  candidates.value = []
  error.value = ''
  status.value = ''
  if (source.value) syncParagraphs(source.value)
  start.value = 1
  end.value = Math.min(18, source.value?.paragraphs.length || 1)
}, { immediate: true })
onUnmounted(() => request?.abort())

function records(book) {
  return [
    ...book.creative.cards,
    ...book.research.foreshadows.map(item => ({ kind: 'foreshadow', title: item.title })),
    ...book.research.events.map(item => ({ kind: 'event', title: item.title })),
  ].filter(item => typeof item.title === 'string' && item.title.trim())
}

function material() {
  const chapter = source.value
  if (!chapter) throw new Error('请选择含正文的章节。')
  const first = start.value || 1
  const last = end.value || chapter.paragraphs.length
  if (last < first) throw new Error('结束段落不能早于起始段落。')
  const parts = chapter.paragraphs.slice(first - 1, last).filter(item => item.text.trim())
  if (!parts.length) throw new Error('这一范围内没有正文。')
  return ensureContextBudget(props.book, {
    章节: chapter.title,
    资料类型: kinds.value,
    审阅原文: evidenceMaterial(chapter, parts),
    已有条目: records(props.book).filter(item => kinds.value.includes(item.kind) &&
      parts.some(({ text }) => text.includes(item.title))).slice(0, 18).map(({ kind, title }) => ({ kind, title })),
  })
}

async function extract() {
  if (busy.value || !source.value || !kinds.value.length) return
  if (!providerReady(props.connection, props.connection.provider || 'auto')) { emit('connection'); return }
  const controller = new AbortController()
  request = controller
  busy.value = true
  error.value = ''
  status.value = ''
  try {
    syncParagraphs(source.value)
    const { data } = await localRequest('/api/ai/rewrite', {
      method: 'POST', body: { intent: 'library-extract', provider: props.connection.provider || 'auto', material: material() },
      signal: AbortSignal.any([controller.signal, AbortSignal.timeout(100000)]),
    })
    if (controller.signal.aborted) return
    candidates.value = data.map(item => ({ ...item, key: crypto.randomUUID(), checked: false }))
    if (!candidates.value.length) status.value = '本次没有提取到新的资料。'
  } catch (reason) {
    if (!controller.signal.aborted) error.value = reason.message
  } finally {
    if (request === controller) { busy.value = false; request = undefined }
  }
}

function apply() {
  const chapter = source.value
  const selected = candidates.value.filter(item => item.checked)
  if (!chapter || !selected.length) return
  syncParagraphs(chapter)
  const book = props.book
  const known = new Set(records(book).map(item => item.kind + ':' + normalize(item.title)))
  if (selected.some(item => !item.title.trim() || !item.body.trim() ||
    item.chapterId !== chapter.id || !chapter.paragraphs.some(part => part.id === item.paragraphId && part.text.includes(item.quote)))) {
    error.value = '候选缺少内容，或引用的原文已变化。修改候选或重新提取后再保存。'
    return
  }
  let added = 0
  for (const item of selected) {
    const title = item.title.trim()
    const body = item.body.trim()
    const id = crypto.randomUUID()
    const anchor = { chapterId: item.chapterId, paragraphId: item.paragraphId }
    const key = item.kind + ':' + normalize(title)
    if (item.kind === 'annotation') {
      if (book.research.annotations.some(note => sameAnchor(note.anchor, anchor) && normalize(note.kind) === normalize(title) && note.text === body)) continue
      book.research.annotations.push({ id, anchor, kind: title, text: body })
    } else {
      if (known.has(key)) continue
      known.add(key)
      if (item.kind === 'foreshadow') {
        book.research.foreshadows.push({ id, title, description: body, status: 'open',
          anchors: [{ id: crypto.randomUUID(), anchor, role: '线索' }] })
      } else if (item.kind === 'event') {
        book.research.events.push({ id, title, description: body, anchor })
      } else {
        book.creative.cards.push({ id, kind: item.kind, title, body, ...anchor,
          state: '', aliases: [], keywords: [], pinned: false, confirmed: true, foreshadowId: '' })
      }
    }
    added += 1
  }
  candidates.value = candidates.value.filter(item => !item.checked)
  error.value = ''
  status.value = `已保存 ${added} 条资料${selected.length > added ? `，略过 ${selected.length - added} 条重复资料` : ''}。`
}
</script>

<template>
  <section class="library-ai">
    <div class="resource-heading"><h2>AI 整理资料</h2></div>
    <p class="library-muted">选择章节和段落范围。每条候选附原文证据，勾选后写入资料库。</p>
    <form class="library-ai-controls" @submit.prevent="extract">
      <label class="field-stack">来源章节
        <el-select v-model="chapterId" aria-label="来源章节" :disabled="busy"><el-option v-for="entry in sources" :key="entry.chapter.id" :label="`${entry.label} ${entry.chapter.title}`" :value="entry.chapter.id" /></el-select>
      </label>
      <label class="field-stack">起始段落<el-input-number v-model="start" :min="1" :max="source?.paragraphs.length || 1" :disabled="busy" aria-label="起始段落" /></label>
      <label class="field-stack">结束段落<el-input-number v-model="end" :min="1" :max="source?.paragraphs.length || 1" :disabled="busy" aria-label="结束段落" /></label>
      <label class="field-stack">资料类型
        <el-select v-model="kinds" multiple collapse-tags aria-label="资料类型" :disabled="busy"><el-option v-for="(label, key) in libraryKinds" :key="key" :label="label" :value="key" /></el-select>
      </label>
      <el-button :icon="Sparkles" type="primary" native-type="submit" :loading="busy" :disabled="!source || !kinds.length">提取资料</el-button>
      <el-button v-if="busy" @click="request?.abort()">取消</el-button>
    </form>
    <p v-if="error" class="field-error" role="alert">{{ error }}</p>
    <p v-if="status" class="library-muted" role="status">{{ status }}</p>
    <div v-if="candidates.length" class="library-record-grid library-ai-candidates">
      <article v-for="item in candidates" :key="item.key" class="resource-record creative-form">
        <el-checkbox v-model="item.checked" :label="`采用此${libraryKinds[item.kind]}`" />
        <el-input v-model="item.title" :aria-label="libraryKinds[item.kind] + '标题'" maxlength="80" />
        <el-input v-model="item.body" :aria-label="libraryKinds[item.kind] + '说明'" type="textarea" :rows="3" maxlength="600" />
        <blockquote>{{ item.quote }}</blockquote>
      </article>
    </div>
    <div v-if="candidates.length" class="dialog-actions">
      <el-button :disabled="busy" @click="candidates.forEach(item => { item.checked = true })">全选候选</el-button>
      <el-button type="primary" :disabled="busy || !candidates.some(item => item.checked)" @click="apply">保存选中资料</el-button>
    </div>
  </section>
</template>
