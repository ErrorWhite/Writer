<script setup>
import { ref } from 'vue'
import iconUrl from './qingjian-icon.png'
import { localRequest } from './local-service.js'

const emit = defineEmits(['authenticated'])
const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')

async function signIn() {
  if (!username.value.trim() || !password.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const session = await localRequest('/api/session', {
      method: 'POST',
      body: { username: username.value.trim(), password: password.value },
      signal: AbortSignal.timeout(10000),
    })
    if (!session.authenticated) throw new Error('登录未完成，请重试。')
    emit('authenticated', session)
  } catch (reason) {
    error.value = reason.message
  } finally {
    busy.value = false
    password.value = ''
  }
}
</script>

<template>
  <main class="login-page">
    <el-card class="login-card" shadow="never">
      <div class="login-heading">
        <img :src="iconUrl" class="brand-app-icon" alt="" />
        <h1>登录青简</h1>
      </div>
      <el-form label-position="top" @submit.prevent="signIn">
        <el-form-item label="账号"><el-input v-model="username" name="username" autocomplete="username" placeholder="输入账号" maxlength="128" autofocus /></el-form-item>
        <el-form-item label="密码"><el-input v-model="password" name="password" type="password" autocomplete="current-password" placeholder="输入密码" maxlength="256" show-password /></el-form-item>
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="login-error" />
        <el-button class="login-submit" type="primary" native-type="submit" :disabled="!username.trim() || !password" :loading="busy">登录</el-button>
      </el-form>
    </el-card>
  </main>
</template>
