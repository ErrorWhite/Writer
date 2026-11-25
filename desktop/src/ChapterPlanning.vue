<script setup>
import { preservesMarkup, sameProse } from './ai-review.js'
import { computed, onUnmounted, ref, watch } from 'vue'
import { Check, FileText, PenLine, RotateCw, Sparkles } from '@lucide/vue'
import { bookMaterial, excerpt } from './book-profile.js'
import { localRequest } from './local-service.js'
import { activeSkills, skillRequest } from './writing-skills.js'
import { ensureContextBudget, withContext } from './context-options.js'
import { creativeMaterial } from './creative-tools.js'
import { generatedManuscript, contentSegments } from './scene-dividers.js'
import { revisionSnapshot } from './revisions.js'
import MarkdownPreview from './MarkdownPreview.vue'

const props = defineProps({
  book: { type: Object, required: true },
  chapter: { type: Object, required: true },
  chapters: { type: Array, required: true },
  ready: Boolean,
  paused: Boolean,
  provider: { type: String, default: 'auto' },
})
const emit = defineEmits(['connection', 'approve', 'mutation', 'creative', 'compare'])
const plan = computed(() => props.chapter.plan)
const busy = ref('')
const error = ref('')
const separatorCount = computed(() => contentSegments(plan.value.draft).filter(({ divider }) => divider).length)
let controller
let attempted = false

function context(intent, summary) {
  const index = props.chapters.findIndex(({ chapter }) => chapter.id === props.chapter.id)
  const previous = props.chapters[index - 1]?.chapter
  const chapterContext = previous
    ? { 标题: previous.title, 摘要: previous.plan?.summary || '', 结尾: previous.text.slice(previous.plan?.summary ? -450 : -1700) }
    : undefined
  const feedbackSource = props.chapters.find(({ chapter }) => chapter.id === props.chapter.plan?.handoffFrom)?.chapter || previous
  const handoff = feedbackSource?.handoff
  const recentLessons = props.chapters.slice(Math.max(0, index - 5), Math.max(0, index)).filter(({ chapter }) => chapter.id !== feedbackSource?.id).flatMap(({ chapter }) =>
    (chapter.handoff?.lessons || []).map(item => ({ 操作: item.kind === 'avoid' ? '避免' : '沿用', 经验: excerpt(item.text, 170) }))).slice(-10)
  const feedback = previous?.paragraphs?.filter(item => ['keep', 'revise'].includes(item.vote)).slice(-12)
    .map(item => ({ 意见: item.vote === 'keep' ? '延续此处理' : '避免此处理', 片段: excerpt(item.text, 140), 备注: excerpt(item.instruction || '', 100) }))
  return withContext(props.book, {
    任务: '首先承接上一章已确定的铺垫；作者选择的方向用于本章推进，其余备选只作参考。',
    按此方向推进: props.chapter.plan?.direction || undefined,
    上一章交接: handoff && { 剧情: excerpt(handoff.recap, 200), 必须承接: excerpt(handoff.bridge, 600),
      学习经验: handoff.lessons?.slice(0, 3).map(item => ({ 操作: item.kind === 'avoid' ? '避免' : '沿用', 经验: excerpt(item.text, 80) })) },
    近期学习反馈: recentLessons,
    所在卷方向: props.chapters[index]?.volume?.direction,
    ...creativeMaterial(props.book, props.chapters, props.chapter, intent, summary),
    作品设定: bookMaterial(props.book, props.chapters, intent === 'chapter' ? 'chapter' : 'excerpt'),
    本章标题: props.chapter.title,
    上一章: chapterContext,
    上一章反馈: feedback,
    全书大纲: excerpt(props.book.outline, 1400),
    章节摘要: summary,
    技能: intent === 'chapter' ? activeSkills(props.book) : [],
  })
}

function requestPreview(intent) {
  try { return JSON.stringify(ensureContextBudget(props.book, context(intent, intent === 'chapter' ? plan.value.summary.trim() : '')), null, 2) }
  catch (reason) { return reason.message }
}
const summaryPreview = computed(() => requestPreview('summary'))
const chapterPreview = computed(() => requestPreview('chapter'))

async function generate(intent) {
  if (busy.value) return
  if (!props.ready) {
    emit('connection')
    return
  }
  const summary = plan.value.summary.trim()
  if (intent === 'chapter' && !summary) return
  if (intent === 'summary') attempted = true
  const baseline = plan.value.summary
  const source = props.chapter.text
  controller = new AbortController()
  busy.value = intent
  error.value = ''
  try {
    const { text } = await localRequest('/api/ai/rewrite', {
      method: 'POST',
      signal: AbortSignal.any([controller.signal, AbortSignal.timeout(intent === 'chapter' ? 205000 : 145000)]),
      body: { provider: props.provider, intent, ...skillRequest(props.book, intent),
        material: ensureContextBudget(props.book, context(intent, intent === 'chapter' ? summary : '')) },
    })
    if (controller.signal.aborted) return
    if (!text?.trim()) throw new Error('模型未返回可用内容。')
    if (plan.value.summary !== baseline || props.chapter.text !== source) throw new Error('生成期间摘要或正文已更新，请重新生成。')
    emit('mutation', revisionSnapshot(props.chapter))
    const suggestion = intent === 'summary' ? text.trim() : generatedManuscript(text)
    if (intent === 'chapter') {
      plan.value.draft = suggestion
      plan.value.draftBase = source
      plan.value.stage = 'draft'
    }
    const previousDraft = intent === 'summary' ? baseline : source
    const chapter = props.chapter
    emit('compare', {
      title: intent === 'summary' ? '全章摘要对比' : '完整初稿对比',
      items: [{ id: intent, label: chapter.title, before: previousDraft, after: suggestion, checked: true }],
      apply: ([item]) => {
        if (!item.after.trim() || chapter.text !== source || (intent === 'summary' && chapter.plan.summary !== baseline)) return false
        if (intent === 'chapter' && source.trim() && (sameProse(source, item.after) || !preservesMarkup(source, item.after))) return false
        emit('mutation', revisionSnapshot(chapter))
        if (intent === 'summary') chapter.plan.summary = item.after.trim()
        else { chapter.plan.draft = item.after; emit('approve') }
        return true
      },
    })
  } catch (reason) {
    if (!controller.signal.aborted) error.value = reason.message
  } finally {
    busy.value = ''
  }
}

watch([() => props.ready, () => props.paused], () => {
  if (props.ready && !props.paused && plan.value.stage === 'summary' && !plan.value.summary && !attempted) generate('summary')
}, { immediate: true, flush: 'post' })
onUnmounted(() => controller?.abort())
defineExpose({ cancel: () => controller?.abort() })
</script>

<template>
  <section class="chapter-plan">
    <div class="plan-steps">
      <span :class="{ current: plan.stage === 'summary' }"><FileText :size="15" />章节摘要</span>
      <span :class="{ current: plan.stage === 'draft' }"><PenLine :size="15" />完整初稿</span>
    </div>
    <template v-if="plan.stage === 'summary'">
      <h3>确认下一章的剧情摘要</h3>
      <p class="dialog-description">可以修改 AI 摘要，也可以自己写。确认摘要后再生成整章。</p>
      <label class="field-stack">章节摘要
        <el-input v-model="plan.summary" type="textarea" :rows="7" :disabled="Boolean(busy)" placeholder="写下本章开场、冲突、推进与结尾…" />
      </label>
      <div class="plan-actions">
        <el-button :icon="RotateCw" :disabled="Boolean(busy)" :loading="busy === 'summary'" @click="generate('summary')">{{ plan.summary.trim() ? '重新生成摘要' : '生成摘要' }}</el-button>
        <el-button type="primary" :icon="Sparkles" :disabled="!plan.summary.trim() || Boolean(busy)" :loading="busy === 'chapter'" @click="generate('chapter')">按此摘要生成整章</el-button>
      </div>
    </template>
    <template v-else>
      <h3>审阅完整初稿</h3>
      <details><summary>查看已确认摘要</summary><p>{{ plan.summary }}</p></details>
      <label class="field-stack">完整章节候选
        <el-input v-model="plan.draft" type="textarea" :rows="14" :disabled="Boolean(busy)" />
      </label>
      <details v-if="plan.draft.trim()"><summary>预览整章 Markdown</summary><MarkdownPreview manuscript :source="plan.draft" :divider-style="book.dividerStyle" /></details>
      <p v-if="separatorCount" class="dialog-description">已识别 {{ separatorCount }} 条场景分隔线。</p>
      <div class="plan-actions">
        <el-button :disabled="Boolean(busy)" @click="plan.stage = 'summary'">返回修改摘要</el-button>
        <el-button :icon="RotateCw" :loading="busy === 'chapter'" :disabled="Boolean(busy)" @click="generate('chapter')">Roll 全章</el-button>
        <el-button type="primary" :icon="Check" :disabled="!plan.draft.trim() || Boolean(busy)" @click="emit('approve')">接受为正文</el-button>
        <el-button :disabled="Boolean(busy)" @click="emit('creative', 'review')">打开分屏比稿</el-button>
      </div>
    </template>
    <details v-if="plan.stage === 'summary'"><summary>预览摘要请求资料</summary><pre class="creative-preview">{{ summaryPreview }}</pre></details>
    <details v-if="plan.summary.trim()"><summary>预览整章请求资料</summary><pre class="creative-preview">{{ chapterPreview }}</pre></details>
    <p v-if="error" class="field-error" role="alert">{{ error }}</p>
  </section>
</template>
