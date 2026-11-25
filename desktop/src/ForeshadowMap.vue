<script setup>
import { computed, ref } from 'vue'
const props = defineProps({ records: { type: Array, required: true }, chapters: { type: Array, required: true } })
const emit = defineEmits(['jump'])
const selected = ref('')
const columns = computed(() => {
  const ids = new Set(props.records.flatMap(record => record.anchors.map(point => point.anchor.chapterId)))
  return props.chapters.filter(({ chapter }) => ids.has(chapter.id)).map((entry, index) => ({ ...entry, x: 232 + index * 108 }))
})
const rows = computed(() => props.records.slice(0, 26).map((record, index) => ({
  ...record, y: 70 + index * 42,
  points: record.anchors.flatMap(point => {
    const column = columns.value.find(({ chapter }) => chapter.id === point.anchor.chapterId)
    return column ? [{ ...point, x: column.x }] : []
  }).sort((a, b) => a.x - b.x),
})))
const active = computed(() => rows.value.find(item => item.id === selected.value))
const size = computed(() => [Math.max(670, 240 + columns.value.length * 108), Math.max(114, 62 + rows.value.length * 42)])
</script>

<template>
  <details v-if="records.length" class="foreshadow-map">
    <summary>伏笔关系图</summary>
    <div class="foreshadow-map-scroll">
      <svg :viewBox="`0 0 ${size[0]} ${size[1]}`" :style="{ width: size[0] + 'px' }" class="story-graph" role="group" aria-label="伏笔与章节关系">
        <g v-for="column in columns" :key="column.chapter.id">
          <text :x="column.x" y="25" text-anchor="middle">{{ column.label }}</text>
          <path :d="`M ${column.x} 34 V ${size[1] - 8}`" class="foreshadow-grid-line" />
        </g>
        <g v-for="row in rows" :key="row.id" role="button" tabindex="0" :aria-label="row.title" :aria-pressed="selected === row.id"
          :class="{ 'foreshadow-resolved': row.status === 'recovered' }" @click="selected = row.id" @keydown.enter.prevent="selected = row.id">
          <text x="18" :y="row.y + 4">{{ row.title.slice(0, 13) }}</text>
          <path :d="`M 175 ${row.y} H ${row.points.at(-1)?.x || 187}`" />
          <circle v-for="point in row.points" :key="point.id" :cx="point.x" :cy="row.y" r="6"><title>{{ point.role }}</title></circle>
        </g>
      </svg>
    </div>
    <div v-if="active" class="resource-record">
      <strong>{{ active.title }}</strong><p>{{ active.description }}</p>
      <button v-for="point in active.points" :key="point.id" type="button" class="text-action" @click="emit('jump', point.anchor)">{{ point.role }} · 跳回原文</button>
    </div>
  </details>
</template>
