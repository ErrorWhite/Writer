<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { excerpt } from './book-profile.js'
import { providerReady } from './context-options.js'
import { localRequest } from './local-service.js'

const props = defineProps({
  book: { type: Object, required: true }, chapter: { type: Object, required: true },
  chapters: { type: Array, required: true }, connection: { type: Object, required: true },
  inline: Boolean,
})
const emit = defineEmits(['connection', 'mutation'])
const open = defineModel('open', { type: Boolean })
const tab = ref('context')
const busy = ref(false)
const memoryTitle = ref('')
const memoryText = ref('')
const feedbackText = ref('')
const scope = ref('chapter')
const options = computed(() => props.book.context)
const reviews = computed(() => props.chapters.flatMap(({ chapter }) =>
  (chapter.paragraphs || []).flatMap(({ drafts }) => (drafts || [])
    .filter(({ state }) => state === 'accepted' || state === 'rejected')
    .map(({ id, state, text }) => ({ id, state, text, chapter: chapter.title })))).slice(-12).reverse())
const learning = computed(() => props.chapters.flatMap(({ chapter }) => (chapter.handoff?.lessons || [])
  .map((item, index) => ({ ...item, id: chapter.id + ':' + index, chapter: chapter.title }))).slice(-25).reverse())

async function compress() {
  if (!providerReady(props.connection, props.connection.provider || 'auto')) { emit('connection'); return }
  const source = scope.value === 'book'
    ? props.chapters.map(({ chapter }) => `${chapter.title}\n${chapter.text}`).join('\n\n')
    : props.chapter.text
  const room = Math.max(1000, options.value.budget - 1400)
  const recalled = options.value.memories.filter(({ enabled }) => enabled).map(({ title, text }) => title + '：' + text).join('\n')
  const material = { 范围: scope.value === 'book' ? '全书' : props.chapter.title, 正文: excerpt(source, room), 已有记忆: excerpt(recalled, 1000) }
  if (!source.trim() && !recalled) { ElMessage.warning('写下正文或记忆后再压缩。'); return }
  const bookId = props.book.id
  busy.value = true
  try {
    const result = await localRequest('/api/ai/rewrite', {
      method: 'POST', body: { provider: props.connection.provider || 'auto', intent: 'context', material }, signal: AbortSignal.timeout(100000),
    })
    if (props.book.id === bookId) {
      options.value.summary = result.text
      options.value.mode = 'summary'
      ElMessage.success('压缩摘要已保存，可继续编辑')
    }
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    busy.value = false
  }
}

function addMemory() {
  if (!memoryTitle.value.trim() || !memoryText.value.trim()) return
  options.value.memories.push({ id: crypto.randomUUID(), title: memoryTitle.value.trim(), text: memoryText.value.trim(), enabled: true })
  memoryTitle.value = ''
  memoryText.value = ''
}

function addFeedback() {
  if (!feedbackText.value.trim()) return
  options.value.notes.push({ id: crypto.randomUUID(), text: feedbackText.value.trim() })
  feedbackText.value = ''
}
</script>

<template>
  <component :is="inline ? 'section' : 'el-dialog'" v-bind="inline ? { class: 'context-workbench' } : { modelValue: open, title: '上下文管理', width: 'min(600px, 94vw)' }"
    @update:model-value="value => { if (!inline) open = value }">
    <div v-if="inline" class="resource-heading"><h2>知识与上下文</h2><slot name="actions" /></div>
    <el-radio-group v-model="tab" class="context-tabs" aria-label="上下文分类">
      <el-radio-button value="context">上下文</el-radio-button>
      <el-radio-button value="memory">记忆</el-radio-button>
      <el-radio-button value="feedback">反馈</el-radio-button>
    </el-radio-group>
    <div class="context-content">
      <section v-if="tab === 'context'">
        <div class="account-fields context-fields">
          <label class="field-stack">压缩策略<el-select v-model="options.mode" aria-label="压缩策略">
            <el-option label="优先使用压缩摘要" value="summary" />
            <el-option label="超限资料首尾摘录" value="excerpt" />
            <el-option label="按预算保留原文" value="raw" />
          </el-select></label>
          <label class="field-stack">资料字符预算<el-input-number v-model="options.budget" aria-label="资料字符预算" :min="2000" :max="120000" :step="2000" :value-on-clear="12000" /></label>
          <label class="field-stack">前后各取几段<el-input-number v-model="options.neighbors" aria-label="前后各取几段" :min="0" :max="8" :value-on-clear="1" /></label>
        </div>
        <div class="context-options">
          <el-checkbox v-model="options.outline" label="随请求附带大纲" />
          <el-checkbox v-model="options.memory" label="使用启用的记忆" />
          <el-checkbox v-model="options.feedback" label="使用写作反馈" />
        </div>
        <label class="field-stack">压缩摘要<el-input v-model="options.summary" type="textarea" :rows="5" maxlength="14000" placeholder="可手写或由 AI 压缩书稿后编辑" /></label>
        <div class="context-compression">
          <el-radio-group v-model="scope" size="small" aria-label="压缩范围"><el-radio-button value="chapter">本章</el-radio-button><el-radio-button value="book">全书</el-radio-button></el-radio-group>
          <el-button size="small" :loading="busy" @click="compress">生成压缩摘要</el-button>
        </div>
      </section>
      <section v-else-if="tab === 'memory'">
        <p class="dialog-description">记录应长期保留的人物事实、伏笔和设定。</p>
        <form class="context-entry-form" @submit.prevent="addMemory">
          <el-input v-model="memoryTitle" placeholder="记忆标题" maxlength="80" />
          <el-input v-model="memoryText" type="textarea" :rows="2" placeholder="记忆内容" maxlength="3000" />
          <el-button native-type="submit" type="primary" size="small" :disabled="!memoryTitle.trim() || !memoryText.trim()">添加记忆</el-button>
        </form>
        <article v-for="memory in options.memories" :key="memory.id" class="resource-record">
          <el-input v-model="memory.title" maxlength="80" aria-label="记忆标题" />
          <el-input v-model="memory.text" class="context-record-text" type="textarea" :rows="2" maxlength="3000" aria-label="记忆内容" />
          <div class="resource-record-actions"><el-checkbox v-model="memory.enabled" label="启用" /><button class="text-action danger" @click="options.memories = options.memories.filter(({ id }) => id !== memory.id)">删除记忆</button></div>
        </article>
      </section>
      <section v-else>
        <h3 v-if="learning.length">定稿学习记录</h3>
        <article v-for="item in learning" :key="item.id" class="resource-record"><strong>{{ item.chapter }} · {{ item.kind === 'avoid' ? '避免' : '沿用' }}</strong><p>{{ item.text }}</p></article>
        <p class="dialog-description">反馈可附在之后的请求中，例如保留人物语气或避免重复总结。</p>
        <form class="context-entry-form" @submit.prevent="addFeedback">
          <el-input v-model="feedbackText" type="textarea" :rows="3" placeholder="填写给 AI 的反馈" maxlength="2000" />
          <el-button native-type="submit" type="primary" size="small" :disabled="!feedbackText.trim()">添加反馈</el-button>
        </form>
        <article v-for="note in options.notes" :key="note.id" class="resource-record"><p>{{ note.text }}</p><button class="text-action danger" @click="options.notes = options.notes.filter(({ id }) => id !== note.id)">删除反馈</button></article>
        <h3 class="appearance-title">最近审批</h3>
        <article v-for="review in reviews" :key="review.id" class="resource-record"><strong>{{ review.chapter }} · {{ review.state === 'accepted' ? '已接受' : '已拒绝' }}</strong><p>{{ excerpt(review.text, 120) }}</p></article>
        <p v-if="!reviews.length" class="resource-empty">还没有候选审批记录。</p>
      </section>
    </div>
    <div v-if="!inline" class="dialog-actions"><el-button type="primary" @click="open = false">完成</el-button></div>
  </component>
</template>
