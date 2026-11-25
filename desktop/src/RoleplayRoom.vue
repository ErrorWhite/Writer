<script setup>
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { ArrowLeft, ArrowUp, FileUp, MessageCircle, Plus, RotateCw, Settings2, Square } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { localRequest } from './local-service.js'
import { providerReady } from './context-options.js'
import { readCharacterCard } from './roleplay-card.js'
import { worldMatches } from './world-book.js'

const props = defineProps({ book: Object, connection: Object })
const emit = defineEmits(['close', 'connection'])
const store = computed(() => props.book.roleplay)
const selectedId = ref(store.value.sessions.at(-1)?.id || '')
const session = computed(() => store.value.sessions.find(item => item.id === selectedId.value))
const card = computed(() => store.value.cards.find(item => item.id === session.value?.cardId))
const provider = computed(() => props.connection.provider || 'auto')
const providerName = computed(() => ({auto:'自动',openai:'OpenAI',deepseek:'DeepSeek',codex:'Codex CLI'})[provider.value])
const input = ref('')
const picker = ref()
const messages = ref()
const busy = ref(false)
const error = ref('')
const editor = ref(false)
const sessionOptions = ref(false)
const draft = ref({ name: '', description: '', scene: '', greeting: '' })
let controller

function addSession(character) {
  if (busy.value || !character) return
  const id = crypto.randomUUID()
  store.value.sessions.push({ id, cardId: character.id, persona: '', memory: '', messages: character.greeting
    ? [{ id: crypto.randomUUID(), role: 'assistant', text: character.greeting }] : [] })
  selectedId.value = id
}

function addCard(character) {
  store.value.cards.push(character)
  addSession(character)
}

function saveCard() {
  if (!draft.value.name.trim()) return
  addCard({ ...draft.value, name: draft.value.name.trim(), id: crypto.randomUUID() })
  draft.value = { name: '', description: '', scene: '', greeting: '' }
  editor.value = false
}

async function importCard(event) {
  const [file] = event.target.files
  event.target.value = ''
  if (!file || busy.value) return
  const target = store.value
  try {
    const character = await readCharacterCard(file)
    if (store.value === target) addCard(character)
  } catch (reason) { ElMessage.error(reason.message) }
}

function scrollEnd() {
  nextTick(() => { if (messages.value) messages.value.scrollTop = messages.value.scrollHeight })
}

async function respond(roll = false) {
  const thread = session.value
  if (busy.value || !thread || !card.value) return
  if (!providerReady(props.connection, provider.value)) { emit('connection'); return }
  const last = thread.messages.at(-1)
  const text = input.value.trim()
  if (roll ? last?.role !== 'assistant' || !thread.messages.some(item => item.role === 'user') : !text) return
  if (!roll) {
    thread.messages.push({ id: crypto.randomUUID(), role: 'user', text })
    input.value = ''
    scrollEnd()
  }
  const recent = (roll ? thread.messages.slice(0, -1) : thread.messages).slice(-12)
  const world = worldMatches(props.book.creative.worldBook, recent.slice(-4).map(item => item.text).join('\n'), 4000)
  const request = new AbortController()
  controller = request
  busy.value = true
  error.value = ''
  try {
    const { text: reply } = await localRequest('/api/ai/rewrite', { method: 'POST', signal: request.signal, body: {
      intent: 'roleplay', provider: provider.value, material: {
        角色: { 名称: card.value.name, 描述: card.value.description, 场景: card.value.scene },
        玩家人设: thread.persona, 记忆: thread.memory,
        世界书: world.chosen.map(item => ({ 标题: item.title, 内容: item.content })),
        对话: recent.map(item => ({ 角色: item.role === 'user' ? '玩家' : card.value.name, 正文: item.text.slice(0, 800) })),
        ...(roll ? { 任务: '换一种表达和推进方式', 上一回复: last.text.slice(0, 400) } : {}),
      },
    } })
    if (request.signal.aborted || session.value !== thread) return
    if (!reply?.trim()) throw new Error('模型没有返回角色回复。')
    if (roll) last.text = reply
    else thread.messages.push({ id: crypto.randomUUID(), role: 'assistant', text: reply })
    scrollEnd()
  } catch (reason) {
    if (!request.signal.aborted) error.value = reason.message
    if (!roll && !input.value) input.value = text
    if (!roll && thread.messages.at(-1)?.role === 'user' && thread.messages.at(-1).text === text) thread.messages.pop()
  } finally {
    if (controller === request) { busy.value = false; controller = undefined }
  }
}

watch(selectedId, () => { error.value = ''; input.value = ''; scrollEnd() })
onUnmounted(() => controller?.abort())
</script>

<template>
  <section class="roleplay-room" aria-label="文本扮演">
    <header class="roleplay-header">
      <el-button :icon="ArrowLeft" text aria-label="返回" @click="emit('close')" />
      <h1>文本扮演</h1>
      <el-button :icon="Settings2" text aria-label="AI 服务" @click="emit('connection')">{{ providerName }}</el-button>
    </header>
    <div class="roleplay-controls">
      <el-select v-if="store.sessions.length" v-model="selectedId" aria-label="角色会话" :disabled="busy">
        <el-option v-for="(item, index) in store.sessions" :key="item.id" :value="item.id"
          :label="`${store.cards.find(c => c.id === item.cardId)?.name || '角色'} · 会话 ${index + 1}`" />
      </el-select>
      <div class="roleplay-actions">
        <el-button :icon="Plus" :disabled="busy" @click="editor = !editor">新建角色</el-button>
        <el-button :icon="FileUp" :disabled="busy" @click="picker.click()">导入角色卡</el-button>
        <el-button v-if="card" :disabled="busy" @click="sessionOptions = !sessionOptions">会话设置</el-button>
      </div>
      <input ref="picker" hidden type="file" accept=".json,.png" @change="importCard" />
      <form v-if="editor" class="roleplay-form" @submit.prevent="saveCard">
        <el-input v-model="draft.name" maxlength="60" placeholder="角色名称" aria-label="角色名称" />
        <el-input v-model="draft.description" maxlength="3500" type="textarea" placeholder="角色描述与性格" aria-label="角色描述" />
        <el-input v-model="draft.scene" maxlength="1800" placeholder="场景" aria-label="场景" />
        <el-input v-model="draft.greeting" maxlength="3000" type="textarea" placeholder="开场白" aria-label="开场白" />
        <el-button native-type="submit" type="primary" :disabled="!draft.name.trim()">保存并开始</el-button>
      </form>
      <div v-if="sessionOptions && session" class="roleplay-form">
        <el-input v-model="session.persona" maxlength="900" placeholder="玩家人设" aria-label="玩家人设" />
        <el-input v-model="session.memory" maxlength="1400" type="textarea" placeholder="需要长期记住的剧情" aria-label="会话记忆" />
        <el-button :disabled="busy" @click="addSession(card)">与此角色新开会话</el-button>
      </div>
    </div>
    <div ref="messages" class="roleplay-messages" aria-live="polite">
      <article v-for="item in session?.messages || []" :key="item.id" class="roleplay-message" :class="item.role">
        <strong>{{ item.role === 'user' ? '你' : card?.name }}</strong><p>{{ item.text }}</p>
      </article>
      <div v-if="!session" class="roleplay-empty"><MessageCircle :size="23" /><p>新建或导入角色卡，开始一段对话。</p></div>
      <p v-if="busy" class="library-muted">角色正在回复…</p>
      <p v-if="error" class="field-error" role="alert">{{ error }}</p>
    </div>
    <form v-if="session" class="roleplay-composer" @submit.prevent="respond()">
      <el-input v-model="input" type="textarea" :autosize="{ minRows: 1, maxRows: 4 }" aria-label="消息" placeholder="写下你的台词或动作" maxlength="2500" />
      <el-button v-if="busy" :icon="Square" aria-label="停止生成" @click="controller?.abort()" />
      <template v-else>
        <el-button v-if="session.messages.at(-1)?.role === 'assistant'" :icon="RotateCw" aria-label="重写上一次回复" @click="respond(true)" />
        <el-button :icon="ArrowUp" native-type="submit" type="primary" aria-label="发送消息" :disabled="!input.trim()" />
      </template>
    </form>
  </section>
</template>

<style scoped>
.roleplay-room { width: min(100%, 900px); min-width: 0; min-height: 0; margin-inline: auto; padding: 0 clamp(16px, 4vw, 32px); display: grid; grid-template-rows: auto auto minmax(0, 1fr) auto; gap: 14px; }
.roleplay-header { display: flex; align-items: center; gap: 10px; padding-block: 16px 10px; border-bottom: 1px solid var(--line); }
.roleplay-header h1 { font-size: clamp(23px, 4vw, 31px); flex: 1; }
.roleplay-controls, .roleplay-form { display: grid; gap: 8px; }
.roleplay-form { padding: 12px; background: var(--theme-surface); border-radius: 17px; }
.roleplay-actions { display: flex; flex-wrap: wrap; gap: 6px; }
.roleplay-actions :deep(.el-button) { margin: 0; border-radius: 18px; }
.roleplay-messages { min-height: 0; overflow: auto; display: flex; flex-direction: column; gap: 10px; }
.roleplay-message { align-self: start; max-width: 88%; background: var(--theme-surface); border: 1px solid var(--line); border-radius: 18px 18px 18px 5px; padding: 10px 14px; overflow-wrap: anywhere; }
.roleplay-message.user { align-self: end; background: var(--theme-selection); border-radius: 18px 18px 5px 18px; }
.roleplay-message strong { font-size: 12px; color: var(--muted); }
.roleplay-message p { line-height: 1.65; white-space: pre-wrap; }
.roleplay-empty { display: grid; gap: 8px; justify-items: center; margin-block: auto; color: var(--muted); text-align: center; }
.roleplay-composer { display: flex; align-items: end; gap: 6px; padding-block: 8px max(10px, env(safe-area-inset-bottom)); }
.roleplay-composer :deep(.el-button) { margin: 0; border-radius: 18px; }
</style>
