<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'

const props = defineProps({ target: { type: Object, default: null } })
const surface = ref()
const handle = ref()
const top = ref(0)
const maximum = ref(0)
const track = ref(0)
const thumb = ref(34)
const dragging = ref(false)
let frame = 0
let initialY = 0
let initialTop = 0

const travel = computed(() => Math.max(0, track.value - thumb.value))
const position = computed(() => maximum.value ? travel.value * top.value / maximum.value : 0)

function schedule() {
  if (frame) return
  frame = requestAnimationFrame(() => {
    frame = 0
    const field = props.target
    if (!field || !surface.value) return
    const visible = field.clientHeight
    const height = field.scrollHeight
    track.value = surface.value.clientHeight
    maximum.value = Math.max(0, height - visible)
    top.value = field.scrollTop
    thumb.value = Math.min(track.value, Math.max(34, track.value * visible / Math.max(height, 1)))
  })
}

function setPosition(next) {
  if (props.target) props.target.scrollTop = Math.max(0, Math.min(maximum.value, next))
}

function startDrag(event) {
  if (!maximum.value || event.button !== 0) return
  initialY = event.clientY
  initialTop = top.value
  dragging.value = true
  event.currentTarget.setPointerCapture(event.pointerId)
}

function moveDrag(event) {
  if (!dragging.value || !travel.value) return
  setPosition(initialTop + (event.clientY - initialY) * maximum.value / travel.value)
}

function jump(event) {
  if (!travel.value || event.button !== 0) return
  const relative = event.clientY - surface.value.getBoundingClientRect().top - thumb.value / 2
  setPosition(relative * maximum.value / travel.value)
  handle.value?.focus({ preventScroll: true })
}

function keys(event) {
  const page = props.target?.clientHeight || 0
  const increments = { ArrowUp: -44, ArrowDown: 44, PageUp: -page, PageDown: page, Home: -maximum.value, End: maximum.value }
  if (!Object.hasOwn(increments, event.key)) return
  event.preventDefault()
  setPosition(top.value + increments[event.key])
}

watch([() => props.target, surface], ([field, rail], _previous, cleanup) => {
  if (!field || !rail) return
  const resize = new ResizeObserver(schedule)
  resize.observe(field)
  resize.observe(rail)
  if (field.firstElementChild) resize.observe(field.firstElementChild)
  field.addEventListener('scroll', schedule, { passive: true })
  schedule()
  cleanup(() => {
    resize.disconnect()
    field.removeEventListener('scroll', schedule)
  })
}, { flush: 'post' })

onUnmounted(() => cancelAnimationFrame(frame))
</script>

<template>
  <div ref="surface" class="editor-scroll-track" :class="{ 'has-overflow': maximum > 1, dragging }" @pointerdown="jump">
    <button v-if="maximum > 1" ref="handle" type="button" role="scrollbar" class="editor-scroll-handle"
      aria-label="滚动正文" aria-controls="manuscript-scroll" aria-orientation="vertical" aria-valuemin="0"
      :aria-valuemax="Math.round(maximum)" :aria-valuenow="Math.round(top)"
      :style="{ height: thumb + 'px', transform: 'translateY(' + position + 'px)' }"
      @pointerdown.stop="startDrag" @pointermove="moveDrag" @lostpointercapture="dragging = false" @keydown="keys"></button>
  </div>
</template>
