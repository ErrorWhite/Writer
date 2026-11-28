<script setup>
import { nextTick, onMounted, ref } from 'vue'
import App from './App.vue'
import { androidCall, AndroidJournalRecoveryError, openAndroidBook } from './android-workbench.js'
import './android-workbench.css'

const session = ref(null)
const recoveryOnly = new URLSearchParams(window.location.search).get('recover') === '1'
const error = ref('')
const recoveryError = ref('')
const opening = ref(false)
const conflict = ref(false)
const accounts = ref({ accounts: [], configured: false, openai: false, deepseek: false, codex: false })
window.qingjianModels = configured => { accounts.value = { ...accounts.value, configured } }

function returnToShelf() {
  return androidCall('navigate', { page: 'Shelf' }).catch(() => {})
}

async function openBook() {
  if (opening.value) return
  opening.value = true
  try {
    const opened = await openAndroidBook()
    if (recoveryOnly) {
      await androidCall('recovered', { replayed: opened.replayedJournal, conflict: opened.recoveredConflict })
      return
    }
    if (opened.viewportHeight > 0) {
      document.documentElement.style.setProperty('--android-workbench-height', `${opened.viewportHeight}px`)
    }
    recoveryError.value = ''
    session.value = opened
    await nextTick()
    await androidCall('ready')
  } catch (failure) {
    if (!recoveryOnly && failure instanceof AndroidJournalRecoveryError) {
      recoveryError.value = failure.message
      window.qingjianBack = returnToShelf
      await nextTick()
      await androidCall('ready').catch(() => {})
    } else {
      error.value = failure.message
      androidCall('failure').catch(() => {})
    }
  } finally {
    opening.value = false
  }
}

onMounted(openBook)
</script>

<template>
  <App v-if="session && !conflict" :android="session" :initial-accounts="accounts" @android-conflict="conflict = true" />
  <main v-else-if="conflict" class="android-recovery">
    <h1>已保留本机冲突副本</h1>
    <p>书稿已在其他位置修改。这次编辑保存在书架的「同步冲突」副本中。</p>
    <el-button type="primary" @click="androidCall('navigate', { page: 'Shelf' })">回到书架</el-button>
  </main>
  <main v-else-if="recoveryError" class="android-recovery">
    <h1>恢复未完成</h1>
    <p role="alert">{{ recoveryError }}</p>
    <el-button type="primary" :loading="opening" @click="openBook">重试恢复</el-button>
    <el-button @click="returnToShelf">回到书架</el-button>
  </main>
  <p v-else-if="error" role="alert">{{ error }}</p>
</template>
