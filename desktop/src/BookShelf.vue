<script setup>
import { ref } from 'vue'
import { BookOpen, Ellipsis, FileUp, LayoutGrid, List, Plus } from '@lucide/vue'
import { bookCover, starterBookId } from './shelf-store.js'

defineProps({ books: { type: Array, required: true }, activeId: { type: String, required: true }, busy: Boolean, importing: Boolean, server: Boolean })
const layout = defineModel('layout', { type: String, default: 'cards' })
const emit = defineEmits(['create', 'open', 'manage', 'import', 'server'])
const backupFile = ref()

function selectBackup(event) {
  const [file] = event.target.files
  event.target.value = ''
  if (file) emit('import', file)
}
</script>

<template>
  <main class="bookshelf-page">
    <section class="page-frame bookshelf-content">
      <header class="page-heading bookshelf-heading">
        <h1>我的书架</h1>
        <div class="bookshelf-actions">
          <div class="shelf-layout-switch" role="group" aria-label="书架布局">
            <el-button :icon="LayoutGrid" aria-label="卡片布局" title="卡片布局" :aria-pressed="layout === 'cards'" @click="layout = 'cards'" />
            <el-button :icon="List" aria-label="横排布局" title="横排布局" :aria-pressed="layout === 'rows'" @click="layout = 'rows'" />
          </div>
          <input ref="backupFile" type="file" accept=".json,application/json" hidden @change="selectBackup" />
          <el-button v-if="server" @click="emit('server')">服务器书架</el-button>
          <el-button :icon="FileUp" :loading="importing" aria-label="导入全书 JSON" @click="backupFile.click()">导入 JSON</el-button>
          <el-button type="primary" :icon="Plus" @click="emit('create')">创建书</el-button>
        </div>
      </header>
      <div v-if="books.length" class="bookshelf-grid" :class="{ 'bookshelf-rows': layout === 'rows' }">
        <article v-for="book in books" :key="book.id" class="shelf-book">
          <button class="shelf-book-open" :disabled="busy" @click="emit('open', book.id)">
            <span class="shelf-cover" :class="{ 'sample-cover': book.id === starterBookId }">
              <img v-if="bookCover(book)" :src="bookCover(book)" alt="" />
              <BookOpen v-else-if="book.id !== starterBookId" :size="24" :stroke-width="1.35" />
            </span>
            <span class="shelf-book-info">
              <strong>{{ book.title }}</strong>
              <small>{{ book.chapters }} 章<span v-if="book.genre"> · {{ book.genre }}</span></small>
              <span v-if="book.id === activeId" class="shelf-current">最近打开</span>
            </span>
          </button>
          <el-dropdown trigger="click" placement="bottom-end" class="shelf-book-menu" @command="emit('manage', $event, book)">
            <button class="icon-button" :aria-label="`管理${book.title}`" title="管理书籍"><Ellipsis :size="17" /></button>
            <template #dropdown><el-dropdown-menu>
              <el-dropdown-item command="edit">编辑书名和封面</el-dropdown-item>
              <el-dropdown-item command="export-json">导出全书 · 可导入 JSON</el-dropdown-item>
              <el-dropdown-item command="export-txt">导出全书 · TXT</el-dropdown-item>
              <el-dropdown-item command="export-epub">导出全书 · EPUB</el-dropdown-item>
              <el-dropdown-item command="delete" class="delete-chapter-item" divided>删除书籍</el-dropdown-item>
            </el-dropdown-menu></template>
          </el-dropdown>
        </article>
      </div>
      <p v-else class="shelf-empty">创建第一部作品，开始写作。</p>
    </section>
  </main>
</template>
