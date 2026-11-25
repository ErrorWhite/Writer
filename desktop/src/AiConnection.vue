<script setup>
import { defineAsyncComponent } from 'vue'
import { ElMessage } from 'element-plus'
import { preferences, savePreferences } from './studio-preferences.js'

const AiAccountPanel = defineAsyncComponent(() => import('./AiAccountPanel.vue'))

defineProps({ info: { type: Object, required: true } })
const emit = defineEmits(['status'])
const open = defineModel('open', { type: Boolean })
</script>

<template>
  <el-dialog v-model="open" title="AI 服务" width="min(560px, 94vw)">
    <div class="field-stack provider-preference">所有书共用的 AI 服务
      <el-select :model-value="preferences.provider" aria-label="全局 AI 服务"
        @update:model-value="value => savePreferences({ provider: value }).catch(error => ElMessage.error(error.message))">
        <el-option label="全部启用账号轮询" value="auto" />
        <el-option label="仅轮询 OpenAI 账号" value="openai" />
        <el-option label="仅轮询 DeepSeek 账号" value="deepseek" />
        <el-option label="使用 Codex CLI" value="codex" />
      </el-select>
    </div>
    <AiAccountPanel :info="info" @update="emit('status', $event)" />
    <div class="dialog-actions"><el-button @click="open = false">完成</el-button></div>
  </el-dialog>
</template>
