<script setup>
import { computed, ref } from 'vue'

const props = defineProps({ book: { type: Object, required: true }, matches: { type: Array, required: true }, busy: Boolean })
const emit = defineEmits(['cancel', 'confirm'])
const replace = ref(false)
const selected = ref(props.matches.length === 1 ? props.matches[0].id : '')
const target = computed(() => props.matches.find(({ id }) => id === selected.value))
const chapters = computed(() => props.book.volumes.reduce((count, volume) => count + volume.chapters.length, 0))
const date = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium' })
</script>

<template>
  <el-dialog :model-value="true" title="导入预览" width="min(470px, 94vw)"
    :show-close="!busy" :close-on-press-escape="!busy" :close-on-click-modal="false" @update:model-value="emit('cancel')">
    <h3 class="import-book-title">{{ book.title }}</h3>
    <dl class="import-statistics">
      <div><dt>章节</dt><dd>{{ chapters }}</dd></div>
      <div><dt>批注</dt><dd>{{ book.research.annotations.length }}</dd></div>
      <div><dt>伏笔</dt><dd>{{ book.research.foreshadows.length }}</dd></div>
    </dl>
    <template v-if="matches.length">
      <p class="dialog-description">书架已有 {{ matches.length }} 本同名书。</p>
      <el-radio-group v-model="replace" aria-label="同名书处理" :disabled="busy">
        <el-radio-button :value="false">保留副本</el-radio-button>
        <el-radio-button :value="true">覆盖原书</el-radio-button>
      </el-radio-group>
    </template>
    <template v-if="replace">
      <label class="field-stack import-target">选择要覆盖的书
        <el-select v-model="selected" aria-label="选择要覆盖的书" placeholder="请选择" :disabled="busy">
          <el-option v-for="match in matches" :key="match.id" :value="match.id"
            :label="`${match.chapters} 章 · ${date.format(match.updatedAt)}`" />
        </el-select>
      </label>
      <p class="dialog-description">原书将保存为还原点。</p>
    </template>
    <div class="dialog-actions">
      <el-button :disabled="busy" @click="emit('cancel')">取消</el-button>
      <el-button :type="replace ? 'danger' : 'primary'" :loading="busy" :disabled="replace && !target"
        @click="emit('confirm', replace ? target.id : '')">{{ replace ? '覆盖并打开' : '导入并打开' }}</el-button>
    </div>
  </el-dialog>
</template>
