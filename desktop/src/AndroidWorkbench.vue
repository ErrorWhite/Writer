<script setup>
import { nextTick, onMounted, ref } from 'vue'
import App from './App.vue'
import { androidCall, openAndroidBook } from './android-workbench.js'
import './android-workbench.css'

const session = ref(null)
const error = ref('')
const conflict = ref(false)
const accounts = ref({ accounts: [], configured: false, openai: false, deepseek: false, codex: false })
window.qingjianModels = configured => { accounts.value = { ...accounts.value, configured } }

onMounted(async () => {
  try {
    const opened = await openAndroidBook()
    if (opened.viewportHeight > 0) {
      document.documentElement.style.setProperty('--android-workbench-height', `${opened.viewportHeight}px`)
    }
    session.value = opened
    await nextTick()
    await androidCall('ready')
  } catch (failure) {
    error.value = failure.message
    androidCall('failure').catch(() => {})
  }
})
</script>

<template>
  <App v-if="session && !conflict" :android="session" :initial-accounts="accounts" @android-conflict="conflict = true" />
  <main v-else-if="conflict" class="android-recovery">
    <h1>已保留本机冲突副本</h1>
    <p>书稿已在其他位置修改。这次编辑保存在书架的「同步冲突」副本中。</p>
    <el-button type="primary" @click="androidCall('navigate', { page: 'Shelf' })">回到书架</el-button>
  </main>
  <p v-else-if="error" role="alert">{{ error }}</p>
</template>
