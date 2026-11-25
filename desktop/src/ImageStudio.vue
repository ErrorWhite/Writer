<script setup>
import { onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { localRequest } from './local-service.js'
import { assetSize, libraryLimit } from './library-assets.js'
import { preferences } from './studio-preferences.js'
const props = defineProps({ book: { type: Object, required: true }, chapter: { type: Object, required: true } })
const open = defineModel('open', { type: Boolean })
const type = ref('scene')
const prompt = ref('')
const title = ref('')
const image = ref('')
const busy = ref(false)
let request
async function generate() {
  if (busy.value || !prompt.value.trim() || !preferences.images) return
  busy.value = true
  image.value = ''
  const controller = new AbortController()
  request = controller
  try {
    const result = await localRequest('/api/ai/image', { method: 'POST', signal: controller.signal,
      body: { cover: type.value === 'cover', prompt: prompt.value.trim() } })
    if (!controller.signal.aborted) image.value = result.image
  } catch (error) { if (!controller.signal.aborted) ElMessage.error(error.message) }
  finally { if (request === controller) { busy.value = false; request = undefined } }
}
async function useImage() {
  const bookId = props.book.id
  const chapterId = props.chapter.id
  const cover = type.value === 'cover'
  try {
    const bytes = Uint8Array.from(atob(image.value.split(',')[1]), char => char.charCodeAt(0))
    const bitmap = await createImageBitmap(new Blob([bytes], { type: 'image/webp' }))
    const canvas = document.createElement('canvas')
    canvas.width = cover ? 320 : 1040
    canvas.height = cover ? 480 : Math.round(canvas.width * bitmap.height / bitmap.width)
    canvas.getContext('2d').drawImage(bitmap, 0, 0, canvas.width, canvas.height)
    bitmap.close()
    const data = canvas.toDataURL('image/webp', .79)
    if (props.book.id !== bookId) return
    if (cover) {
      if (data.length > 120000) throw new Error('生成图片超出封面体积限制。')
      props.book.cover = data
    } else {
      const assets = props.book.creative.assets
      if (data.length > 640 * 1024 || assets.reduce((sum, item) => sum + assetSize(item.data), 0) + data.length > libraryLimit) {
        throw new Error('本书附件空间不足，请清理附件后再保存。')
      }
      assets.push({ id: crypto.randomUUID(), title: title.value.trim() || props.chapter.title + ' · 配图', data, type: 'image',
        group: '配图', summary: '', context: false, cardId: '', chapterId, updatedAt: Date.now() })
    }
    open.value = false
    ElMessage.success(cover ? '封面已更新' : '配图已保存到工作台附件')
  } catch (error) { ElMessage.error(error.message) }
}
onUnmounted(() => request?.abort())
</script>
<template>
  <el-dialog v-model="open" title="生成配图与封面" width="min(640px, 95vw)">
    <el-radio-group v-model="type" :disabled="busy || Boolean(image)"><el-radio-button value="scene">章节配图</el-radio-button><el-radio-button value="cover">全书封面</el-radio-button></el-radio-group>
    <label class="field-stack">画面描述<el-input v-model="prompt" type="textarea" :rows="4" maxlength="2500" placeholder="描述人物、场景、构图与画风。需要书名时请写入描述。" /></label>
    <label v-if="type === 'scene'" class="field-stack">附件名称<el-input v-model="title" maxlength="100" /></label>
    <img v-if="image" class="generated-image-preview" :src="image" alt="生成候选" />
    <div class="dialog-actions"><el-button v-if="busy" @click="request?.abort()">取消生成</el-button><el-button :disabled="!prompt.trim()" :loading="busy" @click="generate">{{ image ? '重新生成' : '生成候选' }}</el-button><el-button type="primary" :disabled="!image" @click="useImage">{{ type === 'cover' ? '设为封面' : '保存到附件' }}</el-button></div>
  </el-dialog>
</template>
