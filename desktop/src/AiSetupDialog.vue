<script setup>
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { Sparkles } from '@lucide/vue'
import { excerpt, profileFields, referenceFor } from './book-profile.js'
import { localRequest } from './local-service.js'
import { ensureContextBudget, providerReady } from './context-options.js'

const props = defineProps({
  book: { type: Object, required: true },
  chapters: { type: Array, required: true },
  connection: { type: Object, required: true },
  profileOnly: Boolean,
})
const emit = defineEmits(['configured', 'connection', 'references'])
const open = defineModel('open', { type: Boolean })
const draft = reactive({ ...props.book.aiSetup, title: props.book.title })
const analyzing = ref(false)
const error = ref('')
const assistOpen = ref(false)
const idea = ref('')
const includeReference = ref(true)
const suggesting = ref(false)
const suggestions = ref([])
const assistError = ref('')
const assistStatus = ref('')
let assistRequest
const referenceChapters = computed(() => props.chapters.filter(({ chapter }) => chapter.text.trim()))
const reference = computed(() => referenceFor(draft, props.chapters))
const valid = computed(() => Boolean(draft.title.trim() && (props.profileOnly || reference.value) && draft.wordTarget && draft.chapterTarget))
const hasAssistMaterial = computed(() => Boolean(idea.value.trim() || props.book.outline.trim() || props.book.notes.trim() ||
  (includeReference.value && reference.value)))

watch(open, (visible) => {
  if (!visible) { assistRequest?.abort(); return }
  error.value = ''
  assistError.value = ''
  assistStatus.value = ''
  assistOpen.value = false
  idea.value = ''
  includeReference.value = true
  suggestions.value = []
  Object.assign(draft, props.book.aiSetup, { title: props.book.title })
  if (!draft.referenceChapterId && draft.referenceType === 'chapter') {
    draft.referenceChapterId = referenceChapters.value.find(({ chapter }) => chapter.id === props.book.activeId)?.chapter.id
      || referenceChapters.value[0]?.chapter.id || ''
    if (!draft.referenceChapterId) draft.referenceType = 'paste'
  }
})
onUnmounted(() => assistRequest?.abort())

async function suggestProfile() {
  if (!hasAssistMaterial.value || suggesting.value) return
  const provider = props.connection.provider || 'auto'
  if (!providerReady(props.connection, provider)) { emit('connection'); return }
  const controller = new AbortController()
  assistRequest = controller
  suggesting.value = true
  assistError.value = ''
  assistStatus.value = ''
  try {
    const fields = Object.fromEntries(Object.entries(profileFields).flatMap(([key, field]) => {
      const value = draft[key]
      return value ? [[key, typeof value === 'string' ? excerpt(value.trim(), field.max) : value]] : []
    }))
    const style = props.book.context
    const referenceLimit = Math.min(2100, Math.max(300, Math.floor((Number(style.budget) || 12000) / 3)))
    const { data } = await localRequest('/api/ai/rewrite', {
      method: 'POST', signal: AbortSignal.any([controller.signal, AbortSignal.timeout(100000)]),
      body: { provider, intent: 'profile-suggest', material: ensureContextBudget(props.book, {
        作者构思: idea.value.trim(), 已填写: fields,
        大纲: excerpt(props.book.outline.trim(), 900), 设定笔记: excerpt(props.book.notes.trim(), 750),
        参考正文: includeReference.value ? excerpt(reference.value, referenceLimit) : '',
      }) },
    })
    if (controller.signal.aborted || !open.value) return
    suggestions.value = Object.entries(profileFields).flatMap(([key, field]) => Object.hasOwn(data, key) && data[key] !== draft[key]
      ? [{ key, label: field.label, value: data[key], selected: !draft[key] }] : [])
    if (!suggestions.value.length) assistStatus.value = '当前资料没有产生新的填写建议。'
  } catch (reason) {
    if (!controller.signal.aborted) assistError.value = reason.message
  } finally {
    if (assistRequest === controller) { suggesting.value = false; assistRequest = undefined }
  }
}

function fillSuggestedFields() {
  const applied = suggestions.value.filter(item => item.selected)
  for (const { key, value } of applied) draft[key] = value
  suggestions.value = suggestions.value.filter(item => !item.selected)
  assistStatus.value = `已填入 ${applied.length} 项，可在表单里继续修改。`
}

async function analyze() {
  const provider = props.connection.provider || 'auto'
  if (!providerReady(props.connection, provider)) {
    emit('connection')
    return
  }
  analyzing.value = true
  error.value = ''
  try {
    const result = await localRequest('/api/ai/rewrite', {
      method: 'POST', signal: AbortSignal.timeout(145000),
      body: { provider, intent: 'style', material: { 参考章: excerpt(reference.value, Math.min(props.book.context.budget || 6000, 6000)), 已知纠偏要求: draft.styleCorrection } },
    })
    draft.writingStyle = result.text
  } catch (reason) {
    error.value = reason.message
  } finally {
    analyzing.value = false
  }
}

function save() {
  if (!valid.value) return
  const { title, ...profile } = draft
  props.book.title = title.trim()
  props.book.aiSetup = { ...profile, configured: props.profileOnly ? props.book.aiSetup.configured : true,
    referenceSnapshot: reference.value || props.book.aiSetup.referenceSnapshot }
  props.book.goal = profile.chapterTarget
  open.value = false
  if (!props.profileOnly) emit('configured')
}
</script>

<template>
  <el-dialog v-model="open" :title="profileOnly ? '立项问卷与创作设定' : book.aiSetup.configured ? 'AI 创作设定' : '设置 AI 驱动创作'" width="min(700px, 94vw)">
    <el-form class="profile-form" label-position="top" novalidate @submit.prevent="save">
      <section>
        <div class="resource-heading profile-assist-heading">
          <h3>作品信息</h3>
          <el-button size="small" :icon="Sparkles" :aria-expanded="assistOpen" @click="assistOpen = !assistOpen">AI 辅助填写</el-button>
        </div>
        <div v-if="assistOpen" class="profile-assist">
          <label class="field-stack">作品构思
            <el-input v-model="idea" aria-label="作品构思" type="textarea" :rows="3" maxlength="1000" placeholder="例如：退潮后的港口收到一封来自过去的信" />
          </label>
          <p class="library-muted">发送构思、已填字段、大纲和设定摘录。已有内容只在勾选建议后更新。</p>
          <div class="profile-assist-actions">
            <el-checkbox v-model="includeReference" label="一并发送所选参考章摘录" :disabled="suggesting" />
            <el-button :icon="Sparkles" type="primary" size="small" :loading="suggesting" :disabled="!hasAssistMaterial" @click="suggestProfile">生成填写建议</el-button>
            <el-button v-if="suggesting" size="small" @click="assistRequest?.abort()">取消生成</el-button>
          </div>
          <p v-if="assistError" class="field-error" role="alert">{{ assistError }}</p>
          <p v-if="assistStatus" class="library-muted" role="status">{{ assistStatus }}</p>
          <ul v-if="suggestions.length" class="profile-suggestions">
            <li v-for="item in suggestions" :key="item.key">
              <el-checkbox v-model="item.selected" :disabled="suggesting" :label="item.label" />
              <p v-if="draft[item.key]" class="library-muted">当前 {{ draft[item.key] }}</p>
              <p>{{ item.value }}</p>
            </li>
          </ul>
          <div v-if="suggestions.length" class="dialog-actions">
            <el-button :disabled="suggesting" size="small" @click="suggestions.forEach(item => { item.selected = true })">全选建议</el-button>
            <el-button type="primary" size="small" :disabled="suggesting || !suggestions.some(item => item.selected)" @click="fillSuggestedFields">填入所选字段</el-button>
          </div>
        </div>
        <div class="profile-grid">
          <el-form-item label="书名"><el-input v-model="draft.title" maxlength="60" /></el-form-item>
          <el-form-item label="类别"><el-input v-model="draft.genre" placeholder="例如：悬疑、奇幻、现实" maxlength="80" /></el-form-item>
          <el-form-item label="全书目标字数"><el-input-number v-model="draft.wordTarget" :min="1" :max="10000000" :step="5000" /></el-form-item>
          <el-form-item label="单章目标字数"><el-input-number v-model="draft.chapterTarget" :min="100" :max="12000" :step="100" /></el-form-item>
          <el-form-item label="主角"><el-input v-model="draft.mainCharacter" placeholder="姓名、目标或关键矛盾" /></el-form-item>
          <el-form-item label="叙事视角">
            <el-select v-model="draft.pointOfView" filterable allow-create>
              <el-option v-for="view in ['第一人称', '第三人称有限', '第三人称全知', '多视角']" :key="view" :label="view" :value="view" />
            </el-select>
          </el-form-item>
          <el-form-item label="故事内核"><el-input v-model="draft.premise" type="textarea" :rows="2" placeholder="这个故事围绕什么冲突展开" /></el-form-item>
          <el-form-item label="主题"><el-input v-model="draft.theme" type="textarea" :rows="2" placeholder="希望读者思考的主题" /></el-form-item>
          <el-form-item label="时代与世界背景"><el-input v-model="draft.setting" type="textarea" :rows="2" /></el-form-item>
          <el-form-item label="其他创作要求"><el-input v-model="draft.extra" type="textarea" :rows="2" placeholder="人物限制、基调或伏笔要求" /></el-form-item>
          <el-form-item label="读者期待"><el-input v-model="draft.readerPromise" type="textarea" :rows="2" placeholder="这部作品给读者持续提供什么阅读体验" /></el-form-item>
          <el-form-item label="阶段推进"><el-input v-model="draft.storyStages" type="textarea" :rows="2" placeholder="开场所建立的状态、中期变化与结局方向" /></el-form-item>
        </div>
      </section>
      <section>
        <h3>参考章与文风纠偏</h3>
        <el-button size="small" @click="emit('references')">管理参考卡</el-button>
        <p class="dialog-description">选择一章代表性正文，或粘贴自己的完整样章。分析长章时会抽取章首、中段和章尾。</p>
        <el-radio-group v-model="draft.referenceType" class="reference-type" aria-label="参考章来源">
          <el-radio-button value="chapter">从书稿选择</el-radio-button><el-radio-button value="paste">粘贴参考章</el-radio-button>
        </el-radio-group>
        <el-form-item v-if="draft.referenceType === 'chapter'" label="参考章">
          <el-select v-model="draft.referenceChapterId" filterable placeholder="选择含有正文的章节">
            <el-option v-for="{ chapter, label } in referenceChapters" :key="chapter.id" :label="`${label} ${chapter.title}`" :value="chapter.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-else label="参考章正文"><el-input v-model="draft.referenceText" type="textarea" :rows="4" /></el-form-item>
        <el-button :icon="Sparkles" size="small" :loading="analyzing" :disabled="!reference" @click="analyze">用 AI 分析参考章</el-button>
        <p v-if="error" class="field-error" role="alert">{{ error }}</p>
        <el-form-item label="写作文风" class="style-field"><el-input v-model="draft.writingStyle" type="textarea" :rows="3" placeholder="可填写或调整 AI 提取的语体、句式和节奏。" /></el-form-item>
        <el-form-item label="文风纠偏要求"><el-input v-model="draft.styleCorrection" type="textarea" :rows="3" /></el-form-item>
      </section>
      <div class="dialog-actions"><el-button @click="open = false">取消</el-button><el-button type="primary" native-type="submit" :disabled="!valid || analyzing">{{ profileOnly || book.aiSetup.configured ? '保存设定' : '保存并开启 AI 驱动' }}</el-button></div>
    </el-form>
  </el-dialog>
</template>
