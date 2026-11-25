<script setup>
import { ref, watch } from 'vue'
import { BookOpen, ImagePlus } from '@lucide/vue'
import { bookCover, starterBookId } from './shelf-store.js'
import defaultCover from './cover.webp'

const props = defineProps({ book: { type: Object, default: null }, saving: Boolean })
const open = defineModel('open', { type: Boolean })
const emit = defineEmits(['save'])
const title = ref('')
const cover = ref('')
const upload = ref()
const error = ref('')
const converting = ref(false)
let openVersion = 0

watch(open, (visible) => {
  openVersion += 1
  converting.value = false
  if (visible) {
    title.value = props.book?.title || ''
    cover.value = bookCover(props.book)
    error.value = ''
  }
}, { immediate: true })

async function chooseCover(event) {
  const [file] = event.target.files
  event.target.value = ''
  if (!file) return
  const version = openVersion
  converting.value = true
  error.value = ''
  let bitmap
  try {
    if (!['image/webp', 'image/jpeg', 'image/png'].includes(file.type) || file.size > 12 * 1024 * 1024) {
      throw new Error('请选择 12 MB 以内的 JPG、PNG 或 WebP 图片。')
    }
    bitmap = await createImageBitmap(file)
    const width = Math.min(bitmap.width, bitmap.height * 2 / 3)
    const height = width * 3 / 2
    const canvas = document.createElement('canvas')
    canvas.width = 240
    canvas.height = 360
    const context = canvas.getContext('2d', { alpha: false })
    context.fillStyle = '#f4f7fd'
    context.fillRect(0, 0, 240, 360)
    context.drawImage(bitmap, (bitmap.width - width) / 2, (bitmap.height - height) / 2, width, height, 0, 0, 240, 360)
    const image = canvas.toDataURL('image/webp', 0.82)
    if (!bookCover({ cover: image })) throw new Error('封面过大，请选择另一张图片。')
    if (version === openVersion) cover.value = image
  } catch (reason) {
    if (version === openVersion) error.value = reason instanceof DOMException ? '无法读取这张图片。' : reason.message
  } finally {
    bitmap?.close()
    if (version === openVersion) converting.value = false
  }
}

function save() {
  if (title.value.trim() && !converting.value && !props.saving) emit('save', { title: title.value.trim(), cover: cover.value })
}
</script>

<template>
  <el-dialog v-model="open" :title="book ? '编辑书籍' : '创建书'" width="min(450px, 92vw)" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item label="书名"><el-input v-model="title" placeholder="输入书名" maxlength="60" autofocus /></el-form-item>
      <el-form-item label="封面">
        <div class="cover-editor">
          <div class="cover-editor-preview">
            <img v-if="cover || book?.id === starterBookId" :src="cover || defaultCover" alt="封面预览" />
            <BookOpen v-else :size="24" />
          </div>
          <div class="cover-editor-controls">
            <input ref="upload" type="file" accept="image/jpeg,image/png,image/webp" hidden @change="chooseCover" />
            <el-button :icon="ImagePlus" :loading="converting" :disabled="saving" @click="upload.click()">选择图片</el-button>
            <el-button v-if="cover" text @click="cover = ''">恢复默认封面</el-button>
            <small>JPG、PNG 或 WebP，裁为 2:3 竖版。</small>
          </div>
        </div>
        <p v-if="error" class="field-error" role="alert">{{ error }}</p>
      </el-form-item>
      <div class="dialog-actions"><el-button :disabled="saving" @click="open = false">取消</el-button><el-button type="primary" native-type="submit" :loading="saving" :disabled="!title.trim() || converting">{{ book ? '保存' : '创建书' }}</el-button></div>
    </el-form>
  </el-dialog>
</template>
