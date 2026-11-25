<script setup>
import { computed, ref } from 'vue'
import ForeshadowMap from './ForeshadowMap.vue'
const props = defineProps({ book: { type: Object, required: true }, chapters: { type: Array, required: true }, backfill: { type: Object, required: true } })
const emit = defineEmits(['jump', 'next', 'backfill', 'cancel-backfill', 'confirm-link'])
const entries = computed(() => props.chapters.filter(({ chapter }) => chapter.handoff))
const missing = computed(() => props.chapters.filter(({ chapter }) => chapter.complete && !chapter.handoff?.archiveVersion && chapter.text.trim()).length)
const chapterId = ref('')
const entry = computed(() => entries.value.find(({ chapter }) => chapter.id === (chapterId.value || props.book.activeId)) || entries.value.at(-1))
const handoff = computed(() => entry.value?.chapter.handoff)
const pendingLinks = computed(() => (handoff.value?.links || []).flatMap((link, index) => link.confirmed ? [] : [{
  ...link, index, source: props.book.research.events.find(event => event.id === link.from)?.title || link.from,
}]))
const openThreads = computed(() => props.book.research.foreshadows.filter(item => item.status !== 'recovered'))
const names = computed(() => [...new Set((handoff.value?.relations || []).flatMap(item => [item.from, item.to]))].slice(0, 12))
const nodes = computed(() => names.value.map((name, index) => {
  const angle = 2 * Math.PI * index / names.value.length - Math.PI / 2
  return { name, x: 300 + 218 * Math.cos(angle), y: 158 + 117 * Math.sin(angle) }
}))
const edges = computed(() => (handoff.value?.relations || []).flatMap(item => {
  const a = nodes.value.find(node => node.name === item.from)
  const b = nodes.value.find(node => node.name === item.to)
  return a && b ? [{ ...item, a, b }] : []
}))
const focus = ref('')
const visibleEdges = computed(() => (handoff.value?.relations || []).filter(item => !focus.value || item.from === focus.value || item.to === focus.value))
</script>
<template>
  <section class="story-desk">
    <div class="resource-heading"><h2>剧情与人物</h2><span>{{ entries.length }} 章已交接 · {{ openThreads.length }} 条未回收</span></div>
    <div v-if="missing || backfill.running" class="resource-record resource-record-actions">
      <span v-if="backfill.running" role="status">已补齐 {{ backfill.done }} / {{ backfill.total }} 章 · 待重试 {{ backfill.failures }}</span>
      <el-button v-if="backfill.running" @click="emit('cancel-backfill')">停止补齐</el-button>
      <el-button v-else @click="emit('backfill')">为 {{ missing }} 个旧章补齐交接</el-button>
    </div>
    <article v-if="pendingLinks.length" class="resource-record">
      <strong>待确认的因果关系</strong>
      <div v-for="link in pendingLinks" :key="link.index">
        <p>{{ link.source }} → {{ link.to }} · {{ link.label }}</p>
        <p>{{ link.quote }}</p>
        <el-button size="small" @click="emit('confirm-link', entry.chapter.id, link.index)">确认因果</el-button>
      </div>
    </article>
    <div v-if="entries.length" class="story-layout">
      <nav class="story-timeline" aria-label="历章剧情">
        <button v-for="item in entries" :key="item.chapter.id" type="button" :aria-pressed="entry.chapter.id === item.chapter.id" @click="chapterId = item.chapter.id; focus = ''">
          <small>{{ item.label }}</small><strong>{{ item.chapter.title }}</strong>
        </button>
      </nav>
      <div class="story-inspector">
        <article class="resource-record"><div class="resource-record-title"><strong>{{ entry.chapter.title }}</strong><button class="text-action" @click="emit('jump', { chapterId: entry.chapter.id })">打开章节</button></div><p>{{ handoff.recap }}</p></article>
        <article class="resource-record handoff-bridge"><strong>下一章铺垫</strong><p>{{ handoff.bridge }}</p></article>
        <div v-if="handoff.directions?.length" class="library-record-grid">
          <article v-for="direction in handoff.directions" :key="direction.title" class="resource-record">
            <strong>{{ direction.title }}</strong><p>{{ direction.detail }}</p>
            <el-button size="small" @click="emit('next', entry.chapter.id, direction)">以此方向新建下一章</el-button>
          </article>
        </div>
        <h3 v-if="nodes.length">人物关系</h3>
        <svg v-if="nodes.length" viewBox="0 0 600 320" class="story-graph" role="group" aria-label="本章人物关系">
          <path v-for="(edge, index) in edges" :key="index" :d="`M ${edge.a.x} ${edge.a.y} Q 300 166 ${edge.b.x} ${edge.b.y}`"
            fill="none" :class="{ faded: focus && edge.from !== focus && edge.to !== focus }" />
          <g v-for="node in nodes" :key="node.name" :transform="`translate(${node.x} ${node.y})`" role="button" tabindex="0" :aria-label="node.name" :aria-pressed="focus === node.name"
            @click="focus = focus === node.name ? '' : node.name" @keydown.enter.prevent="focus = focus === node.name ? '' : node.name">
            <circle r="33" /><circle r="27" /><text text-anchor="middle" dy=".35em">{{ node.name.slice(0, 6) }}</text>
          </g>
        </svg>
        <article v-for="(item, index) in visibleEdges" :key="index" class="resource-record"><strong>{{ item.from }} · {{ item.to }} · {{ item.label }}</strong><p>{{ item.quote }}</p></article>
        <h3 v-if="handoff.threads?.length">待跟进线索</h3>
        <article v-for="item in handoff.threads" :key="item.title + item.quote" class="resource-record"><strong>{{ item.title }}</strong><p>{{ item.note }}</p><blockquote>{{ item.quote }}</blockquote></article>
      </div>
    </div>
    <p v-else class="resource-empty">章节定稿后，剧情概括、下一章铺垫和人物关系会汇集到这里。</p>
    <h3 v-if="openThreads.length">未回收伏笔</h3>
    <ForeshadowMap :records="openThreads" :chapters="chapters" @jump="emit('jump', $event)" />
    <div class="library-record-grid"><article v-for="item in openThreads" :key="item.id" class="resource-record"><strong>{{ item.title }}</strong><p>{{ item.description }}</p>
      <button v-for="point in item.anchors" :key="point.id" type="button" class="text-action" @click="emit('jump', point.anchor)">{{ point.role }} · 跳到原文</button>
    </article></div>
  </section>
</template>
