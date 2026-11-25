<script setup>
import { defineAsyncComponent, onMounted, ref, watch } from 'vue'
import App from './App.vue'
import LoginPage from './LoginPage.vue'
import { localRequest } from './local-service.js'

const AiAccountPanel = defineAsyncComponent(() => import('./AiAccountPanel.vue'))

const session = ref(null)
const accounts = ref(null)
const waitingForSetup = ref(false)
const error = ref('')
const emptyAccounts = { accounts: [], configured: false, openai: false, deepseek: false, codex: false }

async function check() {
  error.value = ''
  try {
    session.value = await localRequest('/api/session', { signal: AbortSignal.timeout(7000) })
  } catch {
    error.value = '无法连接工作台。'
  }
}

async function checkAccounts() {
  error.value = ''
  try {
    const status = await localRequest(session.value?.desktop ? '/api/ai/accounts?defer-cli' : '/api/ai/accounts', { signal: AbortSignal.timeout(10000) })
    if (!session.value?.authenticated) return
    if (session.value.desktop && accounts.value?.cli) return
    accounts.value = status
    waitingForSetup.value = !session.value.desktop && !status.configured
  } catch {
    error.value = '无法读取 AI 账号配置。'
  }
}

function updateAccounts(status) {
  accounts.value = status
  if (!session.value?.desktop && !status.configured) waitingForSetup.value = true
}

function signedIn(result) {
  if (!window.location.hash) window.history.replaceState(null, '', '#shelf')
  session.value = result
}

async function signOut() {
  try {
    session.value = await localRequest('/api/session', { method: 'DELETE' })
    window.history.replaceState(null, '', window.location.pathname + window.location.search)
  } catch {
    await check()
  }
}

onMounted(check)
watch(() => session.value?.authenticated, (authenticated) => {
  if (!authenticated) { accounts.value = null; return }
  if (session.value.desktop) accounts.value = emptyAccounts
  checkAccounts()
})
</script>

<template>
  <App v-if="session?.authenticated && accounts && (session.desktop || (accounts.configured && !waitingForSetup))" :session-required="session.required"
    :desktop="session.desktop" :webview="session.webview" :initial-accounts="accounts" @accounts-change="updateAccounts" @sign-out="signOut" />
  <main v-else-if="session?.authenticated && accounts" class="login-page">
    <el-card class="login-card ai-onboarding-card" shadow="never">
      <div class="login-heading"><div><h1>连接 AI 服务</h1><p>启用至少一个账号后即可进入书架。</p></div></div>
      <AiAccountPanel :info="accounts" @update="updateAccounts" />
      <div class="dialog-actions onboarding-actions">
        <el-button v-if="session.required" text @click="signOut">退出登录</el-button>
        <el-button type="primary" :disabled="!accounts.configured" @click="waitingForSetup = false">进入书架</el-button>
      </div>
    </el-card>
  </main>
  <LoginPage v-else-if="session && !session.authenticated" @authenticated="signedIn" />
  <main v-else class="login-page">
    <el-card class="login-card session-check" shadow="never">
      <p role="status">{{ error || '正在连接工作台…' }}</p>
      <el-button v-if="error" @click="session?.authenticated ? checkAccounts() : check()">重试</el-button>
    </el-card>
  </main>
</template>
