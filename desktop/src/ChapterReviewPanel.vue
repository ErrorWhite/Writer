<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { chapterTextDiff, creativeMaterial, evidenceMaterial, reviewRoles } from './creative-tools.js'
import { ensureContextBudget, providerReady, withContext } from './context-options.js'
import { localRequest } from './local-service.js'
import { sameAnchor, syncParagraphs } from './revisions.js'

const props = defineProps({
  book: { type: Object, required: true }, chapter: { type: Object, required: true },
  chapters: { type: Array, required: true }, connection: { type: Object, required: true },
  versions: Boolean, flush: { type: Function, required: true },
})
const emit = defineEmits(['connection', 'jump', 'apply-draft', 'restore-chapter'])
const current = computed(() => props.chapter)
const stored = computed(() => props.book.creative)
const plannedDraft = computed(() => current.value.plan?.stage === 'draft')
const draft = ref(plannedDraft.value ? current.value.plan.draft : '')
const draftBase = ref(plannedDraft.value ? current.value.plan.draftBase ?? current.value.text : current.value.text)
const comparing = ref(false)
const start = ref(1)
const end = ref(null)
const scope = computed(() => current.value.paragraphs || [])
const busy = ref('')
const error = ref('')
const status = ref('')
const versionList = ref([])
const selectedVersion = ref(null)
let controller

function versionUrl(version) {
  const query = new URLSearchParams({ id: props.book.id, chapter: current.value.id })
  if (version) query.set('version', version)
  return '/api/book/versions?' + query
}

const snapshot = () => JSON.parse(JSON.stringify(current.value))

const diff = computed(() => chapterTextDiff(current.value.text, props.versions ? selectedVersion.value?.text || '' : draft.value))
const reviews = computed(() => stored.value.reviews.filter(item => item.chapterId === current.value.id))
const changes = computed(() => stored.value.changes.filter(item => item.chapterId === current.value.id))
const allowProposals = computed(() => current.value.complete || current.value.plan?.stage === 'approved')
const proposalTargets = computed(() => [
  ...stored.value.cards.filter(item => item.confirmed && ['character', 'rule'].includes(item.kind))
    .map(item => ({ id: item.id, kind: item.kind, title: item.title, state: item.state || '', body: item.body })),
  ...props.book.research.foreshadows.filter(item => item.status !== 'recovered')
    .map(item => ({ id: item.id, kind: 'foreshadow', title: item.title, state: item.status || '', body: item.description })),
])

syncParagraphs(current.value)
function selectedParagraphs() {
  const first = Number(start.value) || 1
  const last = Number(end.value) || scope.value.length
  if (last < first) throw new Error('结束段落必须在起始段落之后。')
  return scope.value.slice(first - 1, last).filter(item => item.text.trim())
}

function materialFor(intent) {
  const paragraphs = selectedParagraphs()
  if (!paragraphs.length) throw new Error('请选择有正文的段落。')
  const evidence = evidenceMaterial(current.value, paragraphs)
  const query = paragraphs.map(item => item.text).join('\n')
  return ensureContextBudget(props.book, withContext(props.book, {
    审阅原文: evidence,
    ...(intent === 'state-proposal' ? { 可提案条目: proposalTargets.value } : {}),
    ...creativeMaterial(props.book, props.chapters, current.value, intent, query),
  }))
}

function previewFor(intent) {
  try { return JSON.stringify(materialFor(intent), null, 2) }
  catch (reason) { return reason.message }
}
const preview = computed(() => previewFor('chapter-review'))
const proposalPreview = computed(() => previewFor('state-proposal'))

async function ask(intent) {
  if (busy.value) return
  if (!providerReady(props.connection, props.connection.provider || 'auto')) { emit('connection'); return }
  if (intent === 'state-proposal' && !allowProposals.value) return
  error.value = ''
  status.value = ''
  controller = new AbortController()
  const signal = controller.signal
  const source = current.value.text
  const id = current.value.id
  busy.value = intent
  try {
    const material = materialFor(intent)
    const { data } = await localRequest('/api/ai/rewrite', { method: 'POST',
      body: { provider: props.connection.provider || 'auto', intent, material },
      signal: AbortSignal.any([signal, AbortSignal.timeout(100000)]),
    })
    if (signal.aborted) return
    if (current.value.id !== id || current.value.text !== source) throw new Error('正文已变化，请重新运行。')
    const results = data.map(item => ({ ...item, id: crypto.randomUUID(), at: Date.now(), state: 'pending',
      ...(intent === 'state-proposal' ? { previous: material.可提案条目.find(target => target.id === item.targetId)?.state || '' } : {}),
    }))
    const records = intent === 'chapter-review' ? stored.value.reviews : stored.value.changes
    records.push(...results)
    status.value = results.length ? `返回 ${results.length} 条待确认记录。` : '这次检查没有发现需要处理的条目。'
  } catch (reason) {
    if (!signal.aborted) error.value = reason.message
  } finally { busy.value = '' }
}

function approveChange(item) {
  error.value = ''
  const part = scope.value.find(paragraph => paragraph.id === item.paragraphId)
  if (!part?.text.includes(item.quote)) { error.value = '原文证据已变化，请重新提取。'; return }
  const anchor = { chapterId: item.chapterId, paragraphId: item.paragraphId }
  if (item.kind === 'foreshadow') {
    const target = props.book.research.foreshadows.find(record => record.id === item.targetId)
    if (!target || (target.status || '') !== item.previous) { error.value = '伏笔状态已变化，请重新提取。'; return }
    target.status = 'recovered'
    if (!target.anchors.some(record => record.role === '回收' && sameAnchor(record.anchor, anchor))) {
      target.anchors.push({ id: crypto.randomUUID(), role: '回收', anchor })
    }
  } else {
    const target = stored.value.cards.find(record => record.id === item.targetId && record.kind === item.kind)
    if (!target?.confirmed || (target.state || '') !== item.previous) { error.value = '资料卡状态已变化，请重新提取。'; return }
    target.state = item.value
    target.stateAnchor = anchor
  }
  item.state = 'applied'
}

function acceptDraft() {
  if (!draft.value.trim()) return
  if (draftBase.value !== current.value.text) { error.value = '正文已变化，请按当前正文重新比稿。'; return }
  emit('apply-draft', { chapterId: current.value.id, base: draftBase.value, text: draft.value, planned: plannedDraft.value })
}

function rejectDraft() {
  if (plannedDraft.value) emit('apply-draft', { chapterId: current.value.id, base: draftBase.value, planned: true, reject: true })
  draft.value = ''
  comparing.value = false
  draftBase.value = current.value.text
}

async function versionAction(action, version) {
  if (busy.value) return
  error.value = ''
  status.value = ''
  busy.value = action
  try {
    if (action === 'list') versionList.value = (await localRequest(versionUrl())).versions
    if (action === 'open') selectedVersion.value = await localRequest(versionUrl(version.id))
    if (action === 'create' || action === 'restore') {
      const original = snapshot()
      const restored = selectedVersion.value
      if (action === 'restore') {
        await ElMessageBox.confirm('恢复所选章节版本？当前内容将先存为还原点。', '恢复章节版本', {
          confirmButtonText: '创建还原点并恢复', cancelButtonText: '取消',
        })
      }
      await props.flush()
      if (current.value.id !== original.id || current.value.text !== original.text) throw new Error('章节已变化，请重新操作。')
      const result = await localRequest(versionUrl(), { method: 'POST', body: original })
      versionList.value = result.versions
      if (action === 'restore') {
        emit('restore-chapter', { original, restored })
        status.value = '已创建恢复前的还原点。'
        selectedVersion.value = null
      } else status.value = '已创建章节还原点。'
    }
  } catch (reason) {
    if (reason instanceof Error) error.value = reason.message
  } finally { busy.value = '' }
}

watch(() => props.versions, visible => { if (visible) versionAction('list') }, { immediate: true })
onUnmounted(() => controller?.abort())
</script>

<template>
  <section class="creative-body creative-form">
    <div class="resource-heading">
      <h2>{{ versions ? '章节版本' : '审阅与比稿' }}</h2>
      <span>{{ chapter.title }}</span>
    </div>
    <template v-if="versions">
      <div class="creative-controls"><el-button type="primary" :loading="busy === 'create'" :disabled="!!busy" @click="versionAction('create')">创建章节还原点</el-button></div>
      <p v-if="!versionList.length && !busy" class="resource-empty">此章节暂无还原点。</p>
      <div class="creative-controls"><el-button v-for="version in versionList" :key="version.id" :disabled="!!busy" @click="versionAction('open', version)">{{ new Date(version.at).toLocaleString('zh-CN') }} · {{ version.title }}</el-button></div>
      <el-button v-if="selectedVersion" type="warning" :disabled="!!busy" @click="versionAction('restore')">恢复此章节版本</el-button>
    </template>
    <template v-else>
      <div class="creative-grid">
        <label class="field-stack">当前正文<el-input :model-value="chapter.text" type="textarea" :rows="9" readonly /></label>
        <label class="field-stack">候选稿<el-input v-model="draft" type="textarea" :rows="9" placeholder="在此编辑或粘贴候选稿" /></label>
      </div>
      <div class="creative-controls">
        <el-button @click="comparing = !comparing">{{ comparing ? '收起差异' : '比较差异' }}</el-button>
        <el-button :disabled="!draft && !plannedDraft" @click="rejectDraft">拒绝候选</el-button>
        <el-button type="primary" :disabled="!draft.trim()" @click="acceptDraft">接受候选为正文</el-button>
      </div>
    </template>

    <div v-if="versions ? selectedVersion : comparing" class="creative-grid creative-diff">
      <section v-for="(lines, index) in diff" :key="index">
        <h4>{{ index === 0 ? '当前正文' : versions ? '所选还原点' : '候选稿' }}</h4>
        <pre class="creative-preview"><span v-for="(line, n) in lines" :key="n" :class="{ 'diff-remove': index === 0 && line.changed, 'diff-add': index === 1 && line.changed }">{{ line.text || ' ' }}{{ '\n' }}</span></pre>
      </section>
    </div>

    <template v-if="!versions">
      <h3>章节审阅</h3>
      <p class="dialog-description">检查设定一致性、跨章衔接和文风。可以指定要检查的正文段落。</p>
      <div class="creative-controls creative-range">
        <label class="field-stack">起始段落<el-input-number v-model="start" aria-label="起始段落" :min="1" :max="Math.max(1, scope.length)" :disabled="!!busy" /></label>
        <label class="field-stack">结束段落<el-input-number v-model="end" aria-label="结束段落" :min="1" :max="Math.max(1, scope.length)" :placeholder="String(scope.length)" :disabled="!!busy" /></label>
        <el-button :loading="busy === 'chapter-review'" :disabled="!!busy" @click="ask('chapter-review')">审阅正文</el-button>
        <el-button v-if="allowProposals" :loading="busy === 'state-proposal'" :disabled="!!busy || !proposalTargets.length" @click="ask('state-proposal')">提取世界状态提案</el-button>
        <el-button v-if="busy && controller" @click="controller.abort()">取消请求</el-button>
      </div>
      <details><summary>预览本次审阅资料</summary><pre class="creative-preview">{{ preview }}</pre></details>
      <details v-if="allowProposals && proposalTargets.length"><summary>预览本次状态提案资料</summary><pre class="creative-preview">{{ proposalPreview }}</pre></details>
      <article v-for="item in reviews" :key="item.id" class="resource-record">
        <strong>{{ reviewRoles[item.role] }}</strong><small v-if="item.state === 'dismissed'" class="creative-meta">已标记误报</small>
        <p>{{ item.note }}</p><blockquote>{{ item.quote }}</blockquote>
        <div class="resource-record-actions">
          <button class="text-action" @click="emit('jump', item)">跳到原文</button>
          <button class="text-action" @click="item.state = item.state === 'dismissed' ? 'pending' : 'dismissed'">{{ item.state === 'dismissed' ? '取消误报标记' : '标记误报' }}</button>
        </div>
      </article>
      <article v-for="item in changes" :key="item.id" class="resource-record">
        <strong>{{ item.kind === 'foreshadow' ? '伏笔回收提案' : '状态提案' }} · {{ proposalTargets.find(target => target.id === item.targetId)?.title || book.research.foreshadows.find(target => target.id === item.targetId)?.title || '原条目' }}</strong>
        <small class="creative-meta">{{ { pending: '待确认', applied: '已确认', dismissed: '已拒绝' }[item.state] }}</small>
        <p>{{ item.value }}</p><p>{{ item.note }}</p><blockquote>{{ item.quote }}</blockquote>
        <div class="resource-record-actions">
          <button class="text-action" @click="emit('jump', item)">跳到原文</button>
          <button v-if="item.state === 'pending'" class="text-action" @click="approveChange(item)">确认提案</button>
          <button v-if="item.state === 'pending'" class="text-action danger" @click="item.state = 'dismissed'">拒绝提案</button>
        </div>
      </article>
    </template>
    <p v-if="error" class="field-error" role="alert">{{ error }}</p>
    <p v-if="status" class="dialog-description" role="status">{{ status }}</p>
  </section>
</template>
