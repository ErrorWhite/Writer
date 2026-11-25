<script setup>
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@lucide/vue'
import { localRequest } from './local-service.js'

const props = defineProps({ info: { type: Object, required: true } })
const emit = defineEmits(['update'])
const platforms = {
  openai: { name: 'OpenAI', model: 'gpt-6.2-sol', efforts: ['auto', 'none', 'minimal', 'low', 'medium', 'high', 'xhigh'], keys: 'https://platform.openai.com/api-keys' },
  deepseek: { name: 'DeepSeek', model: 'deepseek-flash', efforts: ['auto', 'none', 'low', 'high', 'max'], keys: 'https://platform.deepseek.com/api_keys' },
}
const effortLabels = { auto: '模型默认', none: '关闭', minimal: '最少', low: '低', medium: '中', high: '高', xhigh: '极高', max: '最高' }
const cliEfforts = ['auto', 'minimal', 'low', 'medium', 'high', 'xhigh']
const form = reactive({ id: '', provider: 'openai', name: '', key: '', model: 'gpt-6.2-sol', effort: 'auto', enabled: true })
const showEditor = ref(!props.info.accounts?.length)
const loading = ref(false)
const error = ref('')
const platform = computed(() => platforms[form.provider])
const cli = computed(() => props.info.cli)
const cliAccounts = computed(() => cli.value?.accounts || [])
const hasPendingLogin = computed(() => cliAccounts.value.some(account => account.login.state === 'pending'))
const cliForm = reactive({ model: '', effort: 'auto', enabled: true })
const cliError = ref('')
let cliPoll
let pollingCli = false
let cliVersion = 0
const cliBusy = ref(false)

async function cliRequest(path, options = {}) {
  try {
    emit('update', await localRequest(path, { ...options, signal: AbortSignal.timeout(14000) }))
    cliError.value = ''
    return true
  } catch (reason) {
    cliError.value = reason.message
    return false
  }
}

async function cliAction(action, account, name) {
  if (cliBusy.value) return
  cliVersion++
  cliBusy.value = true
  cliError.value = ''
  if (action === 'login' || action === 'cancel') await cliRequest('/api/ai/codex/login', { method: 'POST', body: { action, id: account.id } })
  else if (['add', 'select', 'rename'].includes(action)) await cliRequest('/api/ai/codex/accounts', { method: 'POST', body: { action, id: account?.id, name } })
  else if (action === 'save') await cliRequest('/api/ai/codex/settings', { method: 'POST', body: { ...cliForm } })
  else await cliRequest('/api/ai/accounts?check=codex')
  cliBusy.value = false
}

async function nameCliAccount(account) {
  let name
  try {
    const { value } = await ElMessageBox.prompt('这个备注用于区分青简里的登录。', account ? '修改 Codex 账号备注' : '添加 Codex 账号', {
      inputValue: account?.name || '', inputPlaceholder: '例如：个人账号',
      inputValidator: value => Boolean(value?.trim()) && value.trim().length <= 60 || '请输入 1 至 60 个字符',
      confirmButtonText: '保存', cancelButtonText: '取消',
    })
    name = value.trim()
  } catch { return }
  await cliAction(account ? 'rename' : 'add', account, name)
}

watch(() => [cli.value?.model, cli.value?.effort, cli.value?.enabled], ([model, effort, enabled]) => {
  Object.assign(cliForm, { model: model || '', effort: effort || 'auto', enabled: enabled !== false })
}, { immediate: true })
function scheduleCliPoll(delay = 1500) {
  cliPoll = setTimeout(async () => {
    if (cliBusy.value) { scheduleCliPoll(); return }
    const version = cliVersion
    try {
      const status = await localRequest('/api/ai/accounts', { signal: AbortSignal.timeout(14000) })
      if (pollingCli && version === cliVersion) emit('update', status)
    } catch {
      // Keep the individual authorization states visible while reconnecting.
    } finally {
      if (pollingCli) scheduleCliPoll()
    }
  }, delay)
}

watch(hasPendingLogin, (pending) => {
  clearTimeout(cliPoll)
  pollingCli = pending
  if (pollingCli) scheduleCliPoll()
}, { immediate: true })
onUnmounted(() => { pollingCli = false; clearTimeout(cliPoll) })

function changePlatform() {
  form.model = platform.value.model
  form.effort = form.provider === 'deepseek' ? 'none' : 'auto'
}

function edit(account) {
  const defaults = { id: '', provider: 'openai', name: '', model: 'gpt-6.2-sol', effort: 'auto', enabled: true }
  Object.assign(form, defaults, account || {}, { key: '' })
  showEditor.value = true
  error.value = ''
}

async function save() {
  if ((!form.id && !form.key.trim()) || !form.model.trim() || loading.value) return
  loading.value = true
  error.value = ''
  try {
    emit('update', await localRequest('/api/ai/accounts', { method: 'POST', body: { ...form }, signal: AbortSignal.timeout(20000) }))
    form.key = ''
    showEditor.value = false
    ElMessage.success('AI 账号已保存')
  } catch (reason) {
    error.value = reason.message
  } finally {
    loading.value = false
  }
}

async function remove(account) {
  try {
    await ElMessageBox.confirm(`移除「${account.name}」的连接？`, '移除 AI 账号', { confirmButtonText: '移除', cancelButtonText: '取消' })
  } catch { return }
  try {
    emit('update', await localRequest(`/api/ai/accounts?id=${encodeURIComponent(account.id)}`, { method: 'DELETE' }))
    if (form.id === account.id) showEditor.value = false
  } catch (reason) {
    error.value = reason.message
  }
}
</script>

<template>
  <section class="ai-account-panel">
    <p class="dialog-description">API 账号按顺序轮询，可分别设置模型和思考等级。</p>
    <ul v-if="info.accounts?.length" class="ai-account-list">
      <li v-for="account in info.accounts" :key="account.id">
        <div><strong>{{ account.name }}</strong><small>{{ platforms[account.provider]?.name }} · {{ account.model }} · {{ effortLabels[account.effort] }} · {{ account.enabled ? '启用' : '停用' }}</small></div>
        <div v-if="!account.managed" class="compact-actions">
          <el-button text size="small" @click="edit(account)">编辑</el-button>
          <el-button text size="small" type="danger" @click="remove(account)">移除</el-button>
        </div>
      </li>
    </ul>
    <el-button v-if="!showEditor" :icon="Plus" @click="edit()">添加账号</el-button>
    <el-form v-else class="ai-account-form" label-position="top" :disabled="loading" @submit.prevent="save">
      <div class="account-fields">
        <el-form-item label="服务商"><el-select v-model="form.provider" @change="changePlatform">
          <el-option label="OpenAI" value="openai" /><el-option label="DeepSeek" value="deepseek" />
        </el-select></el-form-item>
        <el-form-item label="账号备注"><el-input v-model="form.name" maxlength="60" placeholder="例如：主账号" /></el-form-item>
        <el-form-item label="模型 ID"><el-input v-model="form.model" maxlength="160" :placeholder="platform.model" /></el-form-item>
        <el-form-item label="思考等级"><el-select v-model="form.effort"><el-option v-for="level in platform.efforts" :key="level" :label="effortLabels[level]" :value="level" /></el-select></el-form-item>
      </div>
      <el-form-item label="API 密钥"><el-input v-model="form.key" type="password" show-password autocomplete="off" :placeholder="form.id ? '留空保留现有密钥' : '粘贴服务商的 API 密钥'" /></el-form-item>
      <div class="account-form-footer"><a :href="platform.keys" target="_blank" rel="noreferrer">打开官方密钥页面</a><el-checkbox v-model="form.enabled" label="启用账号" /></div>
      <div class="dialog-actions"><el-button v-if="info.accounts?.length" @click="showEditor = false">取消</el-button><el-button type="primary" native-type="submit" :loading="loading" :disabled="(!form.id && !form.key.trim()) || !form.model.trim()">{{ form.id ? '保存账号' : '验证并连接' }}</el-button></div>
    </el-form>
    <section class="codex-account">
      <div class="resource-heading">
        <div><strong>Codex CLI</strong><p>{{ !cli ? '正在检测 CLI…' : cli.installed ? (cli.connected ? '已登录 ChatGPT' : '已安装 · 等待登录') : '未检测到 CLI' }}{{ cli?.version ? ' · ' + cli.version : '' }}</p></div>
        <el-button text size="small" :loading="cliBusy" @click="cliAction('refresh')">刷新状态</el-button>
      </div>
      <p v-if="cliError" class="field-error" role="alert">{{ cliError }}</p>
      <template v-if="cli?.installed">
        <p class="dialog-description">每个账号分别授权。写作时使用标记为「当前使用」的账号。</p>
        <ul class="ai-account-list codex-account-list">
          <li v-for="account in cliAccounts" :key="account.id">
            <div class="codex-account-row">
              <div><strong>{{ account.name }}</strong><small>{{ account.login.state === 'pending' ? '等待授权' : account.connected ? '已登录 ChatGPT' : '未登录' }}<template v-if="cli.selected === account.id"> · 当前使用</template></small></div>
              <div class="codex-account-actions">
                <el-button v-if="cli.selected !== account.id" size="small" text :disabled="cliBusy || !account.connected" @click="cliAction('select', account)">使用此账号</el-button>
                <el-button v-if="account.id !== 'system'" size="small" text :disabled="cliBusy" @click="nameCliAccount(account)">改备注</el-button>
                <el-button v-if="account.login.state === 'pending'" size="small" :disabled="cliBusy" @click="cliAction('cancel', account)">取消授权</el-button>
                <el-button v-else size="small" :disabled="cliBusy" @click="cliAction('login', account)">{{ account.connected ? '重新授权' : '设备登录' }}</el-button>
              </div>
            </div>
            <div v-if="account.login.state === 'pending'" class="device-login" role="status">
              <p v-if="!account.login.code">正在获取设备代码…</p>
              <template v-else><a :href="account.login.url" target="_blank" rel="noreferrer">打开 OpenAI 授权页面</a><code>{{ account.login.code }}</code><p>请在授权页面确认浏览器登录的是这一行对应的账号。</p></template>
            </div>
            <div v-if="account.login.state === 'error'" class="cli-login-error" role="alert">
              <p class="field-error">{{ account.login.message }}</p>
              <a v-if="account.login.reason === 'request-forbidden'" href="https://help.openai.com/en/articles/7947663-chatgpt-and-openai-api-supported-countries-and-territories" target="_blank" rel="noreferrer">查看 OpenAI 支持地区</a>
            </div>
          </li>
        </ul>
        <el-button :icon="Plus" size="small" :disabled="cliBusy" @click="nameCliAccount()">添加 Codex 账号</el-button>
      </template>
      <details v-if="cli?.installed" class="codex-settings">
        <summary>Codex 模型和思考等级</summary>
        <div class="account-fields">
          <label class="field-stack">Codex 模型<el-input v-model="cliForm.model" placeholder="留空使用 CLI 默认" maxlength="160" /></label>
          <label class="field-stack">Codex 思考等级<el-select v-model="cliForm.effort" aria-label="Codex 思考等级"><el-option v-for="level in cliEfforts" :key="level" :value="level" :label="effortLabels[level]" /></el-select></label>
        </div>
        <div class="account-form-footer"><el-checkbox v-model="cliForm.enabled" label="启用 Codex" /><el-button size="small" :loading="cliBusy" @click="cliAction('save')">保存 Codex 设置</el-button></div>
      </details>
    </section>
    <p v-if="error" class="field-error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.codex-account-list > li { display: grid; gap: 8px; padding: 10px; }
.codex-account-row, .codex-account-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; }
.codex-account-row { justify-content: space-between; }
.codex-account-row > div:first-child { min-width: 90px; overflow-wrap: anywhere; }
.codex-account-actions .el-button + .el-button { margin-left: 0; }
</style>
