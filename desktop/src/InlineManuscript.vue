<script setup>
import { computed, nextTick, ref, shallowRef } from 'vue'
import MarkdownPreview from './MarkdownPreview.vue'
import SceneDivider from './SceneDivider.vue'
import { editorDividerBlocks } from './scene-dividers.js'

const props = defineProps({
  source: { type: String, required: true }, blocks: { type: Array, required: true },
  label: { type: String, required: true }, dividerStyle: { type: String, default: 'ornament' }, manuscript: Boolean,
})
const emit = defineEmits(['edit'])
const root = ref()
const locked = shallowRef(null)
const fields = new Map()
let remembered
let inputRevision = 0

const parts = computed(() => {
  const lock = locked.value
  const blocks = lock ? [...props.blocks.filter(block => block.end <= lock.start || block.start >= lock.end), lock]
    .sort((a, b) => a.start - b.start) : props.blocks
  const result = []
  let cursor = 0
  let plainIndex = 0
  let markupIndex = 0
  function addPlain(end, trailing = false) {
    const text = props.source.slice(cursor, end)
    result.push({ key: `plain-${plainIndex++}`, start: cursor, end, text,
      trailing, scenes: props.manuscript ? editorDividerBlocks(text) : [] })
  }
  for (const block of blocks) {
    if (block.start > cursor) addPlain(block.start)
    if (block.start < cursor) continue
    result.push({ ...block, key: block === lock ? lock.key : `markup-${markupIndex}`,
      text: props.source.slice(block.start, block.end), raw: block === lock })
    markupIndex += 1
    cursor = block.end
  }
  if (cursor < props.source.length || !result.length) addPlain(props.source.length)
  else if (!lock || lock.end < props.source.length) addPlain(cursor, true)
  return result
})

function bindField(key, element) {
  if (element) fields.set(key, element)
  else fields.delete(key)
}

function owns(element) {
  return Boolean(element?.matches('textarea') && root.value?.contains(element))
}

function localSelection(part, element) {
  function offset(index) {
    // Native textareas normalize CRLF to LF.
    if (!part.text.includes('\r')) return index
    let position = 0
    for (let n = 0; n < index; n += 1) {
      position += part.text[position] === '\r' && part.text[position + 1] === '\n' ? 2 : 1
    }
    return position
  }
  return [part.start + offset(element.selectionStart), part.start + offset(element.selectionEnd)]
}

function range() {
  const element = document.activeElement
  const part = parts.value.find(item => fields.get(item.key) === element)
  return part ? localSelection(part, element) : remembered || [props.source.length, props.source.length]
}

function partAt(start, end = start, afterBlock = false) {
  const list = parts.value
  if (afterBlock && start === end) {
    const previous = list.find(part => part.token && part.end === start)
    if (previous) return previous.type === 'code' || afterBlock === 'paste' ? list[list.indexOf(previous) + 1] || previous : previous
  }
  return list.find(part => start >= part.start && end <= part.end && (part.end > start || part === list.at(-1))) ||
    [...list].reverse().find(part => start >= part.start && start <= part.end) || list.at(-1)
}

async function focusRange(start, end = start, afterBlock = false) {
  const part = partAt(start, end, afterBlock)
  if (!part) return
  if (part.token && !part.raw) locked.value = { ...part, key: 'active-markup' }
  await nextTick()
  const field = fields.get(part.raw || part.token ? 'active-markup' : part.key)
  if (!field) return
  const position = index => part.text.slice(0, Math.max(0, index - part.start)).replace(/\r\n?/g, '\n').length
  field.focus({ preventScroll: true })
  field.setSelectionRange(position(start), position(end))
}

function blur(part, event) {
  remembered = localSelection(part, event.target)
  if (part.raw && locked.value?.key === part.key) locked.value = null
}

function editBlock(part) {
  focusRange(part.start)
}

function clickMarkup(part, event) {
  if (event.target.closest('button, a, input') || document.getSelection()?.toString()) return
  editBlock(part)
}

async function change(part, event, inputType) {
  if (event.isComposing) return
  const field = event.target
  const newline = part.text.includes('\r\n') ? '\r\n' : '\n'
  const inserted = field.value.replace(/\n/g, newline)
  const previous = parts.value[parts.value.indexOf(part) - 1]
  const prefix = previous?.token && previous.type !== 'paragraph' && (part.trailing || /^\r?\n/u.test(part.text)) &&
    inserted && !/^[\r\n]/u.test(inserted) ? newline : ''
  const text = prefix + inserted
  const oldStart = part.start + prefix.length
  const position = index => oldStart + field.value.slice(0, index).replace(/\n/g, newline).length
  const start = position(field.selectionStart)
  const end = position(field.selectionEnd)
  remembered = [start, end]
  if (part.raw) locked.value = { ...locked.value, end: part.start + text.length }
  const revision = ++inputRevision
  emit('edit', props.source.slice(0, part.start) + text + props.source.slice(part.end), inputType)
  await nextTick()
  if (revision !== inputRevision || part.raw) return
  const sameField = parts.value.find(item => item.key === part.key && !item.token)
  if (field.isConnected && sameField && start >= sameField.start && end <= sameField.end) return
  const afterBlock = inputType === 'insertFromPaste' || event.data?.includes('\n') ? 'paste' : true
  const current = partAt(start, end, afterBlock)
  if (current && (current.key !== part.key || !field.isConnected)) await focusRange(start, end, afterBlock)
}

function move(part, event) {
  if (event.isComposing || event.shiftKey || event.ctrlKey || event.metaKey || event.altKey) return
  const field = event.target
  if (event.key === 'Escape' && part.raw) {
    event.stopPropagation()
    field.blur()
    return
  }
  if (field.selectionStart !== field.selectionEnd) return
  const back = ['ArrowLeft', 'ArrowUp'].includes(event.key) && field.selectionStart === 0
  const forward = ['ArrowRight', 'ArrowDown'].includes(event.key) && field.selectionEnd === field.value.length
  if (!back && !forward) return
  const adjacent = parts.value[parts.value.indexOf(part) + (back ? -1 : 1)]
  if (!adjacent) return
  event.preventDefault()
  field.blur()
  const target = back && adjacent.end > adjacent.start ? adjacent.end - Number(Boolean(adjacent.token)) : adjacent.start
  focusRange(target)
}

defineExpose({ owns, range, focusRange, editingBlock: computed(() => Boolean(locked.value)) })
</script>

<template>
  <div ref="root" class="inline-manuscript" :aria-label="label">
    <template v-for="part in parts" :key="part.key">
      <div v-if="!part.token || part.raw" class="auto-editor inline-manuscript-field"
        :class="{ 'inline-manuscript-gap': !part.text.trim() && !part.trailing, 'inline-source-block': part.raw, 'inline-code-editor': part.raw && part.type === 'code' }">
        <div class="editor-sizing" aria-hidden="true">{{ part.text }}&#8203;</div>
        <textarea :ref="element => bindField(part.key, element)" :value="part.text" class="manuscript-editor" rows="1" spellcheck="false"
          :aria-label="part.raw ? 'Markdown 源码' : `${label}，从第 ${part.start + 1} 字开始`"
          @input="change(part, $event, $event.inputType)" @compositionend="change(part, $event, 'composition')"
          @blur="blur(part, $event)" @keydown="move(part, $event)"></textarea>
        <div v-if="part.scenes?.length" aria-hidden="true" class="editor-divider-overlay">
          <div v-for="(line, index) in part.scenes" :key="index" class="editor-overlay-block">{{ line.text }}&#8203;<SceneDivider v-if="line.divider" class="editor-scene-visual" :variant="dividerStyle" /></div>
        </div>
      </div>
      <div v-else class="inline-markdown-block" :class="`inline-markdown-${part.type}`"
        @click="clickMarkup(part, $event)">
        <MarkdownPreview :source="part.text" :token="part.token" :manuscript="manuscript" :divider-style="dividerStyle"
          editable-code @edit-code="editBlock(part)" />
        <button v-if="part.type !== 'code'" type="button" class="inline-edit-markup" aria-label="编辑 Markdown" @click="editBlock(part)">编辑</button>
      </div>
    </template>
  </div>
</template>
