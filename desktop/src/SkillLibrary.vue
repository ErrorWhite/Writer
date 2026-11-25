<script setup>
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Upload } from '@lucide/vue'
import { approveSkill, availableSkills, importSkillPackage, skillApproved, skillScript } from './writing-skills.js'
import { downloadFile } from './manuscript.js'
import { localRequest } from './local-service.js'

const props = defineProps({ book: { type: Object, required: true }, chapter: { type: Object, required: true } })
const open = defineModel('open', { type: Boolean })
const fileInput = ref()
const folderInput = ref()
const skills = computed(() => availableSkills(props.book))
const approvalChange = ref(0)
const testing = ref('')
const preview = ref(null)

function hasApproval(skill) {
  approvalChange.value
  return skillApproved(props.book, skill)
}

function toggleScript(skill) {
  approveSkill(props.book, skill, !hasApproval(skill))
  approvalChange.value++
}

async function tryScript(skill) {
  const script = skillScript(skill)
  if (!script || testing.value) return
  testing.value = skill.id
  preview.value = null
  try {
    const { result } = await localRequest('/api/skills/test', { method: 'POST', body: {
      script, input: { text: props.chapter.text.slice(0, 16000), task: 'preview' },
    } })
    preview.value = { id: skill.id, text: JSON.stringify(result, null, 2) }
  } catch (error) { ElMessage.error(error.message) }
  finally { testing.value = '' }
}

function enabled(id) {
  return props.book.skills.enabled.includes(id)
}

function toggle(id) {
  props.book.skills.enabled = enabled(id)
    ? props.book.skills.enabled.filter((enabledId) => enabledId !== id)
    : [...props.book.skills.enabled, id]
}

async function importSkill(event) {
  const files = [...event.target.files]
  event.target.value = ''
  if (!files.length) return
  const target = props.book.skills
  try {
    const skill = await importSkillPackage(files)
    if (target !== props.book.skills) return
    target.custom.push(skill)
    ElMessage.success('已导入技能，可以预览后启用')
  } catch (error) {
    ElMessage.error(error instanceof SyntaxError ? '技能 JSON 格式无效。' : error.message)
  }
}

function downloadResource(file) {
  const body = file.text ?? Uint8Array.from(atob(file.base64), char => char.charCodeAt(0))
  const filename = file.path.split('/').at(-1)
  const dot = filename.lastIndexOf('.')
  downloadFile(dot > 0 ? filename.slice(0, dot) : filename, new Blob([body]), dot > 0 ? filename.slice(dot + 1) : 'bin')
}

function remove(id) {
  const skill = props.book.skills.custom.find(item => item.id === id)
  if (skill) approveSkill(props.book, skill, false)
  props.book.skills.custom = props.book.skills.custom.filter((skill) => skill.id !== id)
  props.book.skills.enabled = props.book.skills.enabled.filter((enabledId) => enabledId !== id)
}
</script>

<template>
  <el-dialog v-model="open" title="写作技能" width="min(570px, 94vw)">
    <p class="dialog-description">启用后，下一次生成或 Roll 会按列表顺序使用这些写作提示。</p>
    <div class="skill-list">
      <div v-for="skill in skills" :key="skill.id" class="skill-entry">
        <div class="skill-row">
          <div><strong>{{ skill.name }}</strong><p v-if="skill.description">{{ skill.description }}</p></div>
          <el-switch :model-value="enabled(skill.id)" :aria-label="skill.name" @change="toggle(skill.id)" />
        </div>
        <details>
          <summary>查看提示</summary>
          <p class="skill-instruction">{{ skill.instruction }}</p>
          <details v-if="skill.files?.length"><summary>{{ skill.files.length }} 个技能资源</summary>
            <button v-for="file in skill.files" :key="file.path" class="text-action skill-resource" type="button" @click="downloadResource(file)">{{ file.path }}</button>
          </details>
          <div v-if="skillScript(skill)" class="resource-record">
            <strong>脚本入口 · {{ skillScript(skill).entry }}</strong>
            <p>启用脚本后，它可读取本次写作素材，并将结果加入 AI 请求。测试使用本章正文，直接在本地运行。</p>
            <div class="resource-record-actions">
              <el-button size="small" :loading="testing === skill.id" @click="tryScript(skill)">用本章测试</el-button>
              <el-switch :model-value="hasApproval(skill)" aria-label="授权技能脚本" @change="toggleScript(skill)" />
              <span>{{ hasApproval(skill) ? '已授权' : '未授权' }}</span>
            </div>
            <pre v-if="preview?.id === skill.id" class="skill-instruction">{{ preview.text }}</pre>
          </div>
          <el-button v-if="book.skills.custom.some(({ id }) => id === skill.id)" text type="danger" size="small" @click="remove(skill.id)">移除技能</el-button>
        </details>
      </div>
    </div>
    <details class="dialog-description">
      <summary>技能脚本格式</summary>
      <p>在 SKILL.md 的 YAML 元数据中填入 qingjian.entry 和 qingjian.tools。入口定义同步函数 run(input, tools)，返回 JSON 值。input 提供 text、instruction、task。可声明的工具有 wordCount、find、readReference。</p>
    </details>
    <label v-if="enabled('style-distillation')" class="field-stack">作者样稿
      <el-input v-model="book.skills.styleSample" type="textarea" :rows="4" maxlength="16000" placeholder="粘贴自己的代表段落，留空则从本章相邻段落提取文风。" />
    </label>
    <div class="dialog-actions skill-actions">
      <input ref="fileInput" type="file" accept=".md,.txt,.json,.zip" hidden @change="importSkill" />
      <input ref="folderInput" type="file" webkitdirectory multiple hidden @change="importSkill" />
      <el-button :icon="Upload" @click="fileInput.click()">导入技能文件或 ZIP</el-button>
      <el-button @click="folderInput.click()">导入技能目录</el-button>
      <el-button type="primary" @click="open = false">完成</el-button>
    </div>
  </el-dialog>
</template>
