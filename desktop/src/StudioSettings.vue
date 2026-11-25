<script setup>
import { ElMessage } from 'element-plus'
import { preferences, savePreferences } from './studio-preferences.js'
const open = defineModel('open', { type: Boolean })
const autoFullscreen = defineModel('autoFullscreen', { type: Boolean })
defineProps({ desktop: Boolean })
const fullscreenSupported = document.fullscreenEnabled
const emit = defineEmits(['accounts'])
function change(patch) { savePreferences(patch).catch(error => ElMessage.error(error.message)) }
function changeAccent(value) { change({ accent: value?.toLowerCase() || '#3d62df' }) }
</script>

<template>
  <el-dialog v-model="open" title="全局设置" width="min(460px, 94vw)">
    <div class="settings-list">
      <div class="settings-row">
        <span>界面主题色</span>
        <div class="settings-controls">
          <span class="settings-color-value">{{ preferences.accent }}</span>
          <el-color-picker :model-value="preferences.accent" color-format="hex" aria-label="界面主题色" @change="changeAccent" />
          <el-button text size="small" @click="changeAccent()">恢复默认主题色</el-button>
        </div>
      </div>
      <div class="settings-row"><span>外观</span><span class="settings-value">跟随系统</span></div>
      <div v-if="!desktop && fullscreenSupported" class="settings-row">
        <label for="settings-fullscreen">打开书籍时全屏</label>
        <el-switch id="settings-fullscreen" v-model="autoFullscreen" />
      </div>
      <div class="settings-row">
        <label for="settings-images">启用 OpenAI 配图</label>
        <el-switch id="settings-images" :model-value="preferences.images" @change="value => change({ images: value })" />
      </div>
    </div>
    <div class="dialog-actions"><el-button @click="open = false; emit('accounts')">AI 服务</el-button><el-button type="primary" @click="open = false">完成</el-button></div>
  </el-dialog>
</template>
