<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { localRequest } from './local-service.js'

const open = defineModel('open', { type: Boolean })
const form = reactive({ currentPassword: '', newPassword: '', confirmation: '' })
const busy = ref(false)
const error = ref('')
const ready = computed(() => form.currentPassword && form.newPassword.trim() && form.newPassword.length >= 8 &&
  form.newPassword !== form.currentPassword && form.newPassword === form.confirmation)

watch(open, () => {
  Object.assign(form, { currentPassword: '', newPassword: '', confirmation: '' })
  error.value = ''
})

async function change() {
  if (!ready.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    await localRequest('/api/session/password', {
      method: 'POST',
      body: { currentPassword: form.currentPassword, newPassword: form.newPassword },
      signal: AbortSignal.timeout(12000),
    })
    open.value = false
    ElMessage.success('密码已更新')
  } catch (reason) {
    error.value = reason.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-dialog v-model="open" title="修改密码" width="min(440px, 92vw)" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy">
    <el-form label-position="top" @submit.prevent="change">
      <el-form-item label="当前密码"><el-input v-model="form.currentPassword" type="password" autocomplete="current-password" maxlength="256" show-password /></el-form-item>
      <el-form-item label="新密码"><el-input v-model="form.newPassword" type="password" autocomplete="new-password" placeholder="至少 8 个字符" maxlength="256" show-password /></el-form-item>
      <el-form-item label="确认新密码"><el-input v-model="form.confirmation" type="password" autocomplete="new-password" maxlength="256" show-password /></el-form-item>
      <p v-if="error || (form.confirmation && form.confirmation !== form.newPassword)" class="field-error" role="alert">{{ error || '两次输入的新密码不一致。' }}</p>
      <div class="dialog-actions"><el-button :disabled="busy" @click="open = false">取消</el-button><el-button type="primary" native-type="submit" :loading="busy" :disabled="!ready">更新密码</el-button></div>
    </el-form>
  </el-dialog>
</template>
