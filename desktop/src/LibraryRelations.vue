<script setup>
import { computed, ref } from 'vue'
import { materialKinds } from './creative-tools.js'

const props = defineProps({ book: { type: Object, required: true }, chapters: { type: Array, required: true } })
const emit = defineEmits(['jump'])
const query = ref('')
const selected = ref('')
const nodes = computed(() => {
  const { cards, assets } = props.book.creative
  return [
    ...cards.map(card => ({ key: 'card:' + card.id, label: card.title, type: materialKinds[card.kind] || '资料卡',
      detail: card.body, anchor: card, links: [['chapter', card.chapterId], ['thread', card.foreshadowId]] })),
    ...assets.map(asset => ({ key: 'asset:' + asset.id, label: asset.title || asset.name, type: '附件', detail: asset.summary,
      anchor: asset, links: [['card', asset.cardId], ['chapter', asset.chapterId]] })),
    ...props.book.research.foreshadows.map(thread => ({ key: 'thread:' + thread.id, label: thread.title, type: '伏笔', detail: thread.description,
      anchor: thread.anchors[0]?.anchor, links: thread.anchors.map(point => ['chapter', point.anchor.chapterId]) })),
    ...props.chapters.map(({ chapter, label }) => ({ key: 'chapter:' + chapter.id, label: chapter.title, type: '章节',
      detail: label, anchor: { chapterId: chapter.id }, links: [] })),
  ]
})
const visible = computed(() => nodes.value.filter(node => (node.label + ' ' + node.type).toLowerCase().includes(query.value.trim().toLowerCase())))
const active = computed(() => visible.value.find(node => node.key === selected.value) || visible.value[0])
const neighbors = computed(() => {
  if (!active.value) return []
  const links = node => node.links.map(pair => pair.join(':'))
  const outward = new Set(links(active.value))
  return nodes.value.filter(node => node.key !== active.value.key && (outward.has(node.key) || links(node).includes(active.value.key)))
})
const positions = computed(() => neighbors.value.slice(0, 12).map((node, index, displayed) => {
  const angle = index * Math.PI * 2 / displayed.length - Math.PI / 2
  return { ...node, x: 360 + 245 * Math.cos(angle), y: 225 + 155 * Math.sin(angle) }
}))
function select(key) {
  if (!visible.value.some(node => node.key === key)) query.value = ''
  selected.value = key
}
</script>

<template>
  <section class="library-workarea library-relations">
    <div class="resource-heading"><h2>资料关系图</h2></div>
    <div class="library-relations-layout">
      <aside>
        <el-input v-model="query" placeholder="搜索节点" aria-label="搜索资料关系节点" clearable />
        <div class="library-relations-list">
          <button v-for="node in visible" :key="node.key" type="button" :aria-pressed="active?.key === node.key" @click="select(node.key)">
            <span>{{ node.label }}</span><small>{{ node.type }}</small>
          </button>
        </div>
      </aside>
      <div v-if="active">
        <svg class="library-relations-graph" viewBox="0 0 720 450" role="group" aria-label="资料关系图">
          <circle cx="360" cy="225" r="105" class="relation-guide" />
          <ellipse cx="360" cy="225" rx="245" ry="155" class="relation-guide" />
          <line v-for="node in positions" :key="node.key" x1="360" y1="225" :x2="node.x" :y2="node.y" class="relation-edge" />
          <g class="relation-center" transform="translate(360 225)">
            <circle r="57" /><title>{{ active.label }}</title>
            <text text-anchor="middle" y="-2">{{ active.label.slice(0, 8) }}</text>
            <text class="relation-kind" text-anchor="middle" y="18">{{ active.type }}</text>
          </g>
          <g v-for="node in positions" :key="node.key" class="relation-node" :transform="`translate(${node.x} ${node.y})`"
            tabindex="0" role="button" :aria-label="node.label" @click="select(node.key)" @keydown.enter.prevent="select(node.key)" @keydown.space.prevent="select(node.key)">
            <circle r="37" /><title>{{ node.label }}</title>
            <text text-anchor="middle" y="-2">{{ node.label.slice(0, 5) }}</text>
            <text class="relation-kind" text-anchor="middle" y="15">{{ node.type }}</text>
          </g>
        </svg>
        <article class="resource-record">
          <strong>{{ active.label }}</strong><p v-if="active.detail">{{ active.detail }}</p>
          <div class="resource-record-actions">
            <button v-if="active.anchor?.chapterId" class="text-action" @click="emit('jump', active.anchor)">定位书稿</button>
            <span v-if="!neighbors.length" class="library-muted">暂无关联</span>
            <button v-for="node in neighbors" :key="node.key" class="text-action" @click="select(node.key)">{{ node.label }}</button>
          </div>
        </article>
      </div>
      <p v-else class="resource-empty">没有匹配的节点。</p>
    </div>
  </section>
</template>

<style scoped>
.library-relations-layout { display: grid; grid-template-columns: minmax(9rem, 15rem) minmax(0, 1fr); gap: 1rem; }
.library-relations-list { max-height: 34rem; overflow: auto; margin-top: .6rem; display: grid; gap: .3rem; }
.library-relations-list button { display: flex; align-items: center; justify-content: space-between; gap: .5rem; padding: .55rem .65rem; border: 1px solid transparent; border-radius: .55rem; color: inherit; background: transparent; text-align: left; }
.library-relations-list button:hover, .library-relations-list button[aria-pressed=true] { background: var(--theme-selection); border-color: var(--accent); }
.library-relations-list small { white-space: nowrap; opacity: .72; }
.library-relations-graph { display: block; width: 100%; border-radius: 1rem; background: radial-gradient(ellipse, var(--theme-tint), transparent 73%); color: inherit; }
.relation-guide { fill: none; stroke: var(--accent); stroke-dasharray: 3 9; opacity: .23; }
.relation-edge { stroke: var(--accent); opacity: .33; }
.relation-center circle { fill: var(--accent); stroke: var(--accent); }
.relation-node circle { fill: var(--theme-surface); stroke: var(--accent); }
.relation-center text { fill: var(--accent-on); }
.relation-node text { fill: currentColor; font-size: 13px; }
.relation-center .relation-kind, .relation-node .relation-kind { font-size: 10px; }
.relation-node { cursor: pointer; outline: none; }
.relation-node:hover circle, .relation-node:focus-visible circle { stroke-width: 3px; }
@media (max-width: 650px) { .library-relations-layout { grid-template-columns: 1fr; }.library-relations-list { max-height: 9rem; } }
</style>
