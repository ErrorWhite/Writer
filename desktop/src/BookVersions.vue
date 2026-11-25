<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { localRequest } from './local-service.js'

const props = defineProps({ book: { type: Object, required: true } })
const emit = defineEmits(['restore'])
const versions = ref([])
const busy = ref(false)
const url = computed(() => '/api/book/versions?id=' + encodeURIComponent(props.book.id))
const date = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short' })

async function load() {
  try {
    versions.value = (await localRequest(url.value)).versions
  } catch (error) {
    ElMessage.error(error.message)
  }
}

async function create() {
  busy.value = true
  try {
    versions.value = (await localRequest(url.value, { method: 'POST', body: props.book })).versions
    ElMessage.success('已创建全书还原点')
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    busy.value = false
  }
}

async function restore(version) {
  try {
    await ElMessageBox.confirm('回退整本书到这份备份？当前书稿会先创建一个还原点。', '回退全书', { confirmButtonText: '回退', cancelButtonText: '取消' })
  } catch { return }
  busy.value = true
  try {
    const endpoint = url.value
    const book = await localRequest(endpoint + '&version=' + encodeURIComponent(version.id))
    await localRequest(endpoint, { method: 'POST', body: props.book })
    emit('restore', book)
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="book-versions">
    <div class="resource-heading"><div><strong>全书还原点</strong><p>保留最近 12 份</p></div><el-button size="small" :loading="busy" @click="create">创建还原点</el-button></div>
    <ul v-if="versions.length" class="ai-account-list version-list">
      <li v-for="version in versions" :key="version.id"><span>{{ date.format(version.at) }} · {{ version.title }}</span><el-button text size="small" :disabled="busy" @click="restore(version)">回退</el-button></li>
    </ul>
    <p v-else class="resource-empty">暂无还原点。</p>
  </section>
</template>
