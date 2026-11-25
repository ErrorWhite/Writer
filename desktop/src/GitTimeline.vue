<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { diffLines } from 'diff'
import { localRequest } from './local-service.js'

const props = defineProps({ book: { type: Object, required: true } })
const emit = defineEmits(['restore'])
const endpoint = '/api/book/git?id=' + encodeURIComponent(props.book.id)
const versionEndpoint = '/api/book/versions?id=' + encodeURIComponent(props.book.id)
const history = ref({ branches: [], commits: [], heads: {} })
const branch = ref('main')
const message = ref('')
const branchName = ref('')
const selected = ref(null)
const changes = ref([])
const selectedFile = ref('')
const comparison = ref(null)
const busy = ref(false)
const error = ref('')
const date = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short' })
const status = { added: '新增', modified: '修改', deleted: '删除' }
const range = computed(() => {
  const byOid = new Map(history.value.commits.map(commit => [commit.oid, commit]))
  const commits = []
  for (let oid = history.value.heads?.[branch.value]; byOid.has(oid);) {
    const commit = byOid.get(oid)
    commits.push(commit)
    oid = commit.parents[0]
  }
  return commits.slice(0, 60)
})
const diff = computed(() => comparison.value && diffLines(comparison.value.before, comparison.value.after, { timeout: 400 }))
const commitUrl = oid => endpoint + '&oid=' + encodeURIComponent(oid)

async function run(operation) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await operation()
  } catch (failure) {
    error.value = failure.message
  } finally {
    busy.value = false
  }
}

const refresh = () => run(async () => { history.value = await localRequest(endpoint) })
function chooseBranch(name) {
  branch.value = name
  selected.value = null
  changes.value = []
  selectedFile.value = ''
  comparison.value = null
}
const commit = () => run(async () => {
  history.value = await localRequest(endpoint, { method: 'POST', body: { type: 'commit', branch: branch.value,
    message: message.value, expected: history.value.heads?.[branch.value] || null, book: props.book } })
  message.value = ''
  ElMessage.success('已提交书稿')
})
const fork = () => run(async () => {
  const name = branchName.value.trim()
  history.value = await localRequest(endpoint, { method: 'POST', body: { type: 'branch', name, oid: selected.value.oid } })
  chooseBranch(name)
  branchName.value = ''
})
const inspect = commit => run(async () => {
  selected.value = commit
  changes.value = []
  selectedFile.value = ''
  comparison.value = null
  changes.value = await localRequest(commitUrl(commit.oid))
})
const compare = path => run(async () => {
  selectedFile.value = path
  comparison.value = null
  comparison.value = await localRequest(commitUrl(selected.value.oid) + '&file=' + encodeURIComponent(path))
})
const restore = () => run(async () => {
  try {
    await ElMessageBox.confirm('载入此提交的整本书稿？当前稿会先保存为全书还原点。', '载入 Git 提交', {
      confirmButtonText: '载入书稿', cancelButtonText: '取消',
    })
  } catch { return }
  const snapshot = await localRequest(commitUrl(selected.value.oid) + '&restore')
  await localRequest(versionEndpoint, { method: 'POST', body: props.book })
  emit('restore', snapshot)
})
onMounted(refresh)
</script>

<template>
  <section class="git-timeline">
    <div class="resource-heading">
      <div><strong>Git 时间线</strong><p>在选定分支提交当前稿，可逐文件查看改动。</p></div>
      <el-button size="small" :disabled="busy" @click="refresh">刷新时间线</el-button>
    </div>
    <form class="git-controls" @submit.prevent="commit">
      <el-select :model-value="branch" aria-label="Git 分支" :disabled="busy" @update:model-value="chooseBranch">
        <el-option v-for="name in history.branches.length ? history.branches : ['main']" :key="name" :label="name" :value="name" />
      </el-select>
      <el-input v-model="message" placeholder="提交说明" aria-label="提交说明" maxlength="180" :disabled="busy" />
      <el-button type="primary" native-type="submit" :disabled="busy || !message.trim()">提交当前稿</el-button>
    </form>
    <p v-if="error" class="field-error" role="alert">{{ error }}</p>
    <div v-if="range.length" class="git-layout">
      <ul class="git-commits">
        <li v-for="node in range" :key="node.oid">
          <button :aria-pressed="selected?.oid === node.oid" :disabled="busy" @click="inspect(node)">
            <strong>{{ node.message }}</strong><small>{{ node.oid.slice(0, 7) }} · {{ date.format(node.at) }}</small>
          </button>
        </li>
      </ul>
      <div v-if="selected" class="git-inspector">
        <div class="resource-heading"><strong>提交改动</strong><el-button size="small" :disabled="busy" @click="restore">载入此提交</el-button></div>
        <ul class="git-files">
          <li v-for="file in changes" :key="file.path">
            <button class="text-action" :aria-pressed="selectedFile === file.path" :disabled="busy" @click="compare(file.path)">{{ file.title }}</button>
            <small>{{ status[file.status] }}</small>
          </li>
        </ul>
        <p v-if="!changes.length && !busy" class="resource-empty">此提交只更改了书稿内部数据。</p>
        <div v-if="comparison" class="git-diff" aria-label="提交差异">
          <p v-if="comparison.truncated">文件较长，显示开头 12 万字符。</p>
          <pre v-if="diff"><template v-for="(part, index) in diff" :key="index"><span :class="{ added: part.added, removed: part.removed }">{{ part.added ? '+ ' : part.removed ? '− ' : '' }}{{ part.value }}</span></template></pre>
          <p v-else>改动较多，无法在预览时间内完成比较。</p>
        </div>
        <form class="git-controls git-branch" @submit.prevent="fork">
          <el-input v-model="branchName" aria-label="新分支名" placeholder="新分支名" maxlength="48" :disabled="busy" />
          <el-button native-type="submit" :disabled="busy || !branchName.trim()">从此提交建分支</el-button>
        </form>
      </div>
      <p v-else class="resource-empty">选择提交查看改动。</p>
    </div>
    <p v-else-if="!busy" class="resource-empty">当前分支还没有提交。</p>
  </section>
</template>
