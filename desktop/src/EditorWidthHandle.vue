<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({ target: { type: Object, default: null } })
const width = defineModel({ type: Number, required: true })
const emit = defineEmits(['commit'])
const minimum = 280
const maximum = ref(minimum)
const actual = computed(() => Math.min(width.value, maximum.value))
const dragging = ref(false)
let startX = 0
let startWidth = 0
let lastX = 0

watch(() => props.target, (field, _previous, cleanup) => {
  if (!field) return
  const observer = new ResizeObserver(([entry]) => {
    maximum.value = Math.max(minimum, Math.min(1440, Math.floor(entry.contentRect.width)))
  })
  observer.observe(field)
  cleanup(() => observer.disconnect())
}, { flush: 'post' })

function resize(value) {
  const next = Math.max(minimum, Math.min(maximum.value, value))
  width.value = next
  return next
}

function start(event) {
  if (!event.isPrimary || event.button !== 0) return
  startX = event.clientX
  lastX = startX
  startWidth = actual.value
  dragging.value = true
  event.currentTarget.setPointerCapture(event.pointerId)
}

function move(event) {
  if (!dragging.value || !event.isPrimary || event.clientX === lastX) return
  lastX = event.clientX
  resize(startWidth + Math.round(2 * (lastX - startX)))
}

function finish() {
  dragging.value = false
  emit('commit', width.value)
}

function reset() {
  width.value = 754
  emit('commit', 754)
}

function keys(event) {
  const step = event.shiftKey ? 80 : 20
  const sizes = { ArrowLeft: actual.value - step, ArrowRight: actual.value + step, Home: minimum, End: maximum.value }
  if (!Object.hasOwn(sizes, event.key)) return
  event.preventDefault()
  emit('commit', resize(sizes[event.key]))
}
</script>

<template>
  <div class="editor-width-rail">
    <button v-if="maximum > minimum + 15" class="editor-width-handle" :class="{ dragging }" type="button"
      role="slider" aria-label="正文宽度" aria-controls="manuscript-paper" aria-orientation="horizontal"
      :aria-valuemin="minimum" :aria-valuemax="maximum" :aria-valuenow="actual" :aria-valuetext="`${actual} 像素`"
      title="拖动调整正文宽度，双击恢复默认" @pointerdown="start" @pointermove="move" @lostpointercapture="finish"
      @dblclick="reset" @keydown="keys"></button>
  </div>
</template>
