<script setup>
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import {
  ChevronLeft, ChevronRight, Eye, FilePenLine, Link2, MessageCircle, PenLine,
  RotateCw, Sparkles, ThumbsDown, ThumbsUp, Waypoints,
} from '@lucide/vue'
import MarkdownPreview from './MarkdownPreview.vue'
import { markupBlocks } from './manuscript-markup.js'
import { sameProse } from './ai-review.js'
import { currentDraft, pendingDrafts } from './revisions.js'

const props = defineProps({
  paragraph: { type: Object, required: true },
  position: { type: Number, required: true },
  selected: Boolean,
  multiSelected: Boolean,
  busy: Boolean,
  notes: { type: Number, default: 0 },
  foreshadows: { type: Number, default: 0 },
})
const emit = defineEmits(['select', 'action', 'variant'])
const tools = [
  { id: 'edit', label: '编辑', icon: PenLine },
  { id: 'prompt', label: '提示', icon: Sparkles },
  { id: 'keep', label: '赞', icon: ThumbsUp, title: '这段保留' },
  { id: 'revise', label: '踩', icon: ThumbsDown, title: '这段需要改' },
  { id: 'notes', label: '注释', icon: MessageCircle },
  { id: 'foreshadow', label: '伏笔', icon: Waypoints },
  { id: 'related', label: '相关', icon: Link2 },
]
const badges = computed(() => [
  { label: '注释', value: props.notes, kind: 'notes' },
  { label: '伏笔', value: props.foreshadows, kind: 'foreshadow' },
  { label: '提示', value: Number(Boolean(props.paragraph.instruction)), kind: 'prompt' },
].filter(({ value }) => value))
const toolbar = ref()
const comparing = ref(false)
const pending = computed(() => pendingDrafts(props.paragraph))
const draft = computed(() => currentDraft(props.paragraph, pending.value))
const edited = computed(() => !sameProse(props.paragraph.text, props.paragraph.original))
const stale = computed(() => draft.value && draft.value.base !== props.paragraph.text)
const pendingPosition = computed(() => pending.value.indexOf(draft.value))
const before = computed(() => draft.value ? props.paragraph.text : props.paragraph.original)
const after = computed(() => draft.value?.text ?? props.paragraph.text)
const formattedCandidate = computed(() => Boolean(draft.value && markupBlocks(draft.value.text).length))
const difference = computed(() => {
  const left = Array.from(before.value)
  const right = Array.from(after.value)
  let start = 0
  let end = 0
  while (start < left.length && start < right.length && left[start] === right[start]) start += 1
  while (end < left.length - start && end < right.length - start && left.at(-end - 1) === right.at(-end - 1)) end += 1
  return {
    prefix: left.slice(0, start).join(''),
    removed: left.slice(start, left.length - end).join(''),
    added: right.slice(start, right.length - end).join(''),
    suffix: left.slice(left.length - end).join(''),
  }
})
let press
let pressTimer
let previousTap = -Infinity

function stopPress() {
  clearTimeout(pressTimer)
  press = undefined
}

function pointerDown(event) {
  if (!event.isPrimary || event.button !== 0 || event.ctrlKey || event.metaKey) return
  stopPress()
  press = { id: event.pointerId, x: event.clientX, y: event.clientY, handled: false }
  pressTimer = setTimeout(() => {
    if (!press) return
    press.handled = true
    previousTap = -Infinity
    emit('action', 'edit')
  }, 650)
}

function pointerMove(event) {
  if (press?.id !== event.pointerId) return
  if (Math.hypot(event.clientX - press.x, event.clientY - press.y) > 12) {
    previousTap = -Infinity
    stopPress()
  }
}

function pointerUp(event) {
  if (press?.id !== event.pointerId) return
  const { handled } = press
  stopPress()
  if (handled) return
  const now = performance.now()
  if (now - previousTap <= 330) {
    previousTap = -Infinity
    emit('action', 'prompt')
    return
  }
  previousTap = now
  emit('select')
}

async function keyboardActivate() {
  previousTap = -Infinity
  emit('select')
  await nextTick()
  toolbar.value?.querySelector('button')?.focus()
}

watch(() => draft.value?.id, () => { comparing.value = false })
onUnmounted(stopPress)
</script>

<template>
  <article class="revision-paragraph" :class="{ selected, edited, 'has-candidate': draft, 'batch-selected': multiSelected }">
    <div v-if="draft || edited" class="paragraph-flags">
      <span v-if="draft" class="revision-label">AI 候选 · 待审批</span>
      <button v-else class="revision-label edited-label" :aria-expanded="comparing" @click="comparing = !comparing">已改 · 查看原文</button>
    </div>
    <button type="button" class="paragraph-hit" :aria-expanded="selected" :aria-label="`第 ${position} 段：${paragraph.text.trim() || '空白开篇'}`"
      @pointerdown="pointerDown" @pointermove="pointerMove" @pointerup="pointerUp"
      @pointercancel="stopPress" @pointerleave="stopPress" @contextmenu.prevent
      @keydown.enter.prevent="keyboardActivate" @keydown.space.prevent="keyboardActivate"
      @click="($event.detail === 0) && keyboardActivate()">
      <span v-if="paragraph.text">{{ paragraph.text }}</span>
      <span v-else class="empty-paragraph">在此写下开篇，或生成一段候选。</span>
    </button>
    <div v-if="draft" class="revision-draft-output">
      <p v-if="draft.reviewNote" class="library-muted">AI 复审 · {{ draft.reviewNote }}</p>
      <MarkdownPreview v-if="formattedCandidate" :source="draft.text" />
      <p v-else>{{ draft.text }}</p>
    </div>
    <div v-if="badges.length" class="paragraph-badges">
      <button v-for="badge in badges" :key="badge.kind" class="paragraph-badge" :class="badge.kind" :title="`${badge.value} 条${badge.label}`" :aria-label="`查看本段${badge.value}条${badge.label}`" @click="emit('action', 'related')">{{ badge.value }}</button>
    </div>
    <Transition name="reveal-tools">
      <div v-if="selected" ref="toolbar" class="paragraph-tools" aria-label="段落工具">
        <button v-for="tool in tools" :key="tool.id" type="button" :title="tool.title || tool.label"
          :aria-pressed="['keep', 'revise'].includes(tool.id) ? paragraph.vote === tool.id : undefined"
          @click="emit('action', tool.id)">
          <component :is="tool.icon" :size="14" />{{ tool.label }}
        </button>
      </div>
    </Transition>
    <div v-if="draft" class="candidate-tools">
      <div class="candidate-variants">
        <button class="icon-button" aria-label="上一个候选" :disabled="pendingPosition <= 0" @click="emit('variant', -1)"><ChevronLeft :size="15" /></button>
        <span>{{ pendingPosition + 1 }} / {{ pending.length }}</span>
        <button class="icon-button" aria-label="下一个候选" :disabled="pendingPosition >= pending.length - 1" @click="emit('variant', 1)"><ChevronRight :size="15" /></button>
      </div>
      <button class="text-action" :aria-expanded="comparing" @click="comparing = !comparing"><Eye :size="14" />对比</button>
      <button class="text-action" @click="emit('action', 'edit-draft')"><FilePenLine :size="14" />修改候选</button>
      <button class="text-action" :disabled="busy" @click="emit('action', 'roll')"><RotateCw :size="14" />Roll</button>
      <button class="text-action" @click="emit('action', 'reject')">拒绝</button>
      <el-button type="primary" size="small" :disabled="stale" @click="emit('action', 'accept')">接受</el-button>
    </div>
    <p v-if="stale" class="field-error">正文已更新。请对比后手动修改候选，或再次 Roll。</p>
    <Transition name="reveal-tools">
      <div v-if="comparing && (draft || edited)" class="paragraph-diff">
        <div><strong>{{ draft ? '当前正文' : '原文' }}</strong><p>{{ difference.prefix }}<del v-if="difference.removed">{{ difference.removed }}</del>{{ difference.suffix }}</p></div>
        <div><strong>{{ draft ? 'AI 候选' : '修改后' }}</strong><p>{{ difference.prefix }}<ins v-if="difference.added">{{ difference.added }}</ins>{{ difference.suffix }}</p></div>
      </div>
    </Transition>
  </article>
</template>
