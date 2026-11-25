<script setup>
import { computed, nextTick, onUnmounted, reactive, ref, toRaw, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { BookOpen, FilePlus, Layers3, Pause, PenLine, Play, Send, Settings2, Sparkles } from '@lucide/vue'
import RevisionParagraph from './RevisionParagraph.vue'
import MarkdownPreview from './MarkdownPreview.vue'
import ChapterPlanning from './ChapterPlanning.vue'
import { bookMaterial, excerpt } from './book-profile.js'
import { localRequest } from './local-service.js'
import { chapterLayout, currentDraft, manuscriptStatistics, paragraphInput, paragraphMarkupSafe, paragraphRevisionSnapshot,
  paragraphSpans, pendingDrafts, revisionSnapshot, syncParagraphs, updateParagraph } from './revisions.js'
import { activeSkills, skillRequest } from './writing-skills.js'
import { ensureContextBudget, providerReady, withContext } from './context-options.js'
import { creativeMaterial } from './creative-tools.js'
import { contentSegments, generatedManuscript, insertSceneDivider, removeSceneDivider } from './scene-dividers.js'
import { markupBlocks } from './manuscript-markup.js'
import { markdownSnippet, preservesMarkup, sameProse } from './ai-review.js'
import SceneDivider from './SceneDivider.vue'
import { X } from '@lucide/vue'

const props = defineProps({
  book: { type: Object, required: true },
  chapter: { type: Object, required: true },
  chapters: { type: Array, required: true },
  connection: { type: Object, required: true },
  jumpTo: { type: Object, default: null },
  paused: Boolean,
  assistantVisible: Boolean,
  batchSelection: { type: Array, default: () => [] },
})
const emit = defineEmits(['connection', 'skills', 'research', 'mutation', 'next-chapter', 'profile', 'creative', 'compare'])
const selected = ref('')
const dialog = ref('')
const targetId = ref('')
const buffer = ref('')
const previousDraft = ref('')
const busy = ref(null)
const planningEditor = ref()
const error = ref('')
const auto = computed({
  get: () => props.book.aiAuto,
  set: (value) => { props.book.aiAuto = value },
})
const lastUsage = ref(null)
const sharing = reactive({ full: false, edits: false, votes: false, notes: false })
const paragraphList = computed(() => props.chapter.paragraphs || [])
const validatedDrafts = new WeakMap()
const revisionRows = computed(() => {
  const rows = []
  const blocks = markupBlocks(props.chapter.text)
  let blockIndex = 0
  let index = 0
  for (const segment of chapterLayout(props.chapter).segments) {
    while (blocks[blockIndex] && blocks[blockIndex].end <= segment.start) blockIndex += 1
    const block = blocks[blockIndex]
    if (segment.divider) {
      if (block?.type === 'heading' && rows.at(-1)?.markup === block && segment.end <= block.end) continue
      rows.push({ divider: segment, key: 'divider-' + segment.start })
    }
    else if (paragraphList.value[index]) {
      const paragraph = paragraphList.value[index++]
      const child = { paragraph, position: index }
      if (block && segment.start >= block.start && segment.end <= block.end) {
        let row = rows.at(-1)
        if (row?.markup !== block) {
          row = { key: 'markup-' + paragraph.id, markup: block, entries: [] }
          rows.push(row)
        }
        row.entries.push(child)
      } else rows.push({ key: paragraph.id, entries: [child] })
    }
  }
  for (const paragraph of paragraphList.value.slice(index)) rows.push({ key: paragraph.id, entries: [{ paragraph, position: ++index }] })
  return rows
})
const target = computed(() => paragraphList.value.find(({ id }) => id === targetId.value))
const ready = computed(() => providerReady(props.connection, props.connection.provider || 'auto'))
const skills = computed(() => activeSkills(props.book))
const statistics = computed(() => manuscriptStatistics(props.chapters, props.book.research))
const reviewQueue = computed(() => {
  const ids = new Set()
  let count = 0
  for (const paragraph of paragraphList.value) {
    for (const draft of paragraph.drafts) {
      if (draft.state !== 'pending') continue
      ids.add(paragraph.id)
      count++
    }
  }
  return { ids, count }
})
const waiting = computed(() => reviewQueue.value.count > 0)
const planning = computed(() => ['summary', 'draft'].includes(props.chapter.plan?.stage))
const nextToGenerate = computed(() => {
  if (!auto.value || !ready.value || waiting.value || props.paused || props.chapter.complete || planning.value) return
  let first
  for (const row of revisionRows.value) {
    if (row.markup?.type === 'code' || !row.entries) continue
    for (const { paragraph } of row.entries) {
      if (paragraph.drafts.length || paragraph.vote === 'keep' || paragraph.aiReviewedText === paragraph.text) continue
      if (paragraph.instruction || paragraph.vote === 'revise') return paragraph
      first ||= paragraph
    }
  }
  return first
})
const picked = computed(() => new Set(props.batchSelection))
const annotationCounts = computed(() => {
  const counts = new Map()
  for (const { anchor } of props.book.research.annotations) {
    if (anchor.chapterId === props.chapter.id) counts.set(anchor.paragraphId, (counts.get(anchor.paragraphId) || 0) + 1)
  }
  return counts
})
const foreshadowCounts = computed(() => {
  const counts = new Map()
  for (const record of props.book.research.foreshadows) {
    const ids = new Set(record.anchors.filter(item => item.anchor.chapterId === props.chapter.id).map(item => item.anchor.paragraphId))
    for (const id of ids) counts.set(id, (counts.get(id) || 0) + 1)
  }
  return counts
})
const sharingOptions = [
  { id: 'full', label: '完整正文', count: () => `${statistics.value.paragraphs} 段` },
  { id: 'edits', label: '逐段改动', count: () => `${statistics.value.edited} 段` },
  { id: 'votes', label: '赞 / 踩', count: () => `${statistics.value.likes} / ${statistics.value.dislikes}` },
  { id: 'notes', label: '批注', count: () => `${statistics.value.annotations} 条` },
]
const textDialogs = ['edit', 'edit-draft', 'prompt']
const focusedParagraph = computed(() => paragraphList.value.find(({ id }) => id === selected.value) || paragraphList.value[0])
const review = computed(() => ({
  ready: ready.value,
  busy: Boolean(busy.value),
  auto: auto.value,
  pending: reviewQueue.value.count,
  selected: focusedParagraph.value ? paragraphList.value.indexOf(focusedParagraph.value) + 1 : 0,
  excerpt: focusedParagraph.value?.text.trim().slice(0, 88) || '',
  skillCount: skills.value.length,
  stage: planning.value ? props.chapter.plan.stage : '',
  usage: lastUsage.value,
}))

function closeTextDialog() {
  if (textDialogs.includes(dialog.value)) dialog.value = ''
}

function anchorFor(paragraph) {
  return { chapterId: props.chapter.id, paragraphId: paragraph.id }
}

function generationMaterial(chapter, paragraph, options, earlierDraft) {
  const index = chapter.paragraphs.indexOf(paragraph)
  const book = props.book
  const neighbors = props.book.context.neighbors
  const material = {
    ...creativeMaterial(book, props.chapters, chapter, 'paragraph', [paragraph.text, paragraph.instruction].join('\n')),
    作品: book.title,
    章节: chapter.title,
    任务: earlierDraft ? '生成一个与上一候选写法不同的改稿。' : '按提示完成本段改稿。',
    目标段落: paragraph.text,
    逐段提示: paragraph.instruction,
    前文: chapter.paragraphs.slice(Math.max(0, index - neighbors), index).map(({ text }) => text),
    后文: chapter.paragraphs.slice(index + 1, index + neighbors + 1).map(({ text }) => text),
    作品设定: bookMaterial(book, props.chapters),
    技能: skills.value,
  }
  if (earlierDraft) material.上一候选 = excerpt(earlierDraft.text, 700)
  if (!paragraph.text.trim()) {
    const chapterIndex = props.chapters.findIndex(({ chapter: item }) => item.id === chapter.id)
    const previous = props.chapters[chapterIndex - 1]?.chapter
    material.任务 = '为新章写一个开篇段落，延续前一章的写法与剧情。'
    if (previous) material.上一章 = { 标题: previous.title, 结尾: paragraphSpans(previous.text).slice(-2).map(({ text }) => text) }
    if (book.outline.trim()) material.全书大纲 = excerpt(book.outline, 900)
  }
  if (options.full) material.全书正文 = props.chapters.map(({ chapter: item, label }) => ({ 章节: label, 标题: item.title, 正文: item.text }))
  if (options.edits || options.votes) {
    material.逐段记录 = props.chapters.flatMap(({ chapter: item }) => (item.paragraphs || []).flatMap((record, position) => {
      const includeEdit = options.edits && record.original !== record.text
      const includeVote = options.votes && record.vote
      return includeEdit || includeVote ? [{
        章节: item.title, 段落: position + 1,
        ...(includeEdit ? { 原文: record.original, 改后: record.text } : {}),
        ...(includeVote ? { 评语: record.vote === 'keep' ? '这段保留' : '这段需要改' } : {}),
      }] : []
    }))
  }
  if (options.notes) {
    material.批注 = book.research.annotations.map((annotation) => ({
      章节: props.chapters.find(({ chapter: item }) => item.id === annotation.anchor.chapterId)?.chapter.title,
      类别: annotation.kind, 内容: annotation.text,
    }))
  }
  return withContext(book, material)
}

async function generate(paragraph, options = {}, earlierDraft, automatic = false) {
  if (!paragraph || busy.value) return
  if (!ready.value) {
    emit('connection')
    return
  }
  const chapter = props.chapter
  const provider = props.connection.provider || 'auto'
  const controller = new AbortController()
  const base = paragraph.text
  busy.value = { chapterId: chapter.id, paragraphId: paragraph.id, controller }
  error.value = ''
  try {
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(180000)])
    const material = ensureContextBudget(props.book, generationMaterial(chapter, paragraph, options, earlierDraft))
    const result = await localRequest('/api/ai/rewrite', {
      method: 'POST',
      signal, body: { provider, material, ...skillRequest(props.book, 'paragraph') },
    })
    let candidate = result.text
    let reviewNote = ''
    if (automatic && candidate?.trim() && !sameProse(base, candidate)) {
      busy.value = { ...busy.value, reviewing: true }
      const { data } = await localRequest('/api/ai/rewrite', {
        method: 'POST', signal, body: { provider, intent: 'paragraph-polish', ...skillRequest(props.book, 'paragraph-polish'),
          material: ensureContextBudget(props.book, withContext(props.book, {
            目标段落: base, 初稿: candidate, 逐段提示: paragraph.instruction, 作品设定: material.作品设定,
            技能: material.技能, 世界书: material.世界书, 前文: material.前文, 后文: material.后文,
          })) },
      })
      candidate = data.decision === 'keep' ? base : data.decision === 'revise' ? data.text : candidate
      reviewNote = data.note
    }
    const liveChapter = props.chapters.find(({ chapter: item }) => item.id === chapter.id)?.chapter
    const liveParagraph = liveChapter?.paragraphs?.find(({ id }) => id === paragraph.id)
    if (!liveParagraph || controller.signal.aborted) return
    if (liveParagraph.text !== base) throw new Error('原文已变化，请重新生成候选。')
    const generated = generatedManuscript(candidate || '')
    const code = contentSegments(generated).some(line => line.codeStart !== undefined)
    const text = code ? generated : contentSegments(generated).filter(line => !line.divider).map(line => line.text.trim()).join(' ').trim()
    if (sameProse(base, text)) {
      liveParagraph.aiReviewedText = base
      return
    }
    if (!text) throw new Error('模型未返回可用的候选。')
    const indentation = code ? '' : base.match(/^[ \t\u3000]+/)?.[0] || ''
    if (!paragraphMarkupSafe(liveChapter, liveParagraph.id, indentation + text)) throw new Error('候选改变了原有 Markdown 结构，请重新生成。')
    const draft = { id: crypto.randomUUID(), base, text: indentation + text, state: 'pending', provider: result.provider || provider, model: result.model, usage: result.usage, reviewNote }
    if (result.usage && props.chapter.id === chapter.id) lastUsage.value = { ...result.usage, account: result.account, model: result.model }
    validatedDrafts.set(draft, { source: liveChapter.text, candidate: draft.text })
    emit('mutation', liveChapter.id, paragraphRevisionSnapshot(liveChapter, liveParagraph))
    liveParagraph.drafts.push(draft)
    liveParagraph.activeDraftId = draft.id
    if (props.chapter.id === chapter.id && !selected.value) selected.value = paragraph.id
  } catch (reason) {
    if (!controller.signal.aborted) error.value = reason.message
  } finally {
    busy.value = null
  }
}

function chooseVariant(paragraph, step) {
  const drafts = pendingDrafts(paragraph)
  const index = drafts.findIndex(({ id }) => id === currentDraft(paragraph)?.id)
  if (drafts[index + step]) {
    record(paragraph)
    paragraph.activeDraftId = drafts[index + step].id
  }
}

function openTextDialog(kind, paragraph) {
  targetId.value = paragraph.id
  const draft = currentDraft(paragraph)
  previousDraft.value = draft?.id || ''
  buffer.value = kind === 'prompt' ? paragraph.instruction : kind === 'edit-draft' ? draft?.text || '' : paragraph.text
  dialog.value = kind
}

function saveText() {
  const paragraph = target.value
  if (!paragraph) return
  if (dialog.value === 'prompt') {
    if (paragraph.instruction !== buffer.value.trim()) record(paragraph)
    paragraph.instruction = buffer.value.trim()
  } else if (dialog.value === 'edit-draft') {
    const draft = paragraph.drafts.find(({ id }) => id === previousDraft.value)
    if (!draft || !buffer.value.trim()) return
    record(paragraph)
    draft.text = paragraphInput(buffer.value)
    draft.base = paragraph.text
  } else {
    if (!writeParagraph(paragraph, buffer.value)) {
      ElMessage.warning('请填写段落正文。')
      return
    }
  }
  dialog.value = ''
}

function restoreOriginal() {
  if (!target.value) return
  writeParagraph(target.value, target.value.original)
  dialog.value = ''
}

function writeParagraph(paragraph, value) {
  const previous = revisionSnapshot(props.chapter)
  const updated = updateParagraph(props.chapter, paragraph.id, value)
  if (updated && previous.text !== props.chapter.text) emit('mutation', props.chapter.id, previous)
  return updated
}

function record(paragraph, includeText = false) {
  const chapter = props.chapter
  emit('mutation', chapter.id, paragraph ? paragraphRevisionSnapshot(chapter, paragraph, includeText) : revisionSnapshot(chapter))
}

function insertDivider() {
  if (planning.value) return
  const index = paragraphList.value.indexOf(focusedParagraph.value)
  const anchor = paragraphSpans(props.chapter.text)[index]?.end ?? props.chapter.text.length
  record()
  props.chapter.text = insertSceneDivider(props.chapter.text, anchor).text
}

function insertMarkdown(command) {
  const paragraph = focusedParagraph.value
  if (!paragraph || planning.value) return
  const index = paragraphList.value.indexOf(paragraph)
  const range = paragraphSpans(props.chapter.text)[index]
  const snippet = markdownSnippet(command, paragraph.text)
  if (!range || snippet === paragraph.text) return
  record()
  props.chapter.text = props.chapter.text.slice(0, range.start) + snippet + props.chapter.text.slice(range.end)
  syncParagraphs(props.chapter)
}

function removeDivider(divider) {
  record()
  props.chapter.text = removeSceneDivider(props.chapter.text, divider)
}

function cancelGeneration() {
  busy.value?.controller.abort()
  planningEditor.value?.cancel()
}

function openSend(paragraph = focusedParagraph.value) {
  if (!paragraph) return
  targetId.value = paragraph.id
  dialog.value = 'send'
}

function preparePrompt(instruction) {
  const paragraph = focusedParagraph.value
  if (!paragraph) return
  if (paragraph.instruction !== instruction) record(paragraph)
  paragraph.instruction = instruction
  openSend(paragraph)
}

function send() {
  if (!target.value) return
  if (!ready.value) {
    dialog.value = ''
    emit('connection')
    return
  }
  const paragraph = target.value
  const options = { ...sharing }
  dialog.value = ''
  generate(paragraph, options)
}

function handleAction(action, paragraph) {
  const draft = currentDraft(paragraph)
  if (action === 'keep' || action === 'revise') {
    record(paragraph)
    paragraph.vote = paragraph.vote === action ? '' : action
  } else if (['edit', 'edit-draft', 'prompt'].includes(action)) {
    openTextDialog(action, paragraph)
  } else if (action === 'roll') {
    generate(paragraph, {}, draft)
  } else if (action === 'accept' && draft?.base === paragraph.text) {
    const chapter = props.chapter
    const cached = validatedDrafts.get(toRaw(draft))
    if (cached?.source !== chapter.text || cached.candidate !== draft.text) {
      if (sameProse(paragraph.text, draft.text)) { record(paragraph); draft.state = 'rejected'; ElMessage.info('候选没有正文改动。'); return }
      if (!paragraphMarkupSafe(chapter, paragraph.id, draft.text)) { ElMessage.warning('候选改变了原有 Markdown，请调整后再接受。'); return }
    }
    const next = paragraphInput(draft.text)
    const previous = /[\r\n]/u.test(next) ? revisionSnapshot(chapter) : paragraphRevisionSnapshot(chapter, paragraph, true)
    if (!updateParagraph(chapter, paragraph.id, next)) return
    for (const pending of pendingDrafts(paragraph)) pending.state = pending.id === draft.id ? 'accepted' : 'rejected'
    emit('mutation', chapter.id, previous)
  } else if (action === 'reject' && draft) {
    record(paragraph)
    draft.state = 'rejected'
  } else if (['notes', 'foreshadow', 'related'].includes(action)) {
    emit('research', { notes: 'annotations', foreshadow: 'foreshadows', related: 'related' }[action], anchorFor(paragraph))
  }
}

function toggleAuto() {
  auto.value = !auto.value
  if (auto.value) error.value = ''
}

async function approveChapter() {
  const chapter = props.chapter
  if (chapter.plan.draftBase !== undefined && chapter.text !== chapter.plan.draftBase) {
    ElMessage.warning('正文已变化，请重新生成候选。')
    return
  }
  const draft = generatedManuscript(chapter.plan.draft)
  if (!draft) return
  if (chapter.text.trim()) {
    if (sameProse(chapter.text, draft)) { ElMessage.info('候选没有正文改动。'); return }
    if (!preservesMarkup(chapter.text, draft)) { ElMessage.warning('候选改变了已有 Markdown 结构，请调整后再接受。'); return }
    try {
      await ElMessageBox.confirm('接受初稿会替换当前章节已有的正文。', '接受章节初稿', { confirmButtonText: '接受初稿', cancelButtonText: '取消' })
    } catch {
      return
    }
  }
  const previous = revisionSnapshot(chapter)
  auto.value = false
  chapter.text = draft
  chapter.plan.stage = 'approved'
  syncParagraphs(chapter)
  emit('mutation', chapter.id, previous)
  ElMessage.success('完整初稿已写入正文')
}

const sendPreview = computed(() => {
  if (dialog.value !== 'send' || !target.value) return ''
  try { return JSON.stringify(ensureContextBudget(props.book, generationMaterial(props.chapter, target.value, sharing)), null, 2) }
  catch (reason) { return reason.message }
})

watch(() => [props.chapter.id, props.chapter.text], () => syncParagraphs(props.chapter), { immediate: true })
watch(() => props.chapter.id, () => {
  selected.value = ''
  dialog.value = ''
  error.value = ''
  lastUsage.value = null
})
watch(() => props.jumpTo, async (anchor) => {
  if (anchor?.chapterId !== props.chapter.id) return
  selected.value = anchor.paragraphId
  await nextTick()
  document.getElementById(`revision-${anchor.paragraphId}`)?.scrollIntoView({ block: 'center' })
}, { immediate: true, flush: 'post' })
watch([ready, auto, nextToGenerate, waiting, busy, dialog, error, planning, () => props.paused, () => props.chapter.complete], () => {
  if (!props.chapter.complete && !planning.value && ready.value && auto.value && nextToGenerate.value && !waiting.value && !busy.value && !dialog.value && !error.value && !props.paused) {
    generate(nextToGenerate.value, {}, undefined, true)
  }
}, { immediate: true, flush: 'post' })
onUnmounted(() => busy.value?.controller.abort())

defineExpose({ review, openSend, preparePrompt, toggleAuto, insertDivider, insertMarkdown, cancelGeneration })
</script>

<template>
  <div class="ai-workspace">
    <ChapterPlanning v-if="planning" :key="chapter.id" ref="planningEditor" :book="book" :chapter="chapter" :chapters="chapters" :ready="ready" :provider="connection.provider || 'auto'" :paused="paused"
      @connection="emit('connection')" @mutation="emit('mutation', chapter.id, $event)" @approve="approveChapter" @creative="emit('creative', $event)" @compare="emit('compare', $event)" />
    <template v-else>
    <div class="ai-workspace-bar">
      <div class="ai-mode-status"><Sparkles :size="17" /><h3>逐段审阅</h3><span v-if="busy" class="ai-generating"><i></i>{{ busy.reviewing ? '复审候选中' : '生成候选中' }}</span><span v-else-if="waiting">有候选待审批</span></div>
      <div v-if="!assistantVisible" class="ai-workspace-actions">
        <button class="toolbar-button" :aria-pressed="auto" :title="auto ? '暂停自动生成' : '恢复自动生成'" @click="toggleAuto"><component :is="auto ? Pause : Play" :size="15" />{{ auto ? '暂停自动生成' : '恢复自动生成' }}</button>
        <button class="toolbar-button" title="AI 服务" aria-label="AI 服务" @click="emit('connection')"><Settings2 :size="16" /></button>
        <button class="toolbar-button" title="创作设定" @click="emit('profile')"><PenLine :size="15" /><span>设定</span></button>
        <button class="toolbar-button" title="写作技能" @click="emit('skills')"><Layers3 :size="15" /><span>技能{{ skills.length ? ` · ${skills.length}` : '' }}</span></button>
        <button class="toolbar-button" title="写作资料" @click="emit('research', 'annotations', null)"><BookOpen :size="15" /><span>资料</span></button>
        <button class="toolbar-button" title="新建下一章" @click="emit('next-chapter')"><FilePlus :size="15" /><span>下一章</span></button>
        <el-button type="primary" size="small" :icon="Send" :disabled="Boolean(busy)" @click="openSend()">发送给 AI</el-button>
      </div>
    </div>
    <p v-if="!ready" class="ai-explanation">连接 AI 服务后可生成候选。</p>
    <p v-if="error" class="field-error ai-error" role="alert">{{ error }} <button class="text-action" @click="error = ''">重试自动生成</button></p>

    <div class="revision-list">
      <template v-for="row in revisionRows" :key="row.key">
        <div v-if="row.divider" class="revision-scene-row"><SceneDivider :variant="book.dividerStyle" />
          <button class="icon-button scene-remove" aria-label="删除场景分隔线" title="删除场景分隔线" @click="removeDivider(row.divider)"><X :size="15" /></button>
        </div>
        <div v-if="row.markup" class="revision-markup" :class="{ 'revision-code': row.markup.type === 'code' }">
          <MarkdownPreview manuscript :source="chapter.text.slice(row.markup.start, row.markup.end)" :token="row.markup.token" :divider-style="book.dividerStyle" />
        </div>
        <component v-if="row.entries" :is="row.markup ? 'details' : 'div'"
          :class="{ 'revision-code-lines': row.markup }"
          :open="row.markup ? row.entries.some(({ paragraph }) => paragraph.id === selected || reviewQueue.ids.has(paragraph.id)) : undefined">
          <summary v-if="row.markup">{{ row.markup.type === 'code' ? '编辑代码行' : '逐行审阅 Markdown' }}</summary>
          <RevisionParagraph v-for="{ paragraph, position } in row.entries" :key="paragraph.id" :id="`revision-${paragraph.id}`"
          :paragraph="paragraph" :position="position" :selected="selected === paragraph.id" :multi-selected="picked.has(paragraph.id)"
          :busy="Boolean(busy && reviewQueue.ids.has(paragraph.id))"
          :notes="annotationCounts.get(paragraph.id)" :foreshadows="foreshadowCounts.get(paragraph.id)"
          @select="selected = paragraph.id" @action="handleAction($event, paragraph)" @variant="chooseVariant(paragraph, $event)" />
        </component>
      </template>
    </div>

    <el-dialog :model-value="textDialogs.includes(dialog)"
      :title="{ edit: '编辑段落', 'edit-draft': '修改候选', prompt: '告诉 AI 这一段怎么改' }[dialog] || '编辑段落'"
      width="min(580px, 94vw)" @update:model-value="closeTextDialog">
      <el-form label-position="top" @submit.prevent="saveText">
        <div v-if="dialog !== 'prompt'" class="original-block"><strong>原文</strong><p>{{ target?.original || '此段原本为空。' }}</p></div>
        <el-form-item :label="dialog === 'prompt' ? '逐段提示' : '修改后'"><el-input v-model="buffer" type="textarea" :rows="5" autofocus /></el-form-item>
        <div class="dialog-actions edit-dialog-actions">
          <el-button v-if="dialog === 'edit' && target?.text !== target?.original" class="restore-original" @click="restoreOriginal">还原原文</el-button>
          <el-button @click="dialog = ''">取消</el-button>
          <el-button v-if="dialog === 'prompt'" :disabled="!buffer.trim()" @click="saveText(); openSend(target)">保存并准备发送</el-button>
          <el-button type="primary" native-type="submit">保存</el-button>
        </div>
      </el-form>
    </el-dialog>

    <el-dialog :model-value="dialog === 'send'" title="发送给 AI" width="min(490px, 94vw)" @update:model-value="dialog === 'send' && (dialog = '')">
      <p class="dialog-description">{{ target?.text.trim() ? '目标段和逐段提示会一并发送。其他资料按上下文设置选取和压缩。' : '开篇生成参考上一章结尾。其他资料按上下文设置选取和压缩。' }}</p>
      <div class="send-options">
        <el-checkbox v-for="option in sharingOptions" :key="option.id" v-model="sharing[option.id]" class="send-option" :aria-label="option.label">
          <span>{{ option.label }}</span><strong>{{ option.count() }}</strong>
        </el-checkbox>
      </div>
      <p v-if="skills.length" class="dialog-description">本次技能 · {{ skills.map(({ name }) => name).join('、') }}</p>
      <details><summary>预览本次发送内容</summary><pre class="creative-preview">{{ sendPreview }}</pre></details>
      <div class="dialog-actions"><el-button @click="dialog = ''">取消</el-button><el-button type="primary" :icon="Send" :disabled="Boolean(busy)" @click="send">{{ ready ? '确认发送' : '连接 AI 服务' }}</el-button></div>
    </el-dialog>
    </template>
  </div>
</template>
