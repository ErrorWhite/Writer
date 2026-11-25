<script setup>
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { Activity, BookOpen, Database, GitFork, History, Layers3, ListChecks, MessageSquare, Paperclip, Sparkles } from '@lucide/vue'
import { ElMessageBox } from 'element-plus'
import { anchorLabel, sameAnchor, syncParagraphs } from './revisions.js'
import ForeshadowMap from './ForeshadowMap.vue'
import LibraryRelations from './LibraryRelations.vue'
import { storyChronology } from './story-handoff.js'

const props = defineProps({
  book: { type: Object, required: true },
  chapters: { type: Array, required: true },
  anchor: { type: Object, default: null },
  requestedTab: { type: String, default: 'annotations' },
})
const emit = defineEmits(['jump'])
const research = computed(() => props.book.research)
const tab = ref('annotations')
const formMode = ref('')
const roles = ['铺垫', '埋设', '线索', '呼应', '回收']
const form = reactive({})
const query = ref('')
const chapterFilter = ref('')
const statusFilter = ref('')
const selectedEvent = ref('')
const editorForm = ref()
const tabs = [
  { id: 'context', label: '知识与上下文', icon: Database },
  { id: 'world', label: '世界书', icon: BookOpen },
  { id: 'references', label: '拆书与参考', icon: BookOpen },
  { id: 'story', label: '剧情与人物', icon: GitFork },
  { id: 'foreshadows', label: '伏笔', icon: GitFork },
  { id: 'plot', label: '剧情图', icon: Layers3 },
  { id: 'relations', label: '资料关系图', icon: GitFork },
  { id: 'annotations', label: '批注', icon: MessageSquare },
  { id: 'cards', label: '资料卡', icon: BookOpen },
  { id: 'assets', label: '附件', icon: Paperclip },
  { id: 'ai', label: 'AI 整理', icon: Sparkles },
  { id: 'templates', label: '提示词模板', icon: ListChecks },
  { id: 'review', label: '审阅与比稿', icon: ListChecks },
  { id: 'versions', label: '章节版本', icon: History },
  { id: 'usage', label: '使用量', icon: Activity },
]
const chapterMap = computed(() => new Map(props.chapters.map((entry, index) => [entry.chapter.id, { ...entry, index }])))
const chapterTitle = id => chapterMap.value.get(id)?.label || '未定位'
const includesQuery = (...values) => values.some(value => value?.toLowerCase().includes(query.value.trim().toLowerCase()))
const anchors = computed(() => props.chapters.flatMap(({ chapter, label }) =>
  (chapter.paragraphs || []).flatMap((paragraph, index) => paragraph.text.trim() ? [{
    key: paragraph.id,
    anchor: { chapterId: chapter.id, paragraphId: paragraph.id },
    label: `${label} ${chapter.title} · 第 ${index + 1} 段`,
  }] : [])))
const relatedParagraph = computed(() => props.chapters.find(({ chapter }) => chapter.id === props.anchor?.chapterId)
  ?.chapter.paragraphs?.find(({ id }) => id === props.anchor.paragraphId))
const annotations = computed(() => research.value.annotations.filter(({ anchor, kind, text }) => tab.value === 'related'
  ? sameAnchor(anchor, props.anchor) : (!chapterFilter.value || anchor.chapterId === chapterFilter.value) && includesQuery(kind, text)))
const foreshadows = computed(() => research.value.foreshadows.filter(record => tab.value === 'related'
  ? record.anchors.some(({ anchor }) => sameAnchor(anchor, props.anchor))
  : (!statusFilter.value || record.status === statusFilter.value) && includesQuery(record.title, record.description)
    && (!chapterFilter.value || record.anchors.some(({ anchor }) => anchor.chapterId === chapterFilter.value))))
const recovered = computed(() => research.value.foreshadows.filter(record => record.status === 'recovered').length)
const touchedChapters = computed(() => new Set(research.value.foreshadows.flatMap(record =>
  record.anchors.map(({ anchor }) => anchor.chapterId).filter(id => chapterMap.value.has(id)))).size)
function chronologicalAnchors(record) {
  return [...record.anchors].sort((a, b) =>
    (chapterMap.value.get(a.anchor.chapterId)?.index ?? Infinity) - (chapterMap.value.get(b.anchor.chapterId)?.index ?? Infinity) ||
    roles.indexOf(a.role) - roles.indexOf(b.role))
}
const relatedEvents = computed(() => research.value.events.filter(({ anchor }) => sameAnchor(anchor, props.anchor)))
const activeEvent = computed(() => research.value.events.find(event => event.id === selectedEvent.value))

const graphNodes = computed(() => [...research.value.events].sort((a, b) =>
  (chapterMap.value.get(a.anchor.chapterId)?.index ?? Infinity) - (chapterMap.value.get(b.anchor.chapterId)?.index ?? Infinity)
).map((event, index) => ({
  ...event,
  x: 20 + (index % 4) * 191,
  y: 26 + Math.floor(index / 4) * 104,
  hue: ((chapterMap.value.get(event.anchor.chapterId)?.index ?? 0) * 137 + 221) % 360,
})))
const graphNodeMap = computed(() => new Map(graphNodes.value.map(node => [node.id, node])))
const graphEdges = computed(() => [...storyChronology(research.value.events, props.chapters), ...research.value.links].flatMap((link) => {
  const start = graphNodeMap.value.get(link.from)
  const end = graphNodeMap.value.get(link.to)
  if (!start || !end) return []
  if (start.y === end.y) {
    const rightward = end.x > start.x
    const x1 = start.x + (rightward ? 150 : 0)
    const x2 = end.x + (rightward ? -5 : 155)
    const y = start.y + 22
    return [{ ...link, path: `M ${x1} ${y} C ${x1} ${y - 34}, ${x2} ${y - 34}, ${x2} ${y}` }]
  }
  const downward = end.y > start.y
  const x1 = start.x + 75
  const x2 = end.x + 75
  const y1 = start.y + (downward ? 56 : 0)
  const y2 = end.y + (downward ? -6 : 62)
  const center = (y1 + y2) / 2
  return [{ ...link, path: `M ${x1} ${y1} C ${x1} ${center}, ${x2} ${center}, ${x2} ${y2}` }]
}))
const graphHeight = computed(() => Math.max(112, Math.ceil(graphNodes.value.length / 4) * 104))

function startForm(mode, record = {}) {
  props.chapters.forEach(({ chapter }) => syncParagraphs(chapter))
  const defaultAnchor = anchors.value.find(({ anchor }) => sameAnchor(anchor, record.anchor || props.anchor))
    ?? anchors.value.find(({ anchor }) => anchor.chapterId === props.book.activeId) ?? anchors.value[0]
  Object.assign(form, {
    id: record.id || '', title: record.title || '', description: record.description || '',
    kind: record.kind || '作者工作注 · 认知边界', role: '铺垫', status: record.status || 'open',
    anchor: defaultAnchor?.key || '', from: '', to: '', relation: '导致',
  })
  if (mode === 'annotation') form.description = record.text || ''
  formMode.value = mode
  nextTick(() => editorForm.value?.$el?.querySelector('input, textarea')?.focus())
}

function addAnchor(record, anchor, role) {
  if (!anchor) return
  if (!record.anchors.some((item) => sameAnchor(item.anchor, anchor) && item.role === role)) {
    record.anchors.push({ id: crypto.randomUUID(), anchor, role })
  }
  if (role === '回收') record.status = 'recovered'
}

function submit() {
  const anchor = anchors.value.find(({ key }) => key === form.anchor)?.anchor
  const id = crypto.randomUUID()
  const title = form.title.trim()
  const description = form.description.trim()
  const mode = formMode.value
  if (mode === 'annotation') {
    if (!anchor || !description) return
    const existing = research.value.annotations.find(item => item.id === form.id)
    const values = { anchor, kind: form.kind.trim() || '作者工作注', text: description }
    if (existing) Object.assign(existing, values)
    else research.value.annotations.push({ id, ...values })
  } else if (mode === 'foreshadow') {
    if (!title) return
    const existing = research.value.foreshadows.find((item) => item.id === form.id)
    if (existing) {
      Object.assign(existing, { title, description, status: form.status })
    } else {
      const record = { id, title, description, status: form.status, anchors: [] }
      addAnchor(record, anchor, form.role)
      research.value.foreshadows.push(record)
    }
  } else if (mode === 'anchor') {
    const target = research.value.foreshadows.find((item) => item.id === form.id)
    if (!target || !anchor) return
    addAnchor(target, anchor, form.role)
  } else if (mode === 'event') {
    if (!title || !anchor) return
    const existing = research.value.events.find(item => item.id === form.id)
    if (existing) Object.assign(existing, { title, description, anchor })
    else research.value.events.push({ id, title, description, anchor })
    selectedEvent.value = existing?.id || id
  } else if (mode === 'link') {
    if (!form.from || !form.to || form.from === form.to) return
    if (!research.value.links.some((link) => link.from === form.from && link.to === form.to && link.text === (form.relation.trim() || '导致'))) {
      research.value.links.push({ id, from: form.from, to: form.to, text: form.relation.trim() || '导致' })
    }
  }
  formMode.value = ''
}

function changeTab(value) {
  tab.value = value
  formMode.value = ''
  query.value = ''
  chapterFilter.value = ''
  statusFilter.value = ''
}

function jump(anchor) {
  if (anchorLabel(anchor, props.chapters) === '段落已删除') return
  emit('jump', anchor)
}

async function removeRecord(collection, record, label) {
  try {
    await ElMessageBox.confirm(`删除这条${label}？`, `删除${label}`, { confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch { return }
  research.value[collection] = research.value[collection].filter(({ id }) => id !== record.id)
  if (collection === 'events') {
    research.value.links = research.value.links.filter(link => link.from !== record.id && link.to !== record.id)
    selectedEvent.value = ''
  }
}

const eventTitle = (id) => research.value.events.find((event) => event.id === id)?.title || '事件已删除'
const shortTitle = (title) => Array.from(title).length > 11 ? Array.from(title).slice(0, 10).join('') + '…' : title
const formTitles = { annotation: '添加注释', foreshadow: '伏笔档案', anchor: '添加锚点', event: '添加事件', link: '连接因果' }

watch(() => props.requestedTab, changeTab, { immediate: true })
</script>

<template>
  <main class="research-library" aria-labelledby="library-title">
    <div class="page-frame library-frame">
    <header class="page-heading library-header">
      <h1 id="library-title">创作工作台</h1>
      <div class="library-header-actions"><span>{{ book.title }}</span></div>
    </header>
    <div class="workbench-columns">
    <nav class="resource-tabs library-tabs" aria-label="工作台导航">
      <button v-for="item in tabs" :key="item.id" :aria-pressed="tab === item.id" @click="changeTab(item.id)">
        <component :is="item.icon" :size="16" aria-hidden="true" />{{ item.label }}
      </button>
      <button v-if="anchor" :aria-pressed="tab === 'related'" @click="changeTab('related')">本段相关</button>
    </nav>
    <el-select class="workbench-mobile-navigation" :model-value="tab" aria-label="工作台导航" @change="changeTab">
      <el-option v-for="item in tabs" :key="item.id" :label="item.label" :value="item.id" />
      <el-option v-if="anchor" label="本段相关" value="related" />
    </el-select>

    <div class="workbench-content">
    <slot v-if="tab === 'cards'" name="cards" />
    <slot v-else-if="tab === 'assets'" name="assets" />
    <slot v-else-if="tab === 'ai'" name="ai" />
    <slot v-else-if="tab === 'world'" name="world" />
    <slot v-else-if="tab === 'context'" name="context" />
    <slot v-else-if="tab === 'usage'" name="usage" />
    <slot v-else-if="tab === 'story'" name="story" />
    <LibraryRelations v-else-if="tab === 'relations'" :book="book" :chapters="chapters" @jump="emit('jump', $event)" />
    <slot v-else-if="['references', 'templates', 'review', 'versions'].includes(tab)" name="creative" :tab="tab" />
    <div v-else class="library-workarea">
      <section v-if="tab === 'annotations' || tab === 'related'">
        <div v-if="tab === 'annotations'" class="resource-heading">
          <h2>段落批注 <small>{{ research.annotations.length }}</small></h2>
          <el-button type="primary" :disabled="!chapters.some(({ chapter }) => chapter.text.trim())" @click="startForm('annotation')">添加批注</el-button>
        </div>
        <div v-if="tab === 'annotations'" class="library-filter">
          <el-input v-model="query" placeholder="搜索批注" aria-label="搜索批注" clearable />
          <el-select v-model="chapterFilter" aria-label="批注所在章"><el-option label="全部章节" value="" /><el-option v-for="entry in chapters" :key="entry.chapter.id" :label="entry.label + ' ' + entry.chapter.title" :value="entry.chapter.id" /></el-select>
        </div>
        <p v-if="tab === 'related' && relatedParagraph?.instruction" class="resource-note"><strong>逐段提示</strong><br />{{ relatedParagraph.instruction }}</p>
        <div class="library-record-grid">
        <article v-for="annotation in annotations" :key="annotation.id" class="resource-record">
          <strong>{{ annotation.kind }}</strong><p>{{ annotation.text }}</p>
          <div class="resource-record-actions">
            <button class="text-action" @click="jump(annotation.anchor)">{{ anchorLabel(annotation.anchor, chapters) }}</button>
            <button class="text-action" @click="startForm('annotation', annotation)">编辑</button>
            <button class="text-action danger" @click="removeRecord('annotations', annotation, '批注')">删除</button>
          </div>
        </article>
        </div>
        <p v-if="!annotations.length && tab === 'annotations'" class="resource-empty">{{ research.annotations.length ? '没有匹配的批注。' : '还没有批注。' }}</p>
      </section>

      <section v-if="tab === 'foreshadows' || tab === 'related'">
        <div v-if="tab === 'foreshadows'" class="resource-heading">
          <h2>伏笔档案</h2>
          <el-button type="primary" @click="startForm('foreshadow')">添加伏笔</el-button>
        </div>
        <template v-if="tab === 'foreshadows'">
          <dl class="library-stats">
            <div><dt>未回收</dt><dd>{{ research.foreshadows.length - recovered }}</dd></div>
            <div><dt>已回收</dt><dd>{{ recovered }}</dd></div>
            <div><dt>涉及章节</dt><dd>{{ touchedChapters }}</dd></div>
          </dl>
          <div class="library-filter">
            <el-input v-model="query" aria-label="搜索伏笔" placeholder="搜索伏笔标题或说明" clearable />
            <el-select v-model="statusFilter" aria-label="回收状态"><el-option label="全部状态" value="" /><el-option label="未回收" value="open" /><el-option label="已回收" value="recovered" /></el-select>
            <el-select v-model="chapterFilter" aria-label="伏笔所在章"><el-option label="全部章节" value="" /><el-option v-for="entry in chapters" :key="entry.chapter.id" :label="entry.label + ' ' + entry.chapter.title" :value="entry.chapter.id" /></el-select>
          </div>
          <ForeshadowMap :records="foreshadows" :chapters="chapters" @jump="jump" />
        </template>
        <div class="library-record-grid">
        <article v-for="record in foreshadows" :key="record.id" class="resource-record foreshadow-record">
          <div class="resource-record-title"><strong>{{ record.title }}</strong><span class="revision-label" :class="{ approved: record.status === 'recovered' }">{{ record.status === 'recovered' ? '已回收' : '未回收' }}</span></div>
          <p v-if="record.description">{{ record.description }}</p>
          <ol v-if="record.anchors.length" class="library-anchor-track" aria-label="伏笔锚点时间线">
            <li v-for="point in chronologicalAnchors(record)" :key="point.id" :class="{ recovered: point.role === '回收' }">
              <button class="text-action" :title="anchorLabel(point.anchor, chapters)" :disabled="anchorLabel(point.anchor, chapters) === '段落已删除'" @click="jump(point.anchor)">
                <strong>{{ point.role }}</strong><small>{{ chapterTitle(point.anchor.chapterId) }}</small>
              </button>
            </li>
          </ol>
          <p v-else class="library-muted">尚未定位段落。</p>
          <div v-if="tab === 'foreshadows'" class="resource-record-actions">
            <button class="text-action" @click="startForm('foreshadow', record)">编辑伏笔</button>
            <button class="text-action" :disabled="!chapters.some(({ chapter }) => chapter.text.trim())" @click="startForm('anchor', record)">添加锚点</button>
            <button class="text-action danger" @click="removeRecord('foreshadows', record, '伏笔')">删除</button>
          </div>
        </article>
        </div>
        <p v-if="!foreshadows.length && tab === 'foreshadows'" class="resource-empty">{{ research.foreshadows.length ? '没有匹配的伏笔。' : '还没有伏笔。' }}</p>
      </section>

      <section v-if="tab === 'plot'">
        <div class="resource-heading">
          <h2>剧情因果图</h2>
          <div class="plot-actions">
            <el-button type="primary" :disabled="!chapters.some(({ chapter }) => chapter.text.trim())" @click="startForm('event')">添加事件</el-button>
            <el-button :disabled="research.events.length < 2" @click="startForm('link')">连接因果</el-button>
          </div>
        </div>
        <p class="library-muted">箭头是记录过的因果，未确认的只保留原说明。虚线连接相邻的归档章节。</p>
        <div v-if="graphNodes.length" class="graph-scroll library-graph">
          <svg :viewBox="`0 0 750 ${graphHeight}`" role="group" aria-label="剧情因果图">
            <defs><marker id="plot-arrow" markerWidth="7" markerHeight="7" refX="6" refY="3.5" orient="auto"><path d="M 0 0 L 7 3.5 L 0 7 Z" fill="var(--accent)" /></marker></defs>
            <path v-for="edge in graphEdges" :key="edge.id" :d="edge.path" fill="none" stroke="var(--accent)" stroke-width="1.8"
              :stroke-dasharray="edge.chronology ? '5 5' : undefined" :opacity="edge.chronology ? .55 : 1"
              :marker-end="edge.chronology ? undefined : 'url(#plot-arrow)'" />
            <g v-for="node in graphNodes" :key="node.id" :transform="`translate(${node.x} ${node.y})`" class="plot-node"
              role="button" tabindex="0" :aria-label="node.title" :aria-pressed="selectedEvent === node.id"
              :style="{ '--node-hue': node.hue }" @click="selectedEvent = node.id" @keydown.enter.prevent="selectedEvent = node.id" @keydown.space.prevent="selectedEvent = node.id">
              <title>{{ node.title }}</title>
              <rect width="150" height="56" rx="9" />
              <text x="75" y="22" dominant-baseline="middle" text-anchor="middle">{{ shortTitle(node.title) }}</text>
              <text x="75" y="42" class="plot-node-chapter" dominant-baseline="middle" text-anchor="middle">{{ chapterTitle(node.anchor.chapterId) }}</text>
            </g>
          </svg>
        </div>
        <p v-else class="resource-empty">添加事件后可在这里连接因果。</p>
        <article v-if="activeEvent" class="resource-record">
          <strong>{{ activeEvent.title }}</strong><p v-if="activeEvent.description">{{ activeEvent.description }}</p>
          <p v-if="activeEvent.quote" class="resource-note">定稿摘录 · {{ activeEvent.quote }}</p>
          <div class="resource-record-actions">
            <button class="text-action" @click="jump(activeEvent.anchor)">{{ anchorLabel(activeEvent.anchor, chapters) }}</button>
            <button class="text-action" @click="startForm('event', activeEvent)">编辑</button>
            <button class="text-action danger" @click="removeRecord('events', activeEvent, '事件')">删除</button>
          </div>
        </article>
        <h3 class="relations-heading">关系清单</h3>
        <ul v-if="research.links.length" class="relations-list"><li v-for="relation in research.links" :key="relation.id">
          <button class="text-action" @click="selectedEvent = relation.from">{{ eventTitle(relation.from) }}</button> → <button class="text-action" @click="selectedEvent = relation.to">{{ eventTitle(relation.to) }}</button>（{{ relation.text }}）
          <div v-if="relation.quote" class="library-muted">{{ relation.quote }}</div>
          <button class="text-action danger" @click="removeRecord('links', relation, '因果关系')">删除</button>
        </li></ul>
        <p v-else class="resource-empty">还没有记录的因果关系。</p>
      </section>

      <section v-if="tab === 'related'">
        <article v-for="event in relatedEvents" :key="event.id" class="resource-record"><strong>{{ event.title }}</strong><p>{{ event.description }}</p><button class="text-action" @click="changeTab('plot')">查看剧情图</button></article>
        <p v-if="!relatedEvents.length && !foreshadows.length && !annotations.length && !relatedParagraph?.instruction" class="resource-empty">此段还没有提示、注释、伏笔或事件。</p>
      </section>

      <el-form v-if="formMode" :key="formMode + form.id" ref="editorForm" class="resource-form library-editor" label-position="top" @submit.prevent="submit">
        <h3>{{ formTitles[formMode] }}</h3>
        <el-form-item v-if="['foreshadow', 'event'].includes(formMode)" label="标题"><el-input v-model="form.title" maxlength="80" /></el-form-item>
        <el-form-item v-if="formMode === 'annotation'" label="注释类别"><el-input v-model="form.kind" maxlength="80" /></el-form-item>
        <el-form-item v-if="['foreshadow', 'event', 'annotation'].includes(formMode)" :label="formMode === 'annotation' ? '注释正文' : '说明'">
          <el-input v-model="form.description" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item v-if="formMode === 'foreshadow'" label="状态">
          <el-radio-group v-model="form.status"><el-radio-button value="open">未回收</el-radio-button><el-radio-button value="recovered">已回收</el-radio-button></el-radio-group>
        </el-form-item>
        <el-form-item v-if="['anchor', 'annotation', 'event'].includes(formMode) || (formMode === 'foreshadow' && !form.id)" label="定位段落">
          <el-select v-model="form.anchor" filterable :clearable="formMode === 'foreshadow'" placeholder="选择章和段落">
            <el-option v-for="item in anchors" :key="item.key" :value="item.key" :label="item.label" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="formMode === 'anchor' || (formMode === 'foreshadow' && !form.id && form.anchor)" label="锚点角色">
          <el-select v-model="form.role"><el-option v-for="role in roles" :key="role" :value="role" :label="role" /></el-select>
        </el-form-item>
        <template v-if="formMode === 'link'">
          <el-form-item label="起因"><el-select v-model="form.from"><el-option v-for="event in research.events" :key="event.id" :value="event.id" :label="event.title" /></el-select></el-form-item>
          <el-form-item label="结果"><el-select v-model="form.to"><el-option v-for="event in research.events" :key="event.id" :value="event.id" :label="event.title" :disabled="event.id === form.from" /></el-select></el-form-item>
          <el-form-item label="关系说明"><el-input v-model="form.relation" /></el-form-item>
        </template>
        <div class="dialog-actions"><el-button @click="formMode = ''">取消</el-button><el-button type="primary" native-type="submit">保存</el-button></div>
      </el-form>
    </div>
    </div>
    </div>
    </div>
  </main>
</template>
