<script setup>
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { materialKinds, creativeMaterial, promptTasks, referenceFields } from './creative-tools.js'
import { ensureContextBudget, providerReady, withContext } from './context-options.js'
import { localRequest } from './local-service.js'
import { anchorLabel, syncParagraphs } from './revisions.js'
import ChapterReviewPanel from './ChapterReviewPanel.vue'

const props = defineProps({
  book: { type: Object, required: true }, chapters: { type: Array, required: true },
  chapter: { type: Object, required: true }, connection: { type: Object, required: true },
  tab: { type: String, default: 'cards' }, flush: { type: Function, required: true },
  inline: Boolean,
})
const emit = defineEmits(['close', 'jump', 'connection', 'apply-draft', 'restore-chapter', 'profile'])
const tab = ref(props.tab)
const query = ref('')
const kind = ref('')
const card = ref(null)
const creative = computed(() => props.book.creative)
const visibleCards = computed(() => creative.value.cards.filter(item =>
  (!kind.value || item.kind === kind.value) && [item.title, item.body, ...(item.aliases || []), ...(item.keywords || [])]
    .some(value => (value || '').toLowerCase().includes(query.value.trim().toLowerCase()))))
const anchorChapter = computed(() => props.chapters.find(entry => entry.chapter.id === card.value?.chapterId)?.chapter)
const sample = ref('')
const permission = ref(false)
const reference = ref(null)
const referenceError = ref('')
const analyzing = ref(false)
const task = ref('summary')
const previewQuery = ref('')
let controller

const blankReference = () => ({ title: '', enabled: true, ...Object.fromEntries(Object.keys(referenceFields).map(key => [key, ''])) })
function startReference(item) {
  sample.value = ''
  permission.value = false
  referenceError.value = ''
  reference.value = item ? { ...item } : blankReference()
}

function startCard(item) {
  card.value = item ? { ...item, aliases: (item.aliases || []).join('、'), keywords: (item.keywords || []).join('、') }
    : { kind: 'character', title: '', body: '', state: '', aliases: '', keywords: '', pinned: false, confirmed: true, chapterId: props.chapter.id, paragraphId: '', foreshadowId: '' }
  if (anchorChapter.value) syncParagraphs(anchorChapter.value)
}

function chooseAnchor() {
  card.value.paragraphId = ''
  if (anchorChapter.value) syncParagraphs(anchorChapter.value)
}

const terms = value => [...new Set(value.split(/[,，、\n]/u).map(item => item.trim()).filter(Boolean))]
function replaceItem(items, item) {
  const index = items.findIndex(current => current.id === item.id)
  if (index === -1) items.push({ ...item, id: crypto.randomUUID() })
  else items.splice(index, 1, item)
}

function saveCard() {
  const { title, body, aliases, keywords, ...data } = card.value
  if (!title.trim() || !body.trim() || (data.kind === 'scene' && !data.chapterId)) return
  replaceItem(creative.value.cards, {
    ...data, title: title.trim(), body: body.trim(), aliases: data.kind === 'character' ? terms(aliases) : [], keywords: terms(keywords),
  })
  card.value = null
}

async function analyze() {
  if (analyzing.value || !sample.value.trim() || !permission.value) return
  if (!providerReady(props.connection, props.connection.provider || 'auto')) { emit('connection'); return }
  const target = reference.value
  controller = new AbortController()
  analyzing.value = true
  referenceError.value = ''
  try {
    const version = sample.value.trim()
    const intent = 'reference-analysis'
    const material = ensureContextBudget(props.book, { 样本: version, 任务模板: creative.value.templates[intent] || undefined })
    const { data } = await localRequest('/api/ai/rewrite', {
      method: 'POST', body: { provider: props.connection.provider || 'auto', intent, material },
      signal: AbortSignal.any([controller.signal, AbortSignal.timeout(100000)]),
    })
    if (reference.value === target && sample.value.trim() === version) Object.assign(reference.value, data)
  } catch (error) {
    if (!controller.signal.aborted) referenceError.value = error.message
  } finally { analyzing.value = false }
}

function saveReference() {
  const item = reference.value
  if (!item.title.trim() || !Object.keys(referenceFields).some(key => item[key].trim())) return
  replaceItem(creative.value.references, { ...item, title: item.title.trim() })
  reference.value = null
  sample.value = ''
  permission.value = false
}

function jump(item) {
  if (item.chapterId) emit('jump', { chapterId: item.chapterId, paragraphId: item.paragraphId })
}

const preview = computed(() => {
  const material = withContext(props.book, creativeMaterial(props.book, props.chapters, props.chapter, task.value, previewQuery.value))
  return JSON.stringify(material, null, 2)
})
watch(tab, () => controller?.abort())
onUnmounted(() => controller?.abort())
</script>

<template>
  <component :is="inline ? 'div' : 'el-dialog'" v-bind="inline ? {} : { modelValue: true, title: '创作工作台', width: 'min(1060px, 96vw)', class: 'creative-dialog' }"
    @update:model-value="visible => { if (!visible) emit('close') }">
    <nav v-if="!inline" class="resource-tabs creative-tabs" aria-label="创作工作台">
      <button v-for="[id, title] in [['references', '拆书与参考卡'], ['templates', '提示词模板'], ['review', '审阅与比稿'], ['versions', '章节版本']]"
        :key="id" :aria-pressed="tab === id" @click="tab = id">{{ title }}</button>
      <button @click="emit('profile')">立项问卷</button>
    </nav>
    <section v-if="tab === 'cards'" class="creative-body">
      <div class="resource-heading"><h2>资料卡</h2><el-button type="primary" @click="startCard()">新建资料卡</el-button></div>
      <div class="library-filter">
        <el-input v-model="query" placeholder="搜索人物、别名或设定" aria-label="搜索资料卡" clearable />
        <el-select v-model="kind" aria-label="资料类型" placeholder="全部资料"><el-option value="" label="全部资料" /><el-option v-for="(name, id) in materialKinds" :key="id" :value="id" :label="name" /></el-select>
      </div>
      <form v-if="card" class="resource-record creative-form" @submit.prevent="saveCard">
        <div class="creative-grid">
          <label class="field-stack">类型<el-select v-model="card.kind" aria-label="类型"><el-option v-for="(name, id) in materialKinds" :key="id" :value="id" :label="name" /></el-select></label>
          <label class="field-stack">标题<el-input v-model="card.title" maxlength="80" /></label>
          <label v-if="card.kind === 'character'" class="field-stack">别名<el-input v-model="card.aliases" placeholder="用逗号分隔" maxlength="160" /></label>
          <label class="field-stack">检索关键词<el-input v-model="card.keywords" placeholder="用逗号分隔" maxlength="160" /></label>
          <label class="field-stack">关联章节<el-select v-model="card.chapterId" aria-label="关联章节" placeholder="无" @change="chooseAnchor"><el-option value="" label="无" /><el-option v-for="entry in chapters" :key="entry.chapter.id" :value="entry.chapter.id" :label="entry.label + ' ' + entry.chapter.title" /></el-select></label>
          <label v-if="anchorChapter" class="field-stack">定位段落<el-select v-model="card.paragraphId" aria-label="定位段落" placeholder="章节开头"><el-option value="" label="章节开头" /><el-option v-for="(part, index) in anchorChapter.paragraphs" :key="part.id" :value="part.id" :label="`第 ${index + 1} 段 · ${part.text.slice(0, 32)}`" /></el-select></label>
          <label class="field-stack">关联伏笔<el-select v-model="card.foreshadowId" aria-label="关联伏笔" placeholder="无"><el-option value="" label="无" /><el-option v-for="item in book.research.foreshadows" :key="item.id" :value="item.id" :label="item.title" /></el-select></label>
        </div>
        <label class="field-stack">说明<el-input v-model="card.body" type="textarea" :rows="4" maxlength="1400" /></label>
        <label v-if="card.kind !== 'scene'" class="field-stack">当前状态<el-input v-model="card.state" maxlength="800" /></label>
        <div class="creative-controls"><el-checkbox v-model="card.confirmed" label="已确认，可供 AI 引用" /><el-checkbox v-model="card.pinned" label="固定到上下文" /></div>
        <div class="dialog-actions"><el-button @click="card = null">取消</el-button><el-button type="primary" native-type="submit"
          :disabled="!card.title.trim() || !card.body.trim() || (card.kind === 'scene' && !card.chapterId)">保存资料卡</el-button></div>
      </form>
      <article v-for="item in visibleCards" :key="item.id" class="resource-record">
        <strong>{{ item.title }}</strong><small class="creative-meta">{{ [materialKinds[item.kind], item.confirmed ? '已确认' : '待确认', item.pinned ? '已固定' : ''].filter(Boolean).join(' · ') }}</small>
        <p class="creative-prose">{{ item.body }}</p>
        <p v-if="item.state" class="creative-prose">当前状态 {{ item.state }}</p>
        <p v-if="item.aliases?.length" class="creative-meta">别名 {{ item.aliases.join('、') }}</p>
        <p v-if="item.foreshadowId" class="creative-meta">关联伏笔 {{ book.research.foreshadows.find(entry => entry.id === item.foreshadowId)?.title || '已删除' }}</p>
        <div class="resource-record-actions">
          <button v-if="item.chapterId" class="text-action" @click="jump(item)">{{ item.paragraphId ? anchorLabel(item, chapters) : '跳到章节' }}</button>
          <button class="text-action" @click="startCard(item)">编辑</button>
          <button class="text-action danger" @click="creative.cards = creative.cards.filter(entry => entry.id !== item.id)">删除</button>
        </div>
      </article>
      <p v-if="!visibleCards.length" class="resource-empty">没有符合条件的资料卡。</p>
    </section>
    <section v-else-if="tab === 'references'" class="creative-body">
      <div class="resource-heading"><h2>拆书与参考</h2><el-button type="primary" @click="startReference()">新建参考卡</el-button></div>
      <form v-if="reference" class="resource-record creative-form" @submit.prevent="saveReference">
        <label class="field-stack">参考卡标题<el-input v-model="reference.title" maxlength="80" /></label>
        <details open>
          <summary>用样本提炼叙事手法</summary>
          <label class="field-stack">参考样本<el-input v-model="sample" type="textarea" :rows="5" :disabled="analyzing" placeholder="样本仅用于本次分析，保留以下参考卡字段" /></label>
          <div class="creative-controls">
            <el-checkbox v-model="permission" label="我拥有样本或已获授权，允许发送给所选模型" />
            <el-button :loading="analyzing" :disabled="!permission || !sample.trim()" @click="analyze">分析样本</el-button>
            <el-button v-if="analyzing" @click="controller?.abort()">取消分析</el-button>
          </div>
          <p v-if="referenceError" class="field-error" role="alert">{{ referenceError }}</p>
        </details>
        <div class="creative-grid"><label v-for="(title, key) in referenceFields" :key="key" class="field-stack">{{ title }}<el-input v-model="reference[key]" type="textarea" :rows="2" maxlength="450" /></label></div>
        <el-checkbox v-model="reference.enabled" label="用于章节摘要与生成" />
        <div class="dialog-actions">
          <el-button :disabled="analyzing" @click="reference = null; sample = ''">取消</el-button>
          <el-button type="primary" native-type="submit" :disabled="analyzing || !reference.title.trim() || !Object.keys(referenceFields).some(key => reference[key].trim())">保存参考卡</el-button>
        </div>
      </form>
      <article v-for="item in creative.references" :key="item.id" class="resource-record">
        <strong>{{ item.title }}</strong>
        <p v-for="(title, key) in referenceFields" v-show="item[key]" :key="key" class="creative-prose"><span class="creative-meta">{{ title }}</span> {{ item[key] }}</p>
        <div class="resource-record-actions"><el-checkbox v-model="item.enabled" label="用于章纲" /><button class="text-action" @click="startReference(item)">编辑</button><button class="text-action danger" @click="creative.references = creative.references.filter(entry => entry.id !== item.id)">删除</button></div>
      </article>
    </section>
    <section v-else-if="tab === 'templates'" class="creative-body creative-form">
      <div class="resource-heading"><h2>提示词模板</h2></div>
      <label class="field-stack">任务<el-select v-model="task" aria-label="任务"><el-option v-for="(title, id) in promptTasks" :key="id" :value="id" :label="title" /></el-select></label>
      <label class="field-stack">附加要求<el-input v-model="creative.templates[task]" type="textarea" :rows="4" maxlength="1800" placeholder="例如：每章留一个具体的悬念，不要提前解释" /></label>
      <label class="field-stack">试用检索词<el-input v-model="previewQuery" placeholder="输入人物别名或剧情关键词" /></label>
      <details open><summary>预览资料与模板 · {{ chapter.title }}</summary><pre class="creative-preview">{{ preview }}</pre></details>
    </section>
    <ChapterReviewPanel v-else :key="tab + chapter.id" :book="book" :chapter="chapter" :chapters="chapters" :connection="connection"
      :flush="flush" :versions="tab === 'versions'" @jump="jump" @connection="emit('connection')" @apply-draft="emit('apply-draft', $event)"
      @restore-chapter="emit('restore-chapter', $event)" />
  </component>
</template>
