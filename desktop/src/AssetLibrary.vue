<script setup>
import { computed, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import MarkdownPreview from './MarkdownPreview.vue'
import { assetLimit, assetSize, assetTypes, defaultAssetGroups, groupLibraryAssets, libraryLimit, validAssetData } from './library-assets.js'

const props = defineProps({ book: { type: Object, required: true }, chapters: { type: Array, required: true } })
const emit = defineEmits(['jump'])
const assets = computed(() => props.book.creative.assets)
const groups = computed(() => [...new Set([...defaultAssetGroups, ...assets.value.map(asset => asset.group).filter(Boolean)])])
const query = ref('')
const group = ref('')
const sort = ref('name')
const editing = ref(null)
const selected = ref(null)
const error = ref('')
const importing = ref(false)
const picker = ref()
const filtered = computed(() => groupLibraryAssets(assets.value, query.value, group.value, sort.value))
const readImage = file => new Promise((resolve, reject) => {
  const reader = new FileReader()
  reader.onload = () => resolve(reader.result)
  reader.onerror = () => reject(reader.error)
  reader.readAsDataURL(file)
})

async function importFiles(files) {
  if (importing.value || !files.length) return
  const target = assets.value
  const importGroup = group.value
  importing.value = true
  error.value = ''
  try {
    const imported = []
    let used = target.reduce((bytes, asset) => bytes + assetSize(asset.data), 0)
    for (const file of files) {
      const type = /\.(md|markdown|txt)$/iu.test(file.name) ? 'markdown' : 'image'
      if (type === 'image' && !/^image\/(png|jpeg|webp|gif)$/u.test(file.type)) throw new Error(`不支持「${file.name}」的格式。`)
      if (file.size > assetLimit) throw new Error(`「${file.name}」超过单个资料的大小上限。`)
      const data = type === 'image' ? await readImage(file) : await file.text()
      if (!validAssetData(type, data)) throw new Error(`「${file.name}」编码后超过大小上限或格式不正确。`)
      used += assetSize(data)
      if (used > libraryLimit) throw new Error('本书资料附件总量超过 2 MiB。')
      imported.push({ id: crypto.randomUUID(), title: file.name.replace(/\.[^.]+$/u, '').slice(0, 100) || file.name,
        type, data, group: importGroup, summary: '', cardId: '', chapterId: '', context: false, updatedAt: Date.now() })
    }
    if (target !== assets.value) return
    target.push(...imported)
    query.value = ''
    if (imported.length === 1) openAsset(imported[0])
  } catch (failure) {
    error.value = failure.message
  } finally {
    importing.value = false
    if (picker.value) picker.value.value = ''
  }
}

function openAsset(asset) {
  selected.value = asset.id
  editing.value = { ...asset }
}

function save() {
  const asset = editing.value
  const title = asset.title.trim()
  if (!title) return
  const index = assets.value.findIndex(item => item.id === asset.id)
  if (index < 0) return
  assets.value[index] = { ...asset, title, group: asset.group.trim(), summary: asset.summary.trim(), updatedAt: Date.now() }
  editing.value = null
  selected.value = null
}

async function remove() {
  const asset = editing.value
  const target = assets.value
  try {
    await ElMessageBox.confirm(`从本书资料库删除「${asset.title}」？`, '删除资料', { confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch { return }
  if (target !== assets.value) return
  props.book.creative.assets = assets.value.filter(item => item.id !== asset.id)
  editing.value = null
  selected.value = null
}
</script>

<template>
  <section class="library-assets" @dragover.prevent @drop.prevent="importFiles($event.dataTransfer.files)">
    <div class="resource-heading"><h2>附件</h2><el-button type="primary" :loading="importing" @click="picker?.click()">导入资料</el-button></div>
    <div class="library-filter">
      <el-input v-model="query" aria-label="搜索附件" placeholder="搜索名称、分组或摘要" clearable />
      <el-select v-model="group" aria-label="附件分组" placeholder="全部分组">
        <el-option label="全部分组" value="" /><el-option v-for="name in groups" :key="name" :label="name" :value="name" />
      </el-select>
      <el-select v-model="sort" aria-label="附件排序"><el-option label="按名称" value="name" /><el-option label="最近更新" value="time" /></el-select>
      <input ref="picker" hidden type="file" multiple accept=".md,.markdown,.txt,image/png,image/jpeg,image/webp,image/gif" @change="importFiles($event.target.files)" />
    </div>
    <p v-if="error" class="field-error" role="alert">{{ error }}</p>
    <div class="library-asset-layout" :class="{ 'has-selection': editing }">
      <div>
        <div v-for="[name, items] in filtered" :key="name" class="library-asset-group">
          <h3>{{ name || '未分组' }} <small>{{ items.length }}</small></h3>
          <div class="library-asset-grid">
            <button v-for="asset in items" :key="asset.id" class="library-asset-tile" :aria-pressed="selected === asset.id" @click="openAsset(asset)">
              <img v-if="asset.type === 'image'" :src="asset.data" :alt="asset.title" loading="lazy" />
              <span v-else class="asset-document">Markdown</span>
              <strong>{{ asset.title }}</strong><small>{{ assetTypes[asset.type] }}</small>
            </button>
          </div>
        </div>
        <p v-if="!filtered.length" class="resource-empty">{{ assets.length ? '没有匹配的资料。' : '可拖入图片、Markdown 或纯文本资料。' }}</p>
      </div>
      <form v-if="editing" class="library-inspector creative-form" @submit.prevent="save">
        <div class="resource-heading"><strong>{{ assetTypes[editing.type] }}详情</strong><el-button text @click="editing = null; selected = null">关闭详情</el-button></div>
        <img v-if="editing.type === 'image'" class="library-image-preview" :src="editing.data" :alt="editing.title" />
        <details v-else open class="library-document-preview"><summary>文档预览</summary><MarkdownPreview :source="editing.data" /></details>
        <label class="field-stack">名称<el-input v-model="editing.title" maxlength="100" /></label>
        <label class="field-stack">分组<el-input v-model="editing.group" list="qingjian-asset-groups" maxlength="40" /></label>
        <datalist id="qingjian-asset-groups"><option v-for="name in groups" :key="name" :value="name"></option></datalist>
        <label class="field-stack">摘要<el-input v-model="editing.summary" type="textarea" :rows="3" maxlength="400" /></label>
        <label class="field-stack">关联资料卡<el-select v-model="editing.cardId" aria-label="关联资料卡" placeholder="无"><el-option value="" label="无" /><el-option v-for="card in book.creative.cards" :key="card.id" :value="card.id" :label="card.title" /></el-select></label>
        <label class="field-stack">关联章节<el-select v-model="editing.chapterId" aria-label="关联章节" placeholder="无"><el-option value="" label="无" /><el-option v-for="item in chapters" :key="item.chapter.id" :value="item.chapter.id" :label="item.label + ' ' + item.chapter.title" /></el-select></label>
        <el-checkbox v-model="editing.context" label="关联卡命中 AI 上下文时附上摘要" />
        <div class="resource-record-actions">
          <button v-if="editing.chapterId" type="button" class="text-action" @click="emit('jump', { chapterId: editing.chapterId })">跳到章节</button>
          <button type="button" class="text-action danger" @click="remove">删除资料</button>
          <el-button type="primary" native-type="submit" :disabled="!editing.title.trim()">保存资料</el-button>
        </div>
      </form>
    </div>
  </section>
</template>
