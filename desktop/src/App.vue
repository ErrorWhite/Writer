<script setup>
import { computed, defineAsyncComponent, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowDownToLine, ArrowLeft, ArrowUp, BookOpen, Check, ChevronDown, ChevronLeft, ChevronRight,
  CircleCheck, CloudAlert, Database, Ellipsis, FilePenLine, FilePlus, FileText, Focus, FolderPlus,
  KeyRound, Layers3, LayoutList, LogOut, MessageCircle, PanelLeftClose, PanelLeftOpen, PanelRightClose, PanelRightOpen, Pause, PenLine, Play, Plus,
  Minus, Redo2, Search, Send, Settings2, Sparkles, Square, Target, Type, Undo2, X,
} from '@lucide/vue'
import { chapterLabel, createManuscript, downloadBookBackup, downloadText, formatCount, hasBrowserBackup, importManuscript, loadManuscript, volumeLabel, wordCount } from './manuscript.js'
import { localRequest } from './local-service.js'
import { acceptBatchReview, paragraphSpans, restoreParagraphRevision, revisionSnapshot, syncParagraphs } from './revisions.js'
import { dividerStyles, editorDividerBlocks, generatedManuscript, insertSceneDivider } from './scene-dividers.js'
import { useBookBackup } from './use-book-backup.js'
import { androidCall, useAndroidBookBackup } from './android-workbench.js'
import { bookCover, bookTitleKey, readBrowserBook, readRetiredBooks, readShelf, rememberBook, retireBrowserBooks, savedBookId, starterBookId, summarizeBook } from './shelf-store.js'
import AiConnection from './AiConnection.vue'
import AiSetupDialog from './AiSetupDialog.vue'
import SkillLibrary from './SkillLibrary.vue'
import BookShelf from './BookShelf.vue'
import SceneDivider from './SceneDivider.vue'
import EditorScrollHandle from './EditorScrollHandle.vue'
import EditorWidthHandle from './EditorWidthHandle.vue'
import InlineManuscript from './InlineManuscript.vue'
import { markupBlocks } from './manuscript-markup.js'
import { applyAccent, loadPreferences, preferences, savePreferences } from './studio-preferences.js'
import iconUrl from './qingjian-icon.png'
import { providerReady } from './context-options.js'
import { excerpt } from './book-profile.js'
import { recoverSnapshot } from './book-journal.js'
import { aiActivity, aiIntentLabels } from './ai-activity.js'
import { markdownCommands, markdownSnippet, preservesMarkup, sameProse } from './ai-review.js'
import { bookMaterial } from './book-profile.js'
import { creativeMaterial, evidenceMaterial } from './creative-tools.js'
import { ensureContextBudget, withContext } from './context-options.js'
import { activeSkills, skillRequest } from './writing-skills.js'
import { syncStoryArchive, undoStoryArchive } from './story-handoff.js'

const StudioSettings = defineAsyncComponent(() => import('./StudioSettings.vue'))
const WorldBook = defineAsyncComponent(() => import('./WorldBook.vue'))
const FullscreenReview = defineAsyncComponent(() => import('./FullscreenReview.vue'))
const UsageHeatmap = defineAsyncComponent(() => import('./UsageHeatmap.vue'))
const ImageStudio = defineAsyncComponent(() => import('./ImageStudio.vue'))
const StoryDesk = defineAsyncComponent(() => import('./StoryDesk.vue'))
const BookDetailsDialog = defineAsyncComponent(() => import('./BookDetailsDialog.vue'))
const AiWorkspace = defineAsyncComponent(() => import('./AiWorkspace.vue'))
const ChangePasswordDialog = defineAsyncComponent(() => import('./ChangePasswordDialog.vue'))
const ContextDialog = defineAsyncComponent(() => import('./ContextDialog.vue'))
const BookVersions = defineAsyncComponent(() => import('./BookVersions.vue'))
const ResearchDrawer = defineAsyncComponent(() => import('./ResearchDrawer.vue'))
const RoleplayRoom = defineAsyncComponent(() => import('./RoleplayRoom.vue'))
const AssetLibrary = defineAsyncComponent(() => import('./AssetLibrary.vue'))
const LibraryAi = defineAsyncComponent(() => import('./LibraryAi.vue'))
const GitTimeline = defineAsyncComponent(() => import('./GitTimeline.vue'))
const BookImportPreview = defineAsyncComponent(() => import('./BookImportPreview.vue'))
const CreativeDesk = defineAsyncComponent(() => import('./CreativeDesk.vue'))

const props = defineProps({
  sessionRequired: Boolean, desktop: Boolean, webview: Boolean, android: Object,
  initialAccounts: { type: Object, required: true },
})
const emit = defineEmits(['sign-out', 'accounts-change', 'android-conflict'])
const systemTheme = window.matchMedia('(prefers-color-scheme: dark)')
const nightMode = ref(props.android?.dark ?? systemTheme.matches)
const followSystem = event => { if (!props.android) nightMode.value = event.matches }
systemTheme.addEventListener('change', followSystem)
watch(nightMode, (dark) => {
  document.documentElement.classList.toggle('dark', dark)
  if (props.webview) window.chrome.webview.postMessage(dark ? 'dark' : 'light')
}, { immediate: true })
watch(() => preferences.accent, applyAccent, { immediate: true })
let nativeThemeTimer
if (props.desktop && !props.webview) watch([nightMode, () => preferences.accent], ([dark, accent]) => {
  clearTimeout(nativeThemeTimer)
  nativeThemeTimer = setTimeout(() => localRequest('/api/desktop/theme', { method: 'PUT', body: { dark, accent } }).catch(() => {}), 70)
}, { immediate: true })
onUnmounted(() => systemTheme.removeEventListener('change', followSystem))
onUnmounted(() => clearTimeout(nativeThemeTimer))

if (props.webview) {
  let idle = false
  let idleTimer
  const actions = ['focus', 'keydown', 'pointerdown', 'wheel']
  const markActive = () => {
    clearTimeout(idleTimer)
    if (idle) window.chrome.webview.postMessage('active')
    idle = false
    idleTimer = setTimeout(() => { idle = true; window.chrome.webview.postMessage('idle') }, 10000)
  }
  for (const action of actions) window.addEventListener(action, markActive, { passive: true })
  markActive()
  onUnmounted(() => {
    clearTimeout(idleTimer)
    for (const action of actions) window.removeEventListener(action, markActive)
  })
}

const manuscript = reactive(loadManuscript(props.android?.book ?? (readBrowserBook(savedBookId()) || undefined)))
const {
  state: backupState, status: saveStatus, saveSoon, flush, checkpoint, discard, stateFor,
  links: syncLinks, synchronization, sync: studioSync, enableSync, disableSync, deleteSynced, pollSync,
} = props.android ? useAndroidBookBackup(manuscript, props.android) : useBookBackup(manuscript, {
  desktop: props.desktop,
  normalize: loadManuscript,
  applyRemote(id, book) {
    if (!book) retireBooks([id])
    else if (hasCurrentBook.value && manuscript.id === id) Object.assign(manuscript, book)
    refreshShelf()
  },
  syncChanged: (message) => ElMessage.warning(message),
})
const syncEndpoint = ref('')
const syncUsername = ref('')
const syncPassword = ref('')
const syncBusy = ref(false)
const syncTarget = ref('')
const syncShelf = ref(false)
const syncBooks = ref([])

async function refreshSyncShelf() {
  const result = await studioSync.list()
  if (!result.ok) throw new Error(result.status === 401 ? '服务器会话已过期，请重新连接。' : '无法读取服务器书架。')
  syncBooks.value = result.books || []
}

function openSyncShelf() {
  syncShelf.value = true
  if (syncTarget.value) refreshSyncShelf().catch(error => ElMessage.error(error.message))
}

async function openSyncBook(id) {
  if (syncBusy.value) return
  syncBusy.value = true
  try {
    await enableSync(id)
    await openBook(id)
    syncShelf.value = false
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    syncBusy.value = false
  }
}

async function configureSync(action) {
  if (syncBusy.value) return
  syncBusy.value = true
  try {
    if (action === 'sign-in') {
      const result = await studioSync.signIn({
        endpoint: syncEndpoint.value, username: syncUsername.value, password: syncPassword.value,
      })
      if (!result.ok) throw new Error(result.error || '服务器登录失败。')
      syncTarget.value = result.endpoint
      await flush()
      await pollSync()
      if (syncShelf.value) await refreshSyncShelf()
    } else if (action === 'sign-out') {
      await studioSync.signOut()
      syncTarget.value = ''
    } else if (action === 'disable') disableSync(manuscript.id)
    else await enableSync(manuscript.id)
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    syncPassword.value = ''
    syncBusy.value = false
  }
}
let closingWindow = false
async function windowAction(action) {
  if (action === 'close') {
    if (closingWindow) return
    closingWindow = true
    try {
      await flush()
      if (['local', 'failed'].includes(backupState.value)) await ElMessageBox.confirm('当前书稿还未写入磁盘。确定退出吗？', '书稿未保存', { type: 'warning', confirmButtonText: '退出', cancelButtonText: '继续写作' })
    } catch { return }
    finally { closingWindow = false }
  }
  window.chrome.webview.postMessage(action)
}
const retiredBooks = reactive(new Set(props.android ? [] : readRetiredBooks()))
const hasCurrentBook = ref(!retiredBooks.has(manuscript.id))
const shelfOpen = ref(window.location.hash === '#shelf' || !hasCurrentBook.value)
const openingBook = ref(false)
const bookEditorOpen = ref(false)
const editingBook = ref(null)
const savingBookDetails = ref(false)
const importingBook = ref(false)
const importPreview = ref(null)
const passwordOpen = ref(false)
const autoFullscreen = ref(readFullscreenPreference())
const shelfBooks = ref(readShelf())
const importMatches = computed(() => importPreview.value
  ? shelfBooks.value.filter(({ title }) => bookTitleKey(title) === bookTitleKey(importPreview.value.title)) : [])
let hydrated = false
const volumeEntries = computed(() => {
  let index = 0
  return manuscript.volumes.map((volume) => ({
    volume,
    entries: volume.chapters.map((chapter, position) => ({
      chapter, volume, position, index, label: chapterLabel(++index),
    })),
  }))
})
const chapterEntries = computed(() => volumeEntries.value.flatMap(({ entries }) => entries))
const activeIndex = computed(() => {
  const index = chapterEntries.value.findIndex(({ chapter }) => chapter.id === manuscript.activeId)
  return Math.max(0, index)
})
const activeEntry = computed(() => chapterEntries.value[activeIndex.value])
const activeChapter = computed(() => activeEntry.value.chapter)
const activeVolume = computed(() => activeEntry.value.volume)
const activeLabel = computed(() => activeEntry.value.label)
const wordCounts = new WeakMap()
function countChapter(chapter) {
  const cached = wordCounts.get(chapter)
  if (cached?.text === chapter.text) return cached.count
  const count = wordCount(chapter.text)
  wordCounts.set(chapter, { text: chapter.text, count })
  return count
}
const chapterWords = computed(() => countChapter(activeChapter.value))
const bookWords = computed(() => chapterEntries.value.reduce((sum, { chapter }) => sum + countChapter(chapter), 0))
const chapterGoal = computed(() => activeChapter.value.goal || manuscript.goal)
const progress = computed(() => Math.min(100, Math.floor(chapterWords.value / chapterGoal.value * 100)))

const sections = [
  { id: 'chapters', label: '章节' },
  { id: 'outline', label: '大纲' },
  { id: 'notes', label: '设定' },
]
const papers = [
  { id: 'white', label: '白纸', color: '#ffffff' },
  { id: 'cream', label: '暖白', color: '#faf5e9' },
  { id: 'blue', label: '雾蓝', color: '#edf4fc' },
]

const section = ref('chapters')
const chapterFilter = ref('')
const mobileAssistant = ref(false)
const mobileNavigation = ref(false)
const viewportWidth = ref(window.innerWidth)
const compactNavigation = computed(() => viewportWidth.value <= 720)
const compactAssistant = computed(() => viewportWidth.value <= 1100)
const navigationVisible = computed(() => compactNavigation.value ? mobileNavigation.value : manuscript.navigationOpen)
const assistantAiMode = computed(() => section.value === 'chapters' && manuscript.aiMode)
const assistantVisible = computed(() => assistantAiMode.value && (compactAssistant.value ? mobileAssistant.value : manuscript.assistantOpen))
const focusMode = ref(false)
const collapsedVolumes = reactive(new Set())
const preferredVolumeId = ref(activeVolume.value.id)
const backupOpen = ref(false)
const backupTab = ref('versions')
const connectionOpen = ref(false)
const settingsOpen = ref(false)
const profileOpen = ref(false)
const profileOnly = ref(false)
const skillsOpen = ref(false)
const researchOpen = ref(false)
const roleplayOpen = ref(false)
const researchTab = ref('annotations')
const researchAnchor = ref(null)
let readingPosition
const sourceMode = ref(false)
const activeMarkdown = computed(() => section.value === 'chapters' ? activeChapter.value.text : manuscript[section.value])
const activeMarkup = computed(() => !sourceMode.value && !assistantAiMode.value ? markupBlocks(activeMarkdown.value) : [])
const revisionJump = ref(null)
const connection = computed(() => ({ ...props.initialAccounts, provider: preferences.provider }))
const pendingReview = ref(null)
const activeReview = computed(() => pendingReview.value || (activeChapter.value.batchReview?.length ? {
  kind: 'batch', title: '选段重新生成 · 对比', items: activeChapter.value.batchReview,
} : null))
const reviewOpen = ref(false)
const batchSelection = ref([])
const batchPrompt = ref('')
const studioBusy = ref('')
const imageOpen = ref(false)
const volumeDirection = ref('')
let aiController
let paragraphDrag = null
watch(() => connectionOpen.value || (!shelfOpen.value && assistantAiMode.value), async (needed) => {
  if (!needed || !props.desktop || connection.value.cli) return
  try {
    emit('accounts-change', await localRequest('/api/ai/accounts', { signal: AbortSignal.timeout(10000) }))
  } catch (error) {
    if (connectionOpen.value) ElMessage.error(error.message)
  }
})
const aiEditor = ref()
const aiReview = computed(() => aiEditor.value?.review)
const editor = ref()
const inlineEditor = ref()
const showInlineMarkup = computed(() => !sourceMode.value && (activeMarkup.value.length || inlineEditor.value?.editingBlock))
const editorScroll = ref()
const paperWidthKey = 'qingjian.paper-width'
const paperWidth = ref(754)
try {
  const saved = Number(localStorage.getItem(paperWidthKey))
  if (saved >= 280 && saved <= 1440) paperWidth.value = Math.round(saved)
} catch { /* Browser storage may be unavailable. */ }
function savePaperWidth(width) {
  try { localStorage.setItem(paperWidthKey, String(width)) } catch { /* Keep the current width in memory. */ }
}
const selection = ref(null)
const visualBlocks = computed(() => editorDividerBlocks(activeChapter.value.text))
const newTitle = ref('')
const nextFromChapterId = ref('')
const editTitle = ref('')
const chapterToEdit = ref('')
const volumeToEdit = ref('')
const destinationVolumeId = ref('')
const nextDirection = ref('')
const handoffDraft = ref(null)
const handoffBase = ref('')
const handoffChapter = ref('')
const backfill = reactive({ running: false, done: 0, total: 0, failures: 0 })
let backfillController
const newChapterPredecessor = computed(() => {
  const explicit = chapterEntries.value.find(({ chapter }) => chapter.id === nextFromChapterId.value)
  if (explicit) return explicit.chapter
  const index = manuscript.volumes.findIndex(volume => volume.id === destinationVolumeId.value)
  return manuscript.volumes.slice(0, index + 1).flatMap(volume => volume.chapters).at(-1)
})
watch(newChapterPredecessor, chapter => {
  if (dialog.value === 'new') nextDirection.value = chapter?.handoff?.directions?.[0]?.detail || ''
}, { flush: 'sync' })
const volumeTitle = ref('')
const dialog = ref('')
const settings = reactive({ goal: 500 })
const search = reactive({ query: '', replacement: '' })
const searchCount = computed(() => search.query ? activeChapter.value.text.split(search.query).length - 1 : 0)
const searchMatch = ref(null)
const matchMarker = ref()
const prompt = ref('')
const history = reactive(new Map())
let previousInputAt = 0
let previousInputType = ''

const filterQuery = computed(() => chapterFilter.value.replace(/\s+/g, '').toLowerCase())
const visibleVolumes = computed(() => {
  if (!filterQuery.value) return volumeEntries.value
  return volumeEntries.value
    .map(({ volume, entries }) => ({
      volume,
      entries: entries.filter(({ chapter, label }) => `${label}${chapter.title}`.replace(/\s+/g, '').toLowerCase().includes(filterQuery.value)),
    }))
    .filter(({ entries }) => entries.length)
})
const chapterHistory = computed(() => history.get(activeChapter.value.id))
const moveDestinations = computed(() => {
  const source = chapterEntries.value.find(({ chapter }) => chapter.id === chapterToEdit.value)?.volume
  return manuscript.volumes.filter((volume) => volume.id !== source?.id)
})
const nextVolumeTitle = computed(() => {
  let index = manuscript.volumes.length + 1
  while (manuscript.volumes.some(({ title }) => title === volumeLabel(index))) index += 1
  return volumeLabel(index)
})

watch(() => activeChapter.value.text, () => {
  if (activeChapter.value.paragraphs && !manuscript.aiMode) syncParagraphs(activeChapter.value)
})
const chapterSaveWatches = new Map()
watch(() => chapterEntries.value.map(({ chapter }) => chapter), chapters => {
  const live = new Set(chapters)
  for (const [chapter, stop] of chapterSaveWatches) {
    if (!live.has(chapter)) { stop(); chapterSaveWatches.delete(chapter) }
  }
  for (const chapter of chapters) {
    if (!chapterSaveWatches.has(chapter)) chapterSaveWatches.set(chapter, watch(chapter, saveCurrentBook))
  }
  saveCurrentBook()
}, { immediate: true })
watch(() => {
  const { volumes, ...bookFields } = manuscript
  return [bookFields, volumes.map(({ chapters, ...volumeFields }) => volumeFields)]
}, saveCurrentBook, { deep: true })
watch(backupState, (state) => {
  if (state === 'deleted') retireBooks([manuscript.id])
  if (state === 'conflict' && props.android) emit('android-conflict')
})
watch(autoFullscreen, (value) => {
  try { localStorage.setItem('qingjian.auto-fullscreen', value ? 'on' : 'off') } catch { /* Keep the session preference. */ }
})

function readFullscreenPreference() {
  try {
    const stored = localStorage.getItem('qingjian.auto-fullscreen')
    return stored === null ? !props.desktop : stored === 'on'
  } catch { return !props.desktop }
}

function enterFullscreen() {
  if (!props.desktop && autoFullscreen.value && document.fullscreenEnabled && !document.fullscreenElement) {
    document.documentElement.requestFullscreen?.().catch(() => {})
  }
}

function saveCurrentBook() {
  if (!hydrated || !hasCurrentBook.value || retiredBooks.has(manuscript.id)) return
  saveSoon()
  shelfBooks.value = [summarizeBook(manuscript), ...shelfBooks.value.filter(({ id }) => id !== manuscript.id)]
}

function retireBooks(ids) {
  if (!ids.length) return
  retireBrowserBooks(ids)
  for (const id of ids) {
    retiredBooks.add(id)
    discard(id)
  }
  shelfBooks.value = shelfBooks.value.filter(({ id }) => !retiredBooks.has(id))
  if (retiredBooks.has(manuscript.id)) {
    hasCurrentBook.value = false
    shelfOpen.value = true
  }
}

async function refreshShelf() {
  const remote = await localRequest('/api/books?include=deleted', { signal: AbortSignal.timeout(4000) }).catch(() => null)
  retireBooks([...new Set([...readRetiredBooks(), ...(remote?.deleted || [])])].filter((id) => !retiredBooks.has(id) && !syncLinks[id]))
  const merged = new Map((Array.isArray(remote) ? remote : (remote?.books || [])).map((item) => [item.id, item]))
  for (const item of [...readShelf(), ...shelfBooks.value]) {
    if (!merged.has(item.id) || merged.get(item.id).updatedAt <= item.updatedAt) merged.set(item.id, item)
  }
  shelfBooks.value = [...merged.values()].filter(({ id }) => !retiredBooks.has(id)).sort((left, right) => right.updatedAt - left.updatedAt)
}

function changeAddress(path) {
  if (window.location.hash !== path) window.history.pushState(null, '', path)
}

function showShelf(updateAddress = true) {
  if (props.android) return androidGo('Shelf')
  roleplayOpen.value = false
  shelfOpen.value = true
  researchOpen.value = false
  readingPosition = undefined
  mobileNavigation.value = false
  mobileAssistant.value = false
  if (updateAddress) changeAddress('#shelf')
  refreshShelf()
}

function showWorkspace(updateAddress = true) {
  roleplayOpen.value = false
  const position = researchOpen.value ? readingPosition : undefined
  shelfOpen.value = false
  researchOpen.value = false
  if (updateAddress) changeAddress(`#book/${manuscript.id}`)
  if (position) {
    readingPosition = undefined
    nextTick(() => {
      if (position.chapter === activeChapter.value.id && position.section === section.value && editorScroll.value) {
        editorScroll.value.scrollTop = position.top
      }
    })
  }
}

function leaveResearch() {
  const path = `#book/${manuscript.id}`
  const walkBack = window.history.state?.qingjianManuscript === path
  showWorkspace(false)
  if (walkBack) window.history.back()
  else window.history.replaceState(null, '', path)
  nextTick(() => document.querySelector('.knowledge-entry')?.focus({ preventScroll: true }))
}

function useManuscript(book) {
  checkpoint()
  backfillController?.abort()
  Object.assign(manuscript, book)
  hasCurrentBook.value = true
  section.value = 'chapters'
  sourceMode.value = false
  batchSelection.value = []
  pendingReview.value = null
  reviewOpen.value = false
  researchOpen.value = false
  chapterFilter.value = ''
  collapsedVolumes.clear()
  preferredVolumeId.value = activeVolume.value.id
  manuscript.activeId = activeChapter.value.id
  prompt.value = ''
  revisionJump.value = null
  searchMatch.value = null
  search.query = ''
  search.replacement = ''
  focusMode.value = false
  history.clear()
  rememberBook(manuscript.id)
  if (manuscript.aiMode && !manuscript.aiSetup.configured) manuscript.aiMode = false
  saveCurrentBook()
}

async function openBook(id, updateAddress = true) {
  if (openingBook.value || retiredBooks.has(id)) return
  if (id !== manuscript.id || !hasCurrentBook.value) {
    openingBook.value = true
    try {
      const book = await recoverSnapshot(id) || readBrowserBook(id) || await localRequest(`/api/book?id=${encodeURIComponent(id)}`, { signal: AbortSignal.timeout(5000) })
      saveCurrentBook()
      useManuscript(loadManuscript(book))
    } catch {
      ElMessage.error('无法打开这部作品。')
      return
    } finally {
      openingBook.value = false
    }
  }
  showWorkspace(updateAddress)
}

function openFromShelf(id) {
  if (!retiredBooks.has(id)) enterFullscreen()
  return openBook(id)
}

async function routeLocation() {
  if (window.location.hash === '#shelf') return showShelf(false)
  const path = window.location.hash
  const match = path.match(/^#book\/([a-z0-9-]+)(\/library|\/roleplay)?$/i)
  if (match && !retiredBooks.has(match[1])) {
    await openBook(match[1], false)
    if (manuscript.id === match[1] && window.location.hash === path) {
      if (match[2] === '/library') showResearch('foreshadows', null, false)
      if (match[2] === '/roleplay') roleplayOpen.value = true
    }
    return
  }
  if (!hasCurrentBook.value) return showShelf(false)
  showWorkspace(false)
}

function openRoleplay() {
  if (props.android) return androidGo('Roleplay')
  const from = `#book/${manuscript.id}`
  roleplayOpen.value = true
  if (window.location.hash !== from + '/roleplay') {
    window.history.pushState(window.location.hash === from ? { qingjianManuscript: from } : null, '', from + '/roleplay')
  }
}

function leaveRoleplay() {
  const path = `#book/${manuscript.id}`
  roleplayOpen.value = false
  if (window.history.state?.qingjianManuscript === path) window.history.back()
  else window.history.replaceState(null, '', path)
}

function showBookCreator() {
  editingBook.value = null
  bookEditorOpen.value = true
}

async function importBackup(file) {
  if (importingBook.value) return
  importingBook.value = true
  try {
    if (file.size > 32 * 1024 * 1024) throw new Error('备份文件超过 32 MB，无法导入。')
    const book = importManuscript(await file.text())
    await refreshShelf()
    importPreview.value = book
  } catch (error) {
    ElMessage.error(error instanceof SyntaxError ? '无法读取 JSON，请选择青简导出的全书备份。' : error.message)
  } finally {
    importingBook.value = false
  }
}

async function confirmImport(target) {
  if (importingBook.value || !importPreview.value) return
  const book = importPreview.value
  importingBook.value = true
  try {
    await flush()
    if (target && ['failed', 'local'].includes(stateFor(target))) throw new Error('原书有尚未写入磁盘的修改，请先保存原书。')
    const { id } = await localRequest('/api/book/import', {
      method: 'POST', body: { book, target }, signal: AbortSignal.timeout(12000),
    })
    useManuscript({ ...book, id })
    checkpoint()
    importPreview.value = null
    showWorkspace()
    ElMessage.success(`已导入《${book.title}》`)
  } catch (error) {
    ElMessage.error(error.message)
    await refreshShelf()
  } finally {
    importingBook.value = false
  }
}

async function saveBookDetails(changes) {
  const target = editingBook.value
  if (!target) {
    enterFullscreen()
    saveCurrentBook()
    useManuscript({ ...createManuscript(changes.title), cover: changes.cover })
    bookEditorOpen.value = false
    showWorkspace()
    return
  }
  savingBookDetails.value = true
  try {
    const current = hasCurrentBook.value && target.id === manuscript.id
    const book = current ? manuscript : await loadBookCopy(target.id)
    Object.assign(book, changes)
    saveSoon(book)
    shelfBooks.value = [summarizeBook(book), ...shelfBooks.value.filter(({ id }) => id !== target.id)]
    bookEditorOpen.value = false
    await flush()
    const state = stateFor(target.id)
    if (state === 'saved' || state === 'server') ElMessage.success('书籍已更新')
    else if (state === 'local') ElMessage.warning('书籍信息仅已保存到当前浏览器。')
    else if (state === 'deleted') { retireBooks([target.id]); ElMessage.warning('这本书已删除。') }
    else ElMessage.error('书籍信息保存失败。')
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    savingBookDetails.value = false
  }
}

async function manageBook(action, book) {
  if (action.startsWith('export-')) {
    try {
      await flush()
      const source = book.id === manuscript.id ? manuscript : await loadBookCopy(book.id)
      const { exportBook } = await import('./book-export.js')
      exportBook(source, action.slice(7))
    } catch (error) { ElMessage.error(error.message) }
    return
  }
  if (action === 'edit') {
    editingBook.value = book
    bookEditorOpen.value = true
    return
  }
  if (action !== 'delete') return
  try {
    await ElMessageBox.confirm(`删除「${book.title}」？该书的章节、封面和写作资料会一并删除。`, '删除书籍', {
      type: 'warning', confirmButtonText: '删除书籍', cancelButtonText: '取消',
    })
  } catch { return }
  try {
    if (!await deleteSynced(book.id) || props.desktop) {
      await localRequest(`/api/book?id=${encodeURIComponent(book.id)}`, { method: 'DELETE', signal: AbortSignal.timeout(10000) })
    }
    retireBooks([book.id])
    ElMessage.success('书籍已删除')
  } catch (error) {
    ElMessage.error(error.message)
  }
}

function openChapter(index, closeNavigation = true) {
  const entry = chapterEntries.value[index]
  if (!entry) return
  if (props.android) void persistAndroidPosition().catch(() => {})
  manuscript.activeId = entry.chapter.id
  batchSelection.value = []
  pendingReview.value = null
  reviewOpen.value = false
  preferredVolumeId.value = entry.volume.id
  collapsedVolumes.delete(entry.volume.id)
  section.value = 'chapters'
  if (closeNavigation) mobileNavigation.value = false
  searchMatch.value = null
  revisionJump.value = null
  if (manuscript.aiMode) prompt.value = ''
  previousInputAt = 0
  editorScroll.value?.scrollTo(0, 0)
  if (props.android) void restoreAndroidChapter(entry.chapter.id)
}

function showNewChapter(volumeId = preferredVolumeId.value) {
  newTitle.value = ''
  nextFromChapterId.value = ''
  destinationVolumeId.value = manuscript.volumes.some((volume) => volume.id === volumeId) ? volumeId : activeVolume.value.id
  dialog.value = 'new'
  nextDirection.value = newChapterPredecessor.value?.handoff?.directions?.[0]?.detail || ''
}

function showNextChapter(chapterId = activeChapter.value.id, direction) {
  const entry = chapterEntries.value.find(item => item.chapter.id === chapterId) || activeEntry.value
  if (direction && !manuscript.aiMode) {
    if (!manuscript.aiSetup.configured) { openProfile(); return }
    manuscript.aiMode = true
  }
  if (researchOpen.value) showWorkspace()
  showNewChapter(entry.volume.id)
  nextFromChapterId.value = entry.chapter.id
  nextDirection.value = direction?.detail || entry.chapter.handoff?.directions?.[0]?.detail || ''
}

async function revealSidebarItem(id) {
  await nextTick()
  document.getElementById(id)?.scrollIntoView({ block: 'nearest' })
}

async function createChapter() {
  const from = chapterEntries.value.find(({ chapter }) => chapter.id === nextFromChapterId.value)
  const volume = manuscript.volumes.find(({ id }) => id === destinationVolumeId.value)
  const title = newTitle.value.trim() || (from || manuscript.aiMode ? '未命名章节' : '')
  if (!title || !volume) return
  const chapter = { id: crypto.randomUUID(), title, text: '', complete: false,
    ...((from || manuscript.aiMode) ? { plan: { stage: 'summary', summary: '', draft: '',
      direction: nextDirection.value.trim(), handoffFrom: newChapterPredecessor.value?.id || '' } } : {}) }
  volume.chapters.splice(from?.volume.id === volume.id ? from.position + 1 : volume.chapters.length, 0, chapter)
  chapterFilter.value = ''
  openChapter(chapterEntries.value.findIndex((entry) => entry.chapter.id === chapter.id))
  if (from) mobileAssistant.value = false
  dialog.value = ''
  await revealSidebarItem(`row-${chapter.id}`)
}

function showVolumeEditor(volume) {
  volumeToEdit.value = volume?.id || ''
  volumeTitle.value = volume?.title || ''
  volumeDirection.value = volume?.direction || ''
  dialog.value = 'volume'
  if (!volume && manuscript.aiMode && providerReady(connection.value, preferences.provider)) requestVolumeSuggestion()
}

async function saveVolume() {
  const title = volumeTitle.value.trim()
  if (volumeToEdit.value) {
    const volume = manuscript.volumes.find(({ id }) => id === volumeToEdit.value)
    if (!volume || !title) return
    volume.title = title
  } else {
    const volume = { id: crypto.randomUUID(), title: title || nextVolumeTitle.value, direction: volumeDirection.value.trim(), chapters: [] }
    manuscript.volumes.push(volume)
    preferredVolumeId.value = volume.id
    chapterFilter.value = ''
    await revealSidebarItem(`volume-${volume.id}`)
  }
  dialog.value = ''
}

function toggleVolume(volume) {
  if (!collapsedVolumes.delete(volume.id)) {
    collapsedVolumes.add(volume.id)
  } else {
    preferredVolumeId.value = volume.id
  }
}

function handleVolumeAction(action, volume) {
  if (action === 'new') showNewChapter(volume.id)
  if (action === 'rename') showVolumeEditor(volume)
  if (action === 'delete' && !volume.chapters.length && manuscript.volumes.length > 1) {
    manuscript.volumes = manuscript.volumes.filter(({ id }) => id !== volume.id)
    collapsedVolumes.delete(volume.id)
    if (preferredVolumeId.value === volume.id) preferredVolumeId.value = activeVolume.value.id
    ElMessage.success('空卷已删除')
  }
}

function showTitleEditor(chapter = activeChapter.value) {
  chapterToEdit.value = chapter.id
  editTitle.value = chapter.title
  dialog.value = 'rename'
}

function renameChapter() {
  const entry = chapterEntries.value.find(({ chapter }) => chapter.id === chapterToEdit.value)
  const title = editTitle.value.trim()
  if (!entry || !title) return
  entry.chapter.title = title
  dialog.value = ''
}

function toggleComplete() {
  const chapter = activeChapter.value
  if (chapter.complete) {
    recordRevision(chapter.id, versionSnapshot(chapter))
    chapter.complete = false
    ElMessage.success('章节已恢复为草稿')
    return
  }
  if (!chapter.text.trim()) { ElMessage.warning('请先写下本章正文。'); return }
  handoffBase.value = chapter.text
  handoffChapter.value = chapter.id
  handoffDraft.value = chapter.handoff
    ? JSON.parse(JSON.stringify(chapter.handoff))
    : { recap: chapter.plan?.summary || '', bridge: '', lessons: [], directions: [], threads: [], relations: [] }
  dialog.value = 'handoff'
  if (providerReady(connection.value, preferences.provider)) generateHandoff()
}

function handoffMaterial(chapter) {
  const volumeIndex = chapterEntries.value.findIndex(entry => entry.chapter.id === chapter.id)
  const previousChapters = new Set(chapterEntries.value.slice(0, Math.max(0, volumeIndex)).map(entry => entry.chapter.id))
  return currentAiMaterial('chapter-close', '', {
    目标段落: excerpt(chapter.text, Math.min(15000, Math.floor(manuscript.context.budget * .67))),
    定稿章节: chapter.title,
    原摘要: excerpt(chapter.plan?.summary || '', 600),
    作者反馈: chapter.paragraphs?.filter(part => part.vote).slice(-10).map(part => ({ 标签: part.vote, 摘录: excerpt(part.text, 110) })),
    未回收线索: manuscript.research.foreshadows.filter(item => item.status !== 'recovered').slice(0, 15).map(item => item.title),
    以往事件: manuscript.research.events.filter(item => previousChapters.has(item.anchor?.chapterId)).slice(-10)
      .map(item => ({ id: item.id, title: item.title, note: excerpt(item.description || '', 120) })),
  }, chapter)
}

function generateHandoff() {
  const chapter = activeChapter.value
  runStudioAi('chapter-close', () => handoffMaterial(chapter), ({ data }) => {
    if (dialog.value === 'handoff' && activeChapter.value.id === chapter.id && chapter.text === handoffBase.value) handoffDraft.value = data
  })
}

function storyLinkLabel(link) {
  const source = manuscript.research.events.find(item => item.id === link.from)?.title || link.from
  return `${source} → ${link.to} · ${link.label}`
}

function storeHandoff(chapter, draft) {
  const previous = versionSnapshot(chapter)
  chapter.handoff = { ...draft, recap: draft.recap.trim(), bridge: draft.bridge.trim(), at: draft.at ?? Date.now(), archiveVersion: 1 }
  chapter.complete = true
  previous.archiveDelta = syncStoryArchive(manuscript, chapter, chapter.handoff)
  recordRevision(chapter.id, previous)
}

function finishHandoff() {
  const chapter = activeChapter.value
  if (chapter.id !== handoffChapter.value || chapter.text !== handoffBase.value) { ElMessage.warning('正文已变化，请重新生成交接。'); return }
  const draft = handoffDraft.value
  if (!draft?.bridge.trim() || !draft.recap.trim()) return
  storeHandoff(chapter, draft)
  dialog.value = ''
  ElMessage.success('已定稿，交接和有原文证据的线索已归档')
}

function confirmStoryLink(chapterId, index) {
  const chapter = chapterEntries.value.find(item => item.chapter.id === chapterId)?.chapter
  const handoff = chapter?.handoff
  const target = handoff?.links?.[index]
  if (!chapter?.complete || !target || target.confirmed || !chapter.text.includes(target.quote)) {
    ElMessage.warning('原文证据已变化，请重新整理交接。')
    return
  }
  storeHandoff(chapter, { ...handoff, links: handoff.links.map((link, position) => position === index ? { ...link, confirmed: true } : link) })
  ElMessage.success('已确认因果关系')
}

async function backfillHandoffs() {
  if (studioBusy.value || backfill.running) return
  const chapters = chapterEntries.value.map(entry => entry.chapter).filter(chapter => chapter.complete && !chapter.handoff?.archiveVersion && chapter.text.trim())
  if (!chapters.length) return
  const needsAi = chapters.filter(chapter => !chapter.handoff).length
  if (needsAi && !providerReady(connection.value, preferences.provider)) { openConnection(); return }
  const id = manuscript.id
  try {
    await ElMessageBox.confirm(`将为 ${chapters.length} 个旧章补齐归档，其中 ${needsAi} 章需要调用 AI 生成交接。`, '补齐旧章交接', {
      confirmButtonText: '开始补齐', cancelButtonText: '取消',
    })
  } catch { return }
  if (manuscript.id !== id || backfill.running) return
  const controller = new AbortController()
  backfillController = controller
  Object.assign(backfill, { running: true, done: 0, total: chapters.length, failures: 0 })
  try {
    for (const chapter of chapters) {
      if (controller.signal.aborted || manuscript.id !== id) break
      const original = chapter.text
      try {
        if (chapter.handoff) {
          storeHandoff(chapter, chapter.handoff)
          backfill.done++
          continue
        }
        const { data } = await localRequest('/api/ai/rewrite', {
          method: 'POST', body: { provider: preferences.provider, intent: 'chapter-close', material: handoffMaterial(chapter) },
          signal: AbortSignal.any([controller.signal, AbortSignal.timeout(205000)]),
        })
        if (controller.signal.aborted || manuscript.id !== id) break
        if (chapter.text !== original || !chapter.complete || chapter.handoff) { backfill.failures++; continue }
        storeHandoff(chapter, data)
        backfill.done++
      } catch {
        if (!controller.signal.aborted) backfill.failures++
      }
    }
    if (!controller.signal.aborted) ElMessage.info(`已补齐 ${backfill.done} 章，${backfill.failures} 章待重试。`)
  } finally {
    if (backfillController === controller) { backfill.running = false; backfillController = undefined }
  }
}

async function removeChapter({ id, title }) {
  try {
    await ElMessageBox.confirm(`删除「${title}」及其正文？`, '删除章节', {
      type: 'warning', confirmButtonText: '删除章节', cancelButtonText: '取消',
    })
  } catch {
    return
  }
  const entry = chapterEntries.value.find(({ chapter }) => chapter.id === id)
  if (!entry || chapterEntries.value.length <= 1) return
  const wasActive = activeChapter.value.id === id
  entry.volume.chapters.splice(entry.position, 1)
  history.delete(id)
  if (wasActive) openChapter(Math.min(entry.index, chapterEntries.value.length - 1), false)
  ElMessage.success('章节已删除')
}

function handleChapterAction(action, entry) {
  const { chapter, volume, position } = entry
  if (action === 'rename') showTitleEditor(chapter)
  if (action === 'delete') removeChapter(chapter)
  if (action === 'move') {
    chapterToEdit.value = chapter.id
    destinationVolumeId.value = moveDestinations.value[0]?.id || ''
    dialog.value = 'move'
  }
  if (action === 'up' || action === 'down') {
    const destination = position + (action === 'up' ? -1 : 1)
    if (destination < 0 || destination >= volume.chapters.length) return
    volume.chapters.splice(position, 1)
    volume.chapters.splice(destination, 0, chapter)
  }
}

async function moveChapter() {
  const entry = chapterEntries.value.find(({ chapter }) => chapter.id === chapterToEdit.value)
  const target = moveDestinations.value.find(({ id }) => id === destinationVolumeId.value)
  if (!entry || !target) return
  entry.volume.chapters.splice(entry.position, 1)
  target.chapters.push(entry.chapter)
  if (activeChapter.value.id === entry.chapter.id) preferredVolumeId.value = target.id
  collapsedVolumes.delete(target.id)
  dialog.value = ''
  await revealSidebarItem(`row-${entry.chapter.id}`)
  ElMessage.success(`已移动到「${target.title}」`)
}

function showSettings() {
  Object.assign(settings, { goal: chapterGoal.value })
  dialog.value = 'settings'
}

function applySettings() {
  if (!settings.goal) return
  activeChapter.value.goal = settings.goal
  dialog.value = ''
}

async function loadBookCopy(id) {
  return await recoverSnapshot(id) || readBrowserBook(id) || localRequest('/api/book?id=' + encodeURIComponent(id))
}

async function exportManuscript(scope) {
  if (props.android) {
    try {
      if (await flush() && backupState.value === 'saved') await androidCall('export', {
        format: scope, chapter: activeChapter.value.id, title: manuscript.title,
      })
    } catch (error) { ElMessage.error(error.message) }
    return
  }
  if (scope === 'chapter') {
    downloadText(`${activeLabel.value} ${activeChapter.value.title}`, `${activeLabel.value} ${activeChapter.value.title}\n\n${activeChapter.value.text}`)
  } else {
    try { (await import('./book-export.js')).exportBook(manuscript, scope.slice(5)) }
    catch (error) { ElMessage.error(error.message) }
  }
}

function showResearch(tab, anchor, updateAddress = true) {
  if (props.android) void persistAndroidPosition().catch(() => {})
  const path = `#book/${manuscript.id}`
  if (!researchOpen.value && editorScroll.value) readingPosition = {
    chapter: activeChapter.value.id, section: section.value, top: editorScroll.value.scrollTop,
  }
  focusMode.value = false
  researchTab.value = tab
  researchAnchor.value = anchor
  researchOpen.value = true
  if (updateAddress && window.location.hash !== path + '/library') {
    const state = window.location.hash === path ? { qingjianManuscript: path } : null
    window.history.pushState(state, '', path + '/library')
  }
}

function showCreative(tab) {
  showResearch(tab, null)
}

async function creativeJump(anchor) {
  if (researchOpen.value) showWorkspace()
  sourceMode.value = false
  if (anchor.paragraphId) jumpToRevision(anchor)
  else openChapter(chapterEntries.value.findIndex(({ chapter }) => chapter.id === anchor.chapterId))
  await nextTick()
  revealSearchMatch()
}

function jumpToRevision(anchor) {
  const index = chapterEntries.value.findIndex(({ chapter }) => chapter.id === anchor.chapterId)
  if (index < 0) return
  openChapter(index)
  mobileAssistant.value = false
  if (!manuscript.aiMode) {
    const chapter = activeChapter.value
    const paragraph = chapter.paragraphs?.findIndex(({ id }) => id === anchor.paragraphId)
    const span = paragraphSpans(chapter.text)[paragraph]
    if (span) searchMatch.value = { start: span.start, end: span.end }
    return
  }
  manuscript.aiMode = true
  revisionJump.value = { ...anchor }
}

async function saveNow() {
  saveSoon()
  await flush()
  if (backupState.value === 'saved') ElMessage.success(props.android ? '已保存到本机' : '浏览器与服务端备份已更新')
}

async function signOut() {
  await flush()
  emit('sign-out')
}

function toggleAiMode() {
  if (!manuscript.aiMode && !manuscript.aiSetup.configured) {
    openProfile()
    return
  }
  manuscript.aiMode = !manuscript.aiMode
  if (!manuscript.aiMode) mobileAssistant.value = false
  else section.value = 'chapters'
  revisionJump.value = null
  editorScroll.value?.scrollTo(0, 0)
}

function openSettings() {
  if (props.android) return androidGo('Settings')
  settingsOpen.value = true
}

function openConnection() {
  if (props.android) return androidGo('Providers')
  connectionOpen.value = true
}

async function openBackup() {
  if (props.android && !await flush()) {
    ElMessage.error('书稿还未保存，请重试。')
    return
  }
  backupTab.value = 'versions'
  backupOpen.value = true
}

let androidLeaving = false
let positionTimer
function scheduleAndroidPosition() {
  if (!props.android || section.value !== 'chapters' || researchOpen.value) return
  clearTimeout(positionTimer)
  positionTimer = setTimeout(() => persistAndroidPosition().catch(() => {}), 220)
}

async function persistAndroidPosition() {
  clearTimeout(positionTimer)
  if (section.value !== 'chapters' || researchOpen.value || !editorScroll.value) return
  const [start, end] = editorRange() || []
  await androidCall('savePosition', {
    chapter: activeChapter.value.id,
    position: { start, end, top: Math.round(editorScroll.value.scrollTop) },
  })
}

async function restoreAndroidChapter(chapter, saved) {
  try {
    const position = saved ?? await androidCall('position', { chapter })
    await nextTick()
    if (activeChapter.value.id !== chapter || section.value !== 'chapters') return
    const field = editor.value
    if (field) field.setSelectionRange(position.start ?? 0, position.end ?? position.start ?? 0)
    editorScroll.value?.scrollTo(0, position.top ?? 0)
  } catch { /* The chapter remains editable if a reading position cannot be restored. */ }
}

async function androidGo(page) {
  if (androidLeaving) return
  androidLeaving = true
  try {
    if (!await flush()) { ElMessage.error('书稿还未保存，请重试。'); return }
    if (backupState.value === 'conflict') return
    await persistAndroidPosition()
    await androidCall('navigate', { page })
  } catch (error) { ElMessage.error(error.message) }
  finally { androidLeaving = false }
}

function androidBack() {
  if (dialog.value) dialog.value = ''
  else if (backupOpen.value) backupOpen.value = false
  else if (reviewOpen.value) reviewOpen.value = false
  else if (profileOpen.value) profileOpen.value = false
  else if (skillsOpen.value) skillsOpen.value = false
  else if (focusMode.value) focusMode.value = false
  else if (mobileNavigation.value || mobileAssistant.value) mobileNavigation.value = mobileAssistant.value = false
  else if (researchOpen.value) leaveResearch()
  else if (section.value !== 'chapters') showSection('chapters')
  else void androidGo('Shelf')
}

function openProfile(only = false) {
  profileOnly.value = only
  profileOpen.value = true
}

function applyProfile() {
  manuscript.aiMode = true
  section.value = 'chapters'
}

function recordRevision(chapterId, previousText) {
  if (!history.has(chapterId)) history.set(chapterId, { undo: [], redo: [] })
  const entry = history.get(chapterId)
  entry.undo.push(previousText)
  if (entry.undo.length > 100) entry.undo.shift()
  entry.redo.length = 0
  previousInputAt = 0
}

function restoreBookVersion(book) {
  if (researchOpen.value) showWorkspace()
  useManuscript(loadManuscript(book))
  saveSoon()
  flush()
  backupOpen.value = false
  ElMessage.success('书稿已回退到所选备份')
}

function applyCreativeDraft({ chapterId, base, text, planned, reject }) {
  const chapter = activeChapter.value
  if (chapter.id !== chapterId || (!reject && chapter.text !== base) || (planned && chapter.plan?.stage !== 'draft')) {
    ElMessage.warning('正文或候选已经变化，请重新打开比稿。')
    return
  }
  const next = reject ? '' : planned ? generatedManuscript(text) : text
  if (!reject && !next.trim()) return
  recordRevision(chapter.id, revisionSnapshot(chapter))
  manuscript.aiAuto = false
  aiEditor.value?.cancelGeneration()
  if (!reject) {
    chapter.text = next
    syncParagraphs(chapter)
  }
  if (planned) {
    chapter.plan.draft = next
    chapter.plan.stage = reject ? 'summary' : 'approved'
  }
  if (researchOpen.value) showWorkspace()
  ElMessage.success(reject ? '已拒绝候选' : '已接受候选，可撤销')
}

const versionFields = ['title', 'complete', 'goal', 'handoff']
const versionSnapshot = (chapter) => ({ ...revisionSnapshot(chapter), ...Object.fromEntries(versionFields.map(key => [key, chapter[key]])) })

function restoreChapterVersion({ original, restored }) {
  const chapter = activeChapter.value
  if (chapter.id !== restored.id || JSON.stringify(chapter) !== JSON.stringify(original)) {
    ElMessage.warning('章节已变化，请重新操作。')
    return
  }
  recordRevision(chapter.id, versionSnapshot(chapter))
  manuscript.aiAuto = false
  aiEditor.value?.cancelGeneration()
  for (const field of ['text', 'paragraphs', 'plan', 'batchReview', ...versionFields]) {
    if (Object.hasOwn(restored, field)) chapter[field] = restored[field]
    else delete chapter[field]
  }
  ElMessage.success('已恢复章节版本，可撤销')
}

function updateText(value, inputType = '') {
  const chapter = activeChapter.value
  if (value === chapter.text) return
  searchMatch.value = null
  if (!history.has(chapter.id)) history.set(chapter.id, { undo: [], redo: [] })
  const entry = history.get(chapter.id)
  const now = Date.now()
  const consecutiveTyping = inputType === 'insertText' && previousInputType === inputType && now - previousInputAt < 550
  if (!consecutiveTyping) {
    entry.undo.push(chapter.text)
    if (entry.undo.length > 100) entry.undo.shift()
  }
  entry.redo.length = 0
  previousInputAt = now
  previousInputType = inputType
  chapter.text = value
}

async function restoreText(direction) {
  const entry = chapterHistory.value
  if (!entry?.[direction].length) return
  if (manuscript.aiMode) {
    manuscript.aiAuto = false
    aiEditor.value?.cancelGeneration()
  }
  const destination = direction === 'undo' ? 'redo' : 'undo'
  const position = editorRange()?.[0]
  const previous = entry[direction].pop()
  const chapter = activeChapter.value
  if (typeof previous === 'string') {
    entry[destination].push(chapter.text)
    chapter.text = previous
  } else if (previous.paragraphRevision) {
    const reciprocal = restoreParagraphRevision(chapter, previous)
    if (reciprocal) entry[destination].push(reciprocal)
  } else {
    const full = Object.hasOwn(previous, 'title')
    const reciprocal = full ? versionSnapshot(chapter) : revisionSnapshot(chapter)
    if (previous.archiveDelta) reciprocal.archiveDelta = undoStoryArchive(manuscript.research, previous.archiveDelta)
    entry[destination].push(reciprocal)
    for (const field of ['text', 'paragraphs', 'plan', 'batchReview', ...(full ? versionFields : [])]) {
      if (Object.hasOwn(previous, field)) chapter[field] = previous[field]
      else delete chapter[field]
    }
  }
  searchMatch.value = null
  previousInputAt = 0
  await nextTick()
  if (position !== undefined) focusEditorRange(position)
}

function editDocument(value, inputType) {
  if (section.value === 'chapters') updateText(value, inputType)
  else manuscript[section.value] = value
}

function onEditorInput(event) {
  if (!event.isComposing) editPlainDocument(event, event.inputType)
  updateSelection()
}

function editorCompositionEnd(event) {
  editPlainDocument(event, 'composition')
  updateSelection()
}

function editPlainDocument(event, inputType) {
  const { value, selectionStart, selectionEnd } = event.target
  editDocument(value, inputType)
  const afterBlock = inputType === 'insertFromPaste' || event.data?.includes('\n') ? 'paste' : true
  if (activeMarkup.value.length) nextTick(() => inlineEditor.value?.focusRange(selectionStart, selectionEnd, afterBlock))
}

function editorRange() {
  const field = editor.value
  return inlineEditor.value?.range() || (field ? [field.selectionStart, field.selectionEnd] : null)
}

function focusEditorRange(start, end = start, scroll = false) {
  if (inlineEditor.value) {
    inlineEditor.value.focusRange(start, end).then(() => {
      if (scroll && inlineEditor.value?.owns(document.activeElement)) document.activeElement.scrollIntoView({ block: 'nearest' })
    })
  } else if (editor.value) {
    editor.value.focus({ preventScroll: scroll })
    editor.value.setSelectionRange(start, end)
    if (scroll) matchMarker.value?.scrollIntoView({ block: 'nearest' })
  }
}

function updateSelection() {
  const field = editor.value
  selection.value = field && document.activeElement === field ? [field.selectionStart, field.selectionEnd] : null
  scheduleAndroidPosition()
}

function showDivider(block) {
  return block.divider && (!selection.value || selection.value[1] < block.start || selection.value[0] > block.end)
}

async function insertContent(command) {
  if (command === 'image' && preferences.images) { imageOpen.value = true; return }
  if (manuscript.aiMode) {
    if (command === 'divider') aiEditor.value?.insertDivider()
    return
  }
  const range = editorRange()
  if (!range) return
  const [start, end] = range
  const text = activeChapter.value.text
  let offset
  if (command === 'divider') {
    const result = insertSceneDivider(text, end)
    updateText(result.text)
    offset = result.offset
  } else {
    updateText(text.slice(0, start) + '　　' + text.slice(end))
    offset = start + 2
  }
  await nextTick()
  focusEditorRange(offset)
  updateSelection()
}

function findNext() {
  if (!searchCount.value) return
  const text = activeChapter.value.text
  const from = editorRange()?.[1] || 0
  let index = text.indexOf(search.query, from)
  if (index === -1) index = text.indexOf(search.query)
  searchMatch.value = { start: index, end: index + search.query.length }
  dialog.value = ''
}

function revealSearchMatch() {
  const match = searchMatch.value
  if (!match) return
  requestAnimationFrame(() => {
    focusEditorRange(match.start, match.end, true)
    searchMatch.value = null
  })
}

function replaceAll() {
  if (!searchCount.value) return
  const count = searchCount.value
  updateText(activeChapter.value.text.split(search.query).join(search.replacement))
  dialog.value = ''
  ElMessage.success(`已替换 ${count} 处`)
}

function toggleNavigation() {
  if (compactNavigation.value) {
    mobileNavigation.value = !mobileNavigation.value
    mobileAssistant.value = false
  } else {
    manuscript.navigationOpen = !manuscript.navigationOpen
  }
}

function toggleAssistant() {
  if (compactAssistant.value) {
    mobileAssistant.value = !mobileAssistant.value
    mobileNavigation.value = false
  } else {
    manuscript.assistantOpen = !manuscript.assistantOpen
  }
}

function updateViewport() {
  viewportWidth.value = window.innerWidth
  if (!compactNavigation.value) mobileNavigation.value = false
  if (!compactAssistant.value) mobileAssistant.value = false
}

function showSection(id) {
  if (props.android && id !== section.value) void persistAndroidPosition().catch(() => {})
  section.value = id
  sourceMode.value = false
  editorScroll.value?.scrollTo(0, 0)
  if (id !== 'chapters') mobileNavigation.value = false
  else if (props.android) void restoreAndroidChapter(activeChapter.value.id)
}

function openComparison(review) {
  if (!review.items.length) return
  pendingReview.value = review
  reviewOpen.value = true
}

function applyComparison(items) {
  const review = activeReview.value
  const chapter = activeChapter.value
  const previous = review?.kind === 'batch' ? revisionSnapshot(chapter) : null
  const success = previous ? acceptBatchReview(chapter, items) : review?.apply(items)
  if (!success) {
    ElMessage.warning('原文已变化，或候选缺少内容、改动了受保护的 Markdown。请检查后重试。')
    return
  }
  if (previous) {
    recordRevision(chapter.id, previous)
    batchSelection.value = []
  }
  pendingReview.value = null
  if (!previous || !chapter.batchReview.length) reviewOpen.value = false
  ElMessage.success(previous && chapter.batchReview.length ? `已采用 ${items.length} 段，剩余 ${chapter.batchReview.length} 段待审批` : '已采用生成稿')
}

function rejectComparison(items) {
  if (activeReview.value?.kind !== 'batch') return
  const chapter = activeChapter.value
  const selected = new Set(items.map(item => item.id))
  recordRevision(chapter.id, revisionSnapshot(chapter))
  for (const paragraph of chapter.paragraphs || []) {
    const candidate = chapter.batchReview.find(item => item.id === paragraph.id && selected.has(item.id))
    if (candidate) (paragraph.drafts ||= []).push({ id: crypto.randomUUID(), text: candidate.after, base: candidate.before, state: 'rejected' })
  }
  chapter.batchReview = chapter.batchReview.filter(item => !selected.has(item.id))
  if (!chapter.batchReview.length) reviewOpen.value = false
}

function currentAiMaterial(intent, instruction, fields, chapter = activeChapter.value) {
  return ensureContextBudget(manuscript, withContext(manuscript, {
    ...fields, 任务: instruction || undefined,
    作品设定: bookMaterial(manuscript, chapterEntries.value),
    ...creativeMaterial(manuscript, chapterEntries.value, chapter, intent, instruction + '\n' + excerpt(chapter.text, 1000)),
    技能: activeSkills(manuscript, intent),
    全书大纲: excerpt(manuscript.outline, 2000),
    当前设定: excerpt(manuscript.notes, 1400),
  }))
}

async function runStudioAi(intent, getMaterial, receive) {
  if (studioBusy.value || backfill.running) return
  if (!providerReady(connection.value, preferences.provider)) { openConnection(); return }
  const controller = new AbortController()
  aiController = controller
  studioBusy.value = intent
  const id = manuscript.id
  try {
    const result = await localRequest('/api/ai/rewrite', {
      method: 'POST', body: { intent, provider: preferences.provider, material: getMaterial(), ...skillRequest(manuscript, intent) },
      signal: AbortSignal.any([controller.signal, AbortSignal.timeout(205000)]),
    })
    if (id === manuscript.id && !controller.signal.aborted) receive(result)
  } catch (error) {
    if (!controller.signal.aborted) ElMessage.error(error.message)
  } finally {
    if (aiController === controller) { aiController = undefined; studioBusy.value = '' }
  }
}

function requestVolumeSuggestion() {
  runStudioAi('volume', () => currentAiMaterial('volume', volumeDirection.value, {
    已有卷: manuscript.volumes.map(volume => ({ 标题: volume.title, 方向: excerpt(volume.direction || '', 160), 末章: volume.chapters.at(-1)?.title })),
    最近章节: chapterEntries.value.slice(-3).map(({ chapter }) => ({ 标题: chapter.title, 摘要: excerpt(chapter.plan?.summary || chapter.text, 320) })),
    未回收伏笔: manuscript.research.foreshadows.filter(item => item.status !== 'recovered').slice(0, 12).map(item => item.title),
  }), ({ data }) => {
    if (dialog.value !== 'volume' || volumeToEdit.value) return
    if (!volumeTitle.value.trim()) volumeTitle.value = data.title
    volumeDirection.value = data.direction
  })
}

function generateDocument() {
  const key = section.value
  if (!['outline', 'notes'].includes(key)) return
  const before = manuscript[key]
  const intent = key === 'notes' ? 'settings' : 'outline'
  runStudioAi(intent, () => currentAiMaterial(intent, '', {
    目标段落: excerpt(before, Math.min(7500, Math.floor(manuscript.context.budget * .55))),
    已有章节: chapterEntries.value.slice(0, 35).map(({ chapter }) => ({ 标题: chapter.title, 摘要: excerpt(chapter.plan?.summary || '', 140) })),
  }), ({ text }) => {
    if (section.value !== key || manuscript[key] !== before) return
    openComparison({ title: key === 'outline' ? '全书大纲对比' : '人物与世界设定对比',
      items: [{ id: key, label: key === 'outline' ? '大纲' : '设定', before, after: text, checked: true }],
      apply: ([item]) => {
        if (manuscript[key] !== before || !item.after.trim()) return false
        manuscript[key] = item.after
        return true
      } })
  })
}

function paragraphsAt(start, end) {
  const chapter = activeChapter.value
  syncParagraphs(chapter)
  return paragraphSpans(chapter.text).flatMap((span, index) =>
    (start === end ? span.start <= start && span.end >= end : span.end > start && span.start < end) ? [chapter.paragraphs[index].id] : [])
}

function clickParagraph(event) {
  if (section.value !== 'chapters' || !(event.ctrlKey || event.metaKey)) return
  const article = event.target.closest('.revision-paragraph[id]')
  const range = editorRange()
  const [id] = article ? [article.id.slice('revision-'.length)] : event.target.matches('textarea') && range ? paragraphsAt(...range) : []
  if (!id) return
  batchSelection.value = batchSelection.value.includes(id) ? batchSelection.value.filter(item => item !== id) : [...batchSelection.value, id]
}

function startParagraphDrag(event) {
  paragraphDrag = event.pointerType === 'mouse' && event.button === 0 && !event.ctrlKey && !event.metaKey &&
    event.target.closest('.paragraph-hit') ? event.target.closest('.revision-paragraph[id]')?.id : null
}

function endParagraphDrag(event) {
  const from = paragraphDrag
  paragraphDrag = null
  const to = event.target.closest('.revision-paragraph[id]')?.id
  if (!from || !to || from === to) return
  const records = activeChapter.value.paragraphs
  const a = records.findIndex(item => 'revision-' + item.id === from)
  const b = records.findIndex(item => 'revision-' + item.id === to)
  if (a >= 0 && b >= 0) batchSelection.value = records.slice(Math.min(a, b), Math.max(a, b) + 1).map(item => item.id)
}

function openBatch(newMode) {
  if (newMode === 'batch' && !batchSelection.value.length) {
    const range = editorRange()
    if (range) batchSelection.value = paragraphsAt(...range)
  }
  batchPrompt.value = ''
  dialog.value = newMode
}

function regenerate(intent) {
  const chapter = activeChapter.value
  const before = chapter.text
  if (!before.trim()) return
  syncParagraphs(chapter)
  const spans = paragraphSpans(before)
  const items = chapter.paragraphs.flatMap((item, index) => batchSelection.value.includes(item.id)
    ? [{ ...item, span: spans[index], position: index + 1 }] : []).filter(item => item.span)
  if (intent === 'batch' && !items.length) return
  const instruction = batchPrompt.value
  dialog.value = ''
  runStudioAi(intent, () => currentAiMaterial(intent, instruction, intent === 'batch'
    ? { 审阅原文: evidenceMaterial(chapter, items) }
    : { 目标段落: before }), ({ data, text }) => {
    if (activeChapter.value.id !== chapter.id || chapter.text !== before) { ElMessage.warning('书稿已变化，请重新生成。'); return }
    const proposed = intent === 'batch'
      ? items.map(item => ({ id: item.id, label: '第 ' + item.position + ' 段', before: item.text,
        after: data.find(candidate => candidate.paragraphId === item.id)?.text || item.text, checked: true }))
      : [{ id: chapter.id, label: chapter.title, before, after: text, checked: true }]
    const changes = proposed.filter(item => !sameProse(item.before, item.after) && preservesMarkup(item.before, item.after))
    if (!changes.length) { ElMessage.info('本次没有可采用的正文改动。'); return }
    if (changes.length < proposed.filter(item => !sameProse(item.before, item.after)).length) ElMessage.warning('已过滤破坏 Markdown 排版的候选。')
    if (intent === 'batch') {
      recordRevision(chapter.id, revisionSnapshot(chapter))
      const replaced = new Set(changes.map(item => item.id))
      chapter.batchReview = [...(chapter.batchReview || []).filter(item => !replaced.has(item.id)), ...changes]
      pendingReview.value = null
      reviewOpen.value = true
      return
    }
    openComparison({ title: '整章改稿 · 对比', items: changes,
      apply: accepted => {
        if (activeChapter.value.id !== chapter.id || chapter.text !== before || accepted.some(item =>
          !item.after.trim() || sameProse(item.before, item.after) || !preservesMarkup(item.before, item.after))) return false
        const next = generatedManuscript(accepted[0].after)
        recordRevision(chapter.id, revisionSnapshot(chapter))
        chapter.text = next
        syncParagraphs(chapter)
        batchSelection.value = []
        return true
      } })
  })
}

async function insertMarkdown(command) {
  if (manuscript.aiMode && section.value === 'chapters') { aiEditor.value?.insertMarkdown(command); return }
  const [start, end] = editorRange() || [activeMarkdown.value.length, activeMarkdown.value.length]
  const text = activeMarkdown.value
  const snippet = markdownSnippet(command, text.slice(start, end))
  if (!snippet) return
  editDocument(text.slice(0, start) + snippet + text.slice(end))
  await nextTick()
  focusEditorRange(start + snippet.length)
}

function sendPrompt() {
  const instruction = prompt.value.trim()
  if (!instruction) return
  prompt.value = ''
  aiEditor.value?.preparePrompt(instruction)
}

function shortcuts(event) {
  const key = event.key.toLowerCase()
  if (key === 'escape' && !dialog.value && !event.defaultPrevented && !event.target.closest?.('[role="menu"]')) {
    mobileNavigation.value = false
    mobileAssistant.value = false
    focusMode.value = false
  }
  if (!(event.ctrlKey || event.metaKey)) return
  if (key === 's') {
    event.preventDefault()
    saveNow()
  }
  if (key === 'f' && !shelfOpen.value && !researchOpen.value && section.value === 'chapters' && !manuscript.aiMode && !dialog.value) {
    event.preventDefault()
    dialog.value = 'search'
  }
  const aiShortcut = assistantAiMode.value && !researchOpen.value && !event.target.closest?.('input,textarea,select,[contenteditable],[role="dialog"]')
  if (section.value !== 'chapters' || (event.target !== editor.value && !inlineEditor.value?.owns(event.target) && !aiShortcut) || event.isComposing) return
  if (key === 'z' || key === 'y') {
    event.preventDefault()
    restoreText(key === 'y' || event.shiftKey ? 'redo' : 'undo')
  }
}

onMounted(async () => {
  if (props.desktop) studioSync.target().then(target => { syncTarget.value = target }).catch(() => {})
  if (!props.android) loadPreferences().catch(() => {})
  window.addEventListener('keydown', shortcuts)
  window.addEventListener('resize', updateViewport)
  window.addEventListener('hashchange', routeLocation)
  if (props.android) {
    if (manuscript.id !== props.android.book.id) throw new Error('书稿格式无法读取。')
    window.qingjianBack = androidBack
    window.qingjianTheme = dark => { nightMode.value = dark }
    window.qingjianKeyboard = open => document.documentElement.classList.toggle('android-keyboard', open)
    window.history.replaceState(null, '', `#book/${manuscript.id}`)
    hydrated = true
    await restoreAndroidChapter(activeChapter.value.id, props.android.position)
    if (props.android.recoveredConflict) ElMessage.warning('此前未保存的编辑已恢复为冲突副本。')
    return
  }
  await refreshShelf()
  const recovered = hasCurrentBook.value && await recoverSnapshot(manuscript.id)
  if (recovered && recovered.id === manuscript.id) Object.assign(manuscript, loadManuscript(recovered))
  if (!recovered && hasCurrentBook.value && !hasBrowserBackup(manuscript.id)) {
    const id = manuscript.id
    const serverBook = await localRequest(`/api/book?id=${encodeURIComponent(id)}`, { signal: AbortSignal.timeout(5000) }).catch(() => null)
    if (serverBook && manuscript.id === id && !hasBrowserBackup(id)) Object.assign(manuscript, loadManuscript(serverBook))
  }
  manuscript.activeId = activeChapter.value.id
  preferredVolumeId.value = activeVolume.value.id
  if (manuscript.aiMode && !manuscript.aiSetup.configured) {
    manuscript.aiMode = false
    openProfile()
  }
  hydrated = true
  if (hasCurrentBook.value) rememberBook(manuscript.id)
  saveCurrentBook()
  await routeLocation()
})
onUnmounted(() => {
  for (const stop of chapterSaveWatches.values()) stop()
  aiController?.abort()
  backfillController?.abort()
  if (props.android) {
    clearTimeout(positionTimer)
    window.qingjianBack = () => androidCall('navigate', { page: 'Shelf' }).catch(() => {})
    delete window.qingjianTheme
    delete window.qingjianKeyboard
  }
  window.removeEventListener('keydown', shortcuts)
  window.removeEventListener('resize', updateViewport)
  window.removeEventListener('hashchange', routeLocation)
})
</script>

<template>
  <div class="studio" :class="{ 'focus-mode': focusMode, 'desktop-studio': desktop, 'android-studio': android }">
    <header class="topbar" :class="{ 'desktop-titlebar': desktop, 'webview-titlebar': webview }">
      <div class="topbar-leading">
        <button v-if="researchOpen" class="toolbar-button library-back" aria-label="返回书稿" title="返回书稿" @click="leaveResearch">
          <ArrowLeft :size="18" /><span>返回书稿</span>
        </button>
        <button v-if="!focusMode && !shelfOpen && !researchOpen" class="icon-button sidebar-toggle" :aria-label="navigationVisible ? '收起章节导航' : '打开章节导航'" :title="navigationVisible ? '收起章节导航' : '打开章节导航'" :aria-expanded="navigationVisible" aria-controls="manuscript-navigation" @click="toggleNavigation">
          <PanelLeftClose v-if="navigationVisible" :size="18" /><PanelLeftOpen v-else :size="18" />
        </button>
        <button class="brand brand-home" :aria-current="shelfOpen ? 'page' : undefined" aria-label="书架" title="打开书架" @click="showShelf()">
          <img :src="iconUrl" class="brand-app-icon" alt="" />
          <span class="brand-name">青简</span>
        </button>
        <button v-if="!shelfOpen && !researchOpen && !roleplayOpen" class="toolbar-button knowledge-entry" aria-label="知识库与上下文" title="知识库与上下文" @click="showResearch('context', null)"><Database :size="17" /><span>知识与上下文</span></button>
      </div>
      <div v-if="!shelfOpen" class="top-actions">
        <button v-if="!desktop" class="icon-button" aria-label="全局设置" title="全局设置" @click="openSettings"><Settings2 :size="17" /></button>
        <button v-if="!researchOpen" class="toolbar-button ai-mode-toggle" :class="{ 'toolbar-active': manuscript.aiMode }" aria-label="AI 驱动" title="AI 驱动" :aria-pressed="manuscript.aiMode" @click="toggleAiMode">
          <Sparkles :size="16" /><span>AI 驱动</span>
        </button>
        <el-popover v-if="aiActivity.stage" trigger="click" :width="340" placement="bottom-end">
          <template #reference><button class="toolbar-button ai-top-progress" type="button" aria-label="AI 生成进度" :title="aiActivity.stage"><Sparkles :size="14" /><span>{{ aiIntentLabels[aiActivity.intent] || 'AI 生成' }}</span></button></template>
          <div class="assistant-progress"><strong>{{ aiActivity.stage }}</strong><div v-if="aiActivity.pending" class="ai-progress-track" /><pre v-if="aiActivity.summary || aiActivity.preview">{{ aiActivity.summary || aiActivity.preview }}</pre></div>
        </el-popover>
        <button class="save-state" :class="{ 'save-error': !['pending', 'saved'].includes(backupState), 'save-pending': backupState === 'pending' }" :title="`保存与备份 · ${saveStatus}`" aria-label="保存与备份" @click="openBackup">
          <CircleCheck v-if="['pending', 'saved'].includes(backupState)" :size="16" /><CloudAlert v-else :size="16" /><span role="status">{{ saveStatus }}</span>
        </button>
        <el-dropdown v-if="!researchOpen" trigger="click" placement="bottom-end" @command="exportManuscript">
          <el-button type="primary" class="export-button"><ArrowDownToLine :size="15" />导出<ChevronDown class="export-chevron" :size="12" /></el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="chapter">导出当前章节 · TXT</el-dropdown-item>
              <el-dropdown-item command="book-txt">导出全书 · TXT</el-dropdown-item>
              <el-dropdown-item command="book-md">导出全书 · Markdown</el-dropdown-item>
              <el-dropdown-item command="book-json">导出全书 · 可导入 JSON</el-dropdown-item>
              <el-dropdown-item command="book-epub">导出全书 · EPUB</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <button v-if="!focusMode && assistantAiMode && !researchOpen" class="icon-button sidebar-toggle" :aria-label="assistantVisible ? '收起写作助手' : '打开写作助手'" :title="assistantVisible ? '收起写作助手' : '打开写作助手'" :aria-expanded="assistantVisible" aria-controls="writing-assistant" @click="toggleAssistant">
          <PanelRightClose v-if="assistantVisible" :size="18" /><PanelRightOpen v-else :size="18" />
        </button>
      </div>
      <div v-else-if="!desktop" class="top-actions logout-actions">
        <button class="icon-button" aria-label="全局设置" title="全局设置" @click="settingsOpen = true"><Settings2 :size="17" /></button>
        <el-button v-if="sessionRequired" text :icon="KeyRound" @click="passwordOpen = true">修改密码</el-button>
        <el-button v-if="sessionRequired" text :icon="LogOut" @click="signOut">退出登录</el-button>
      </div>
      <div v-if="desktop" class="desktop-title-tools">
        <button class="icon-button" aria-label="全局设置" title="全局设置" @click="settingsOpen = true"><Settings2 :size="17" /></button>
      </div>
      <div v-if="webview" class="window-controls" aria-label="窗口控制">
        <button type="button" title="最小化" aria-label="最小化" @click="windowAction('minimize')"><Minus :size="16" /></button>
        <button type="button" title="最大化或还原" aria-label="最大化或还原" @click="windowAction('maximize')"><Square :size="13" /></button>
        <button type="button" title="关闭窗口" aria-label="关闭窗口" data-close-window @click="windowAction('close')"><X :size="15" /></button>
      </div>
    </header>

    <BookShelf v-if="shelfOpen" :layout="preferences.shelfLayout" @update:layout="value => savePreferences({ shelfLayout: value }).catch(error => ElMessage.error(error.message))" :books="shelfBooks" :active-id="hasCurrentBook ? manuscript.id : ''" :busy="openingBook"
      :importing="importingBook" :server="desktop && !webview" @server="openSyncShelf" @create="showBookCreator" @open="openFromShelf" @manage="manageBook" @import="importBackup" />
    <ResearchDrawer v-else-if="researchOpen" :key="manuscript.id" :book="manuscript" :chapters="chapterEntries" :requested-tab="researchTab" :anchor="researchAnchor"
      @jump="creativeJump">
      <template #cards><CreativeDesk inline tab="cards" :book="manuscript" :chapter="activeChapter" :chapters="chapterEntries" :connection="connection" :flush="flush" @jump="creativeJump" /></template>
      <template #assets><AssetLibrary :book="manuscript" :chapters="chapterEntries" @jump="creativeJump" /></template>
      <template #ai><LibraryAi :book="manuscript" :chapters="chapterEntries" :connection="connection" @connection="openConnection" /></template>
      <template #world><WorldBook :book="manuscript" :chapter="activeChapter" /></template>
      <template #context><ContextDialog inline :book="manuscript" :chapter="activeChapter" :chapters="chapterEntries" :connection="connection" @connection="openConnection">
        <template #actions><el-button :icon="PenLine" @click="openProfile(true)">创作设定与 AI 填写</el-button></template>
      </ContextDialog></template>
      <template #usage><UsageHeatmap /></template>
      <template #story><StoryDesk :book="manuscript" :chapters="chapterEntries" :backfill="backfill"
        @jump="creativeJump" @next="showNextChapter" @backfill="backfillHandoffs" @cancel-backfill="backfillController?.abort()" @confirm-link="confirmStoryLink" /></template>
      <template #creative="{ tab }"><CreativeDesk :key="tab + activeChapter.id" inline :tab="tab" :book="manuscript" :chapter="activeChapter" :chapters="chapterEntries" :connection="connection"
        :flush="flush" @jump="creativeJump" @profile="openProfile(true)" @connection="openConnection" @apply-draft="applyCreativeDraft" @restore-chapter="restoreChapterVersion" /></template>
    </ResearchDrawer>
    <RoleplayRoom v-if="roleplayOpen && !shelfOpen" :key="manuscript.id" :book="manuscript" :connection="connection"
      @close="leaveRoleplay" @connection="openConnection" />
    <div v-if="!shelfOpen" v-show="!researchOpen && !roleplayOpen" class="workspace workspace-open" :class="{ 'navigation-hidden': !navigationVisible, 'assistant-hidden': !assistantVisible }">
      <Transition name="sidebar-scrim">
        <button v-if="mobileNavigation || (assistantAiMode && mobileAssistant)" class="mobile-overlay" aria-label="关闭侧栏" @click="mobileNavigation = false; mobileAssistant = false"></button>
      </Transition>

      <aside id="manuscript-navigation" class="navigation" :class="{ 'mobile-open': mobileNavigation }" :inert="!navigationVisible || focusMode" aria-label="书稿导航">
        <section class="book-card">
          <div class="book-cover" :class="{ 'generic-cover': manuscript.id !== starterBookId }" aria-hidden="true">
            <img v-if="bookCover(manuscript)" :src="bookCover(manuscript)" alt="" />
            <BookOpen v-else-if="manuscript.id !== starterBookId" :size="19" />
          </div>
          <div class="book-info">
            <h1>{{ manuscript.title }}</h1>
            <span class="book-meta">{{ manuscript.volumes.length }} 卷 <span class="meta-dot">·</span> {{ chapterEntries.length }} 章 <span class="meta-dot">·</span> {{ formatCount(bookWords) }} 字</span>
          </div>
        </section>

        <div class="section-tabs" aria-label="书稿分类">
          <button v-for="item in sections" :key="item.id" :class="{ selected: section === item.id }" :aria-pressed="section === item.id" @click="showSection(item.id)">
            {{ item.label }}
          </button>
        </div>

        <template v-if="section === 'chapters'">
          <div class="chapter-search-row">
            <el-input v-model="chapterFilter" :prefix-icon="Search" placeholder="搜索章节" aria-label="搜索章节" clearable />
            <el-button class="new-volume-button" :icon="FolderPlus" aria-label="新建卷" title="新建卷" @click="showVolumeEditor()" />
            <el-button class="new-chapter-button" :icon="FilePlus" aria-label="新建章节" title="新建章节" @click="showNewChapter()" />
          </div>
          <div class="chapter-list">
            <section v-for="{ volume, entries } in visibleVolumes" :id="`volume-${volume.id}`" :key="volume.id" class="volume-group" :aria-label="volume.title">
              <div class="volume-row">
                <button class="volume-button" :title="volume.title" :aria-expanded="Boolean(filterQuery) || !collapsedVolumes.has(volume.id)" :disabled="Boolean(filterQuery)" @click="toggleVolume(volume)">
                  <ChevronDown v-if="filterQuery || !collapsedVolumes.has(volume.id)" :size="14" /><ChevronRight v-else :size="14" />
                  <span>{{ volume.title }}</span>
                </button>
                <span class="volume-count" :aria-label="`${volume.chapters.length} 章`">{{ volume.chapters.length }}</span>
                <el-dropdown trigger="click" placement="bottom-start" @command="handleVolumeAction($event, volume)">
                  <button class="icon-button row-actions" :aria-label="`${volume.title}的卷操作`" title="卷操作"><Ellipsis :size="16" /></button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item command="new">在此卷新建章节</el-dropdown-item>
                      <el-dropdown-item command="rename">重命名卷</el-dropdown-item>
                      <el-dropdown-item command="delete" divided :disabled="Boolean(volume.chapters.length) || manuscript.volumes.length === 1">删除空卷</el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </div>
              <template v-if="filterQuery || !collapsedVolumes.has(volume.id)">
                <ul v-if="entries.length" class="chapters">
                  <li v-for="entry in entries" :id="`row-${entry.chapter.id}`" :key="entry.chapter.id" class="chapter-row" :class="{ 'current-row': entry.chapter.id === activeChapter.id }">
                    <button class="chapter-button" :title="`${entry.label} ${entry.chapter.title}`" :aria-current="entry.chapter.id === activeChapter.id ? 'page' : undefined" @click="openChapter(entry.index)">
                      <FileText :size="16" :stroke-width="1.65" />
                      <span class="chapter-name">{{ entry.label }} <span>{{ entry.chapter.title }}</span></span>
                      <Check v-if="entry.chapter.complete" class="chapter-check" :size="13" aria-label="定稿" />
                    </button>
                    <el-dropdown trigger="click" placement="bottom-start" @command="handleChapterAction($event, entry)">
                      <button class="icon-button row-actions" :aria-label="`${entry.label} ${entry.chapter.title}的章节操作`" title="章节操作"><Ellipsis :size="16" /></button>
                      <template #dropdown>
                        <el-dropdown-menu>
                          <el-dropdown-item command="rename">重命名章节</el-dropdown-item>
                          <el-dropdown-item command="up" :disabled="entry.position === 0">上移一章</el-dropdown-item>
                          <el-dropdown-item command="down" :disabled="entry.position === volume.chapters.length - 1">下移一章</el-dropdown-item>
                          <el-dropdown-item command="move" :disabled="manuscript.volumes.length < 2">移动到其他卷</el-dropdown-item>
                          <el-dropdown-item class="delete-chapter-item" command="delete" divided :disabled="chapterEntries.length <= 1">删除章节</el-dropdown-item>
                        </el-dropdown-menu>
                      </template>
                    </el-dropdown>
                  </li>
                </ul>
                <button v-else class="add-chapter-link" @click="showNewChapter(volume.id)"><Plus :size="15" />新建本卷章节</button>
              </template>
            </section>
            <p v-if="!visibleVolumes.length" class="search-empty">没有匹配的章节</p>
          </div>
        </template>
        <div v-else class="section-nav">
          <button class="chapter-button current" @click="mobileNavigation = false">
            <LayoutList v-if="section === 'outline'" :size="16" /><BookOpen v-else :size="16" />
            {{ section === 'outline' ? '全书大纲' : '人物与世界设定' }}
          </button>
        </div>

        <div class="goal-card">
          <div class="goal-heading"><Target :size="17" /><span>本章写作目标</span><button class="icon-button" aria-label="编辑写作目标" title="编辑目标" @click="showSettings"><Settings2 :size="15" /></button></div>
          <el-progress :percentage="progress" :show-text="false" :stroke-width="5" :color="preferences.accent" />
          <div class="goal-numbers"><span><strong>{{ formatCount(chapterWords) }}</strong> / {{ formatCount(chapterGoal) }} 字</span><span>{{ progress }}%</span></div>
        </div>
      </aside>

      <main class="writing">
        <div class="editor-toolbar">
          <div v-if="section === 'chapters'" class="toolbar-group editing-tools">
            <button class="icon-button" title="撤销 (Ctrl+Z)" aria-label="撤销" :disabled="!chapterHistory?.undo.length" @click="restoreText('undo')"><Undo2 :size="18" /></button>
            <button class="icon-button" title="重做 (Ctrl+Shift+Z)" aria-label="重做" :disabled="!chapterHistory?.redo.length" @click="restoreText('redo')"><Redo2 :size="18" /></button>
          </div>
          <span v-if="section === 'chapters'" class="toolbar-divider"></span>
          <div class="toolbar-group">
            <el-popover trigger="click" placement="bottom-start" :width="216" popper-class="appearance-popover">
              <template #reference>
                <button class="toolbar-button" title="字体和纸张"><Type :size="17" /><span>排版</span><ChevronDown :size="12" /></button>
              </template>
              <div class="appearance-title">正文字号</div>
              <el-radio-group v-model="manuscript.fontSize" size="small">
                <el-radio-button v-for="size in [14, 16, 18, 20]" :key="size" :value="size">{{ size }}</el-radio-button>
              </el-radio-group>
              <div class="appearance-title paper-options-title">纸张颜色</div>
              <div class="paper-options">
                <button v-for="paper in papers" :key="paper.id" :class="{ selected: manuscript.paper === paper.id }" :aria-pressed="manuscript.paper === paper.id" @click="manuscript.paper = paper.id">
                  <span :style="{ background: paper.color }"></span>{{ paper.label }}
                </button>
              </div>
              <div class="appearance-title paper-options-title">场景分隔线</div>
              <el-radio-group v-model="manuscript.dividerStyle" size="small" aria-label="分隔线样式">
                <el-radio-button v-for="style in dividerStyles" :key="style.id" :value="style.id">{{ style.label }}</el-radio-button>
              </el-radio-group>
            </el-popover>
            <template v-if="section === 'chapters'">
              <button v-if="!manuscript.aiMode" class="toolbar-button find-button" aria-label="查找替换" title="查找替换 (Ctrl+F)" @click="dialog = 'search'"><Search :size="16" /><span>查找替换</span></button>
              <el-dropdown trigger="click" @command="insertContent">
                <button class="toolbar-button insert-button" title="插入" aria-label="插入"><Plus :size="17" /><span>插入</span><ChevronDown :size="12" /></button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="divider" :disabled="Boolean(aiReview?.stage)">场景分隔线</el-dropdown-item>
                    <el-dropdown-item v-if="!manuscript.aiMode" command="indent">段首缩进</el-dropdown-item>
                    <el-dropdown-item v-if="preferences.images" command="image">生成配图或封面</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
            <el-dropdown trigger="click" @command="insertMarkdown">
              <button class="toolbar-button" title="插入 Markdown 标记" aria-label="插入 Markdown 标记"><FileText :size="16" /><span>Markdown</span><ChevronDown :size="11" /></button>
              <template #dropdown><el-dropdown-menu><el-dropdown-item v-for="[id, label] in markdownCommands" :key="id" :command="id">{{ label }}</el-dropdown-item></el-dropdown-menu></template>
            </el-dropdown>
            <el-button v-if="section !== 'chapters'" size="small" :icon="Sparkles" :loading="Boolean(studioBusy)" @click="generateDocument">AI 生成{{ section === 'outline' ? '大纲' : '设定' }}</el-button>
            <el-dropdown v-else trigger="click" @command="openBatch">
              <button class="toolbar-button" aria-label="AI 改稿" title="AI 改稿"><Sparkles :size="16" /><span>AI 改稿{{ batchSelection.length ? ' · ' + batchSelection.length : '' }}</span></button>
              <template #dropdown><el-dropdown-menu><el-dropdown-item command="batch">重新生成选中段落</el-dropdown-item><el-dropdown-item command="chapter-rewrite">整章改稿</el-dropdown-item></el-dropdown-menu></template>
            </el-dropdown>
          </div>
          <div class="toolbar-group toolbar-right">
            <el-button v-if="activeReview" size="small" @click="reviewOpen = true">查看生成稿</el-button>
            <button v-if="!assistantAiMode" class="toolbar-button markdown-toggle" :class="{ 'toolbar-active': sourceMode }"
              aria-label="编辑整篇 Markdown 源码" :aria-pressed="sourceMode" title="编辑整篇 Markdown 源码" @click="sourceMode = !sourceMode">
              <FileText :size="16" /><span>源码</span>
            </button>
            <button class="toolbar-button roleplay-entry" aria-label="文本扮演" title="文本扮演" @click="openRoleplay"><MessageCircle :size="16" /></button>
            <button class="toolbar-button" aria-label="创作工作台" title="创作工作台" @click="showCreative('references')"><Layers3 :size="16" /></button>
            <button class="toolbar-button focus-button" :class="{ 'toolbar-active': focusMode }" :aria-label="focusMode ? '退出专注' : '专注模式'" :aria-pressed="focusMode" @click="focusMode = !focusMode"><Focus :size="17" /><span>{{ focusMode ? '退出专注' : '专注模式' }}</span></button>
          </div>
        </div>

        <div class="editor-scroll-shell" :style="{ '--manuscript-width': `${paperWidth}px` }">
        <div id="manuscript-scroll" ref="editorScroll" class="paper-scroll" @scroll.passive="scheduleAndroidPosition">
          <article id="manuscript-paper" :key="section" class="paper section-enter" :class="`paper-${manuscript.paper}`" :style="{ '--manuscript-font-size': `${manuscript.fontSize}px` }"
            @click="clickParagraph" @pointerdown="startParagraphDrag" @pointerup="endParagraphDrag" @pointercancel="paragraphDrag = null">
            <template v-if="section === 'chapters'">
              <div class="chapter-heading">
                <h2><span>{{ activeLabel }}</span> {{ activeChapter.title }}</h2>
                <button class="icon-button rename-button" aria-label="重命名章节" title="重命名章节" @click="showTitleEditor()"><PenLine :size="16" /></button>
              </div>
              <div class="chapter-details">
                <el-dropdown trigger="click" @command="toggleComplete">
                  <button class="chapter-status" :class="{ complete: activeChapter.complete }"><span class="status-dot"></span>{{ activeChapter.complete ? '已定稿' : '草稿' }}<ChevronDown :size="11" /></button>
                  <template #dropdown><el-dropdown-menu><el-dropdown-item command="status">{{ activeChapter.complete ? '恢复为草稿' : '标记为定稿' }}</el-dropdown-item></el-dropdown-menu></template>
                </el-dropdown>
                <span class="detail-divider"></span>
                <span>{{ formatCount(chapterWords) }} 字</span>
                <span class="detail-divider"></span>
                <span class="chapter-volume">{{ activeVolume.title }}</span>
              </div>
              <div class="paper-rule"></div>
              <AiWorkspace v-if="manuscript.aiMode" ref="aiEditor" :book="manuscript" :chapter="activeChapter" :chapters="chapterEntries" :connection="connection" :jump-to="revisionJump"
                :assistant-visible="assistantVisible && !focusMode"
                :batch-selection="batchSelection" :paused="connectionOpen || profileOpen || researchOpen || skillsOpen || backupOpen || reviewOpen || Boolean(studioBusy) || Boolean(dialog)"
                @connection="openConnection" @profile="openProfile()" @skills="skillsOpen = true" @research="showResearch" @mutation="recordRevision"
                @next-chapter="showNextChapter" @creative="showCreative" @compare="openComparison" />
              <InlineManuscript v-else-if="showInlineMarkup" :key="activeChapter.id" ref="inlineEditor" :source="activeMarkdown" :blocks="activeMarkup"
                label="章节正文" manuscript :divider-style="manuscript.dividerStyle" @edit="editDocument" />
              <div v-else class="auto-editor">
                <div class="editor-sizing" aria-hidden="true"><template v-if="searchMatch">{{ activeChapter.text.slice(0, searchMatch.start) }}<span ref="matchMarker">{{ activeChapter.text.slice(searchMatch.start, searchMatch.end) }}</span>{{ activeChapter.text.slice(searchMatch.end) }}</template><template v-else>{{ activeChapter.text }}</template>&#8203;</div>
                <textarea :key="activeChapter.id" ref="editor" :value="activeChapter.text" class="manuscript-editor" aria-label="章节正文" placeholder="在这里写下正文…" spellcheck="false"
                  @input="onEditorInput" @compositionend="editorCompositionEnd" @select="updateSelection" @focus="updateSelection" @blur="selection = null"></textarea>
                <div v-if="visualBlocks.length" aria-hidden="true" class="editor-divider-overlay">
                  <div v-for="(block, index) in visualBlocks" :key="index" class="editor-overlay-block">{{ block.text }}&#8203;<SceneDivider v-if="showDivider(block)" class="editor-scene-visual" :variant="manuscript.dividerStyle" /></div>
                </div>
              </div>
            </template>
            <template v-else>
              <div class="chapter-heading"><h2>{{ section === 'outline' ? '全书大纲' : '人物与世界设定' }}</h2></div>
              <div class="paper-rule"></div>
              <InlineManuscript v-if="showInlineMarkup" :key="section" ref="inlineEditor" :source="activeMarkdown" :blocks="activeMarkup"
                :label="section === 'outline' ? '全书大纲' : '人物与世界设定'" @edit="editDocument" />
              <div v-else class="auto-editor">
                  <div class="editor-sizing" aria-hidden="true">{{ manuscript[section] }}&#8203;</div>
                  <textarea :key="section" ref="editor" :value="activeMarkdown" class="manuscript-editor" :aria-label="section === 'outline' ? '全书大纲' : '人物与世界设定'"
                    :placeholder="section === 'outline' ? '记录情节走向和章节安排…' : '记录人物、地点与世界规则…'" spellcheck="false"
                    @input="onEditorInput" @compositionend="editorCompositionEnd"></textarea>
              </div>
            </template>
          </article>
        </div>
        <EditorScrollHandle :target="editorScroll" />
        <EditorWidthHandle v-model="paperWidth" :target="editorScroll" @commit="savePaperWidth" />
        </div>

        <footer class="editor-footer">
          <div class="footer-count"><FilePenLine :size="15" /><span>{{ section === 'chapters' ? '本章' : '当前文档' }} <strong>{{ formatCount(section === 'chapters' ? chapterWords : wordCount(manuscript[section])) }}</strong> 字</span></div>
          <div v-if="section === 'chapters'" class="chapter-pagination">
            <button class="icon-button" aria-label="上一章" title="上一章" :disabled="activeIndex === 0" @click="openChapter(activeIndex - 1)"><ChevronLeft :size="17" /></button>
            <span>{{ activeIndex + 1 }} / {{ chapterEntries.length }}</span>
            <button class="icon-button" aria-label="下一章" title="下一章" :disabled="activeIndex >= chapterEntries.length - 1" @click="openChapter(activeIndex + 1)"><ChevronRight :size="17" /></button>
          </div>
        </footer>
      </main>

      <aside v-if="assistantAiMode" id="writing-assistant" class="assistant" :class="{ 'mobile-open': mobileAssistant }" :inert="!assistantVisible || focusMode" aria-label="写作助手">
        <header class="assistant-header">
          <h2>写作助手</h2>
          <button class="icon-button assistant-close" aria-label="关闭写作助手" title="关闭写作助手" @click="toggleAssistant"><X :size="17" /></button>
        </header>
        <div class="assistant-scroll">
            <section class="assistant-status">
              <div class="assistant-status-row">
                <span class="banner-label"><span></span>{{ { openai: 'OpenAI', deepseek: 'DeepSeek', auto: '账号轮询', codex: 'Codex CLI' }[preferences.provider] }} · {{ aiReview?.ready ? '已连接' : '待连接' }}</span>
                <span v-if="aiReview?.pending" class="assistant-pending">{{ aiReview.pending }} 个待审批</span>
              </div>
              <section v-if="aiActivity.stage" class="assistant-progress" :class="{ 'in-progress': aiActivity.pending }" aria-live="polite">
                <strong>{{ aiIntentLabels[aiActivity.intent] || 'AI 生成' }} · {{ aiActivity.stage }}</strong><div v-if="aiActivity.pending" class="ai-progress-track" />
                <details v-if="aiActivity.summary || aiActivity.preview" :open="aiActivity.pending"><summary>{{ aiActivity.summary ? '模型思考摘要' : '正在输出' }}</summary><pre>{{ aiActivity.summary || aiActivity.preview }}</pre></details>
              </section>
            </section>
            <section v-if="aiReview?.stage || aiReview?.excerpt || aiReview?.usage" class="assistant-target">
              <h3 v-if="aiReview?.stage">章节摘要</h3>
              <p v-if="aiReview?.stage || aiReview?.excerpt">{{ aiReview?.stage ? (activeChapter.plan.summary || '先生成并确认本章摘要。') : aiReview.excerpt }}</p>
              <p v-if="aiReview?.usage" class="ai-request-usage">{{ aiReview.usage.model || aiReview.usage.account }} · 输入 {{ aiReview.usage.input ?? '—' }} / 输出 {{ aiReview.usage.output ?? '—' }} tokens</p>
            </section>
            <section aria-label="写作操作">
              <div class="tool-grid">
                <button v-if="!aiReview?.stage" class="tool-button" :disabled="aiReview?.busy" @click="aiEditor?.openSend()"><Send :size="16" />发送给 AI</button>
                <button v-if="!aiReview?.stage" class="tool-button" :aria-pressed="aiReview?.auto" @click="aiEditor?.toggleAuto()"><component :is="aiReview?.auto ? Pause : Play" :size="16" />{{ aiReview?.auto ? '暂停自动生成' : '恢复自动生成' }}</button>
                <button class="tool-button" @click="skillsOpen = true"><Layers3 :size="16" />写作技能{{ aiReview?.skillCount ? ` · ${aiReview.skillCount}` : '' }}</button>
                <button class="tool-button" @click="openProfile(true)"><PenLine :size="16" />创作设定</button>
              </div>
              <div class="assistant-secondary">
                <button class="text-action" @click="openConnection">AI 服务</button>
                <button class="text-action" @click="showNextChapter"><FilePlus :size="15" />新建下一章</button>
              </div>
            </section>
        </div>
        <form v-if="!aiReview?.stage" class="assistant-composer" @submit.prevent="sendPrompt">
          <div class="scope-row ai-scope"><span><PenLine :size="14" />逐段提示</span><span v-if="aiReview?.selected">第 {{ aiReview.selected }} 段</span></div>
          <div class="composer-input">
            <el-input v-model="prompt" aria-label="告诉 AI 这一段怎么改" placeholder="例如：收紧这一段的节奏" />
            <el-button type="primary" native-type="submit" :icon="ArrowUp" aria-label="发送提问" :disabled="!prompt.trim()" />
          </div>
        </form>
      </aside>
    </div>

    <nav v-if="!desktop && !shelfOpen && !researchOpen && !roleplayOpen && !focusMode && !mobileNavigation && !mobileAssistant" class="mobile-writing-nav" aria-label="移动写作导航">
      <button type="button" @click="showShelf()"><BookOpen :size="18" />书架</button>
      <button type="button" @click="toggleNavigation"><LayoutList :size="18" />章节</button>
      <button type="button" @click="showResearch('context', null)"><Database :size="18" />工作台</button>
      <button type="button" @click="openRoleplay"><MessageCircle :size="18" />文本扮演</button>
    </nav>
    <div v-if="android && researchOpen" class="android-native-tools">
      <el-button @click="androidGo('Desk')">原生工作台</el-button>
      <el-button @click="openConnection">模型供应商</el-button>
    </div>
    <AiConnection v-model:open="connectionOpen" :info="connection" @status="emit('accounts-change', $event)" />
    <StudioSettings v-model:open="settingsOpen" v-model:auto-fullscreen="autoFullscreen" :desktop="desktop" @accounts="connectionOpen = true" />
    <FullscreenReview v-if="reviewOpen && activeReview" :review="activeReview" @close="reviewOpen = false" @apply="applyComparison" @reject="rejectComparison" />
    <ImageStudio v-if="imageOpen && preferences.images" v-model:open="imageOpen" :book="manuscript" :chapter="activeChapter" />
    <AiSetupDialog v-model:open="profileOpen" :book="manuscript" :chapters="chapterEntries" :connection="connection" :profile-only="profileOnly"
      @configured="applyProfile" @connection="openConnection" @references="profileOpen = false; showCreative('references')" />
    <SkillLibrary v-model:open="skillsOpen" :book="manuscript" :chapter="activeChapter" />

    <el-dialog v-model="backupOpen" title="保存与备份" :width="backupTab === 'git' ? 'min(930px, 94vw)' : 'min(470px, 94vw)'">
      <strong class="backup-result" :class="backupState">{{ saveStatus }}</strong>
      <p v-if="android" class="dialog-description">书稿与还原点保存在本机。</p>
      <p v-else-if="desktop" class="dialog-description">书稿和还原点保存在此电脑的青简数据目录。可以下载全书 JSON 另存一份。</p>
      <template v-else>
        <p class="dialog-description">修改会先写入当前浏览器，再保存到本地服务。</p>
        <p class="dialog-description">浏览器备份仅保留在当前浏览器，清理网站数据会丢失。</p>
      </template>
      <div class="dialog-actions"><el-button @click="saveNow">立即保存</el-button><el-button :icon="ArrowDownToLine" type="primary" @click="android ? exportManuscript('book-json') : downloadBookBackup(manuscript)">导出全书 JSON</el-button></div>
      <div v-if="android" class="dialog-actions"><el-button @click="androidGo('Native')">跨端同步与 Git</el-button></div>
      <template v-else-if="!webview">
        <p class="dialog-description">跨端同步 · {{ synchronization.get(manuscript.id) || (syncLinks[manuscript.id] ? '等待同步' : '未开启') }}</p>
        <el-form v-if="desktop && !syncTarget" label-position="top" @submit.prevent="configureSync('sign-in')">
          <el-form-item label="青简服务器 HTTPS 地址"><el-input v-model="syncEndpoint" /></el-form-item>
          <el-form-item label="账号"><el-input v-model="syncUsername" autocomplete="username" /></el-form-item>
          <el-form-item label="密码"><el-input v-model="syncPassword" type="password" autocomplete="current-password" /></el-form-item>
          <el-button :loading="syncBusy" native-type="submit">连接服务器</el-button>
        </el-form>
        <div v-else class="dialog-actions">
          <el-button v-if="syncLinks[manuscript.id] && !syncLinks[manuscript.id].paused" :disabled="syncBusy" @click="configureSync('disable')">暂停同步此书</el-button>
          <el-button v-else :loading="syncBusy" @click="configureSync('enable')">{{ syncLinks[manuscript.id]?.paused ? '恢复同步此书' : '同步此书' }}</el-button>
          <el-button v-if="desktop" :disabled="syncBusy" @click="configureSync('sign-out')">退出服务器</el-button>
        </div>
      </template>
      <nav v-if="!android" class="resource-tabs backup-tabs" aria-label="备份方式">
        <button :aria-pressed="backupTab === 'versions'" @click="backupTab = 'versions'">全书还原点</button>
        <button :aria-pressed="backupTab === 'git'" @click="backupTab = 'git'">Git 时间线</button>
      </nav>
      <BookVersions v-if="backupOpen && backupTab === 'versions'" :book="manuscript" @restore="restoreBookVersion" />
      <GitTimeline v-else-if="backupOpen" :book="manuscript" @restore="restoreBookVersion" />
    </el-dialog>

    <el-dialog v-model="syncShelf" title="服务器书架" width="min(470px, 94vw)">
      <el-form v-if="!syncTarget" label-position="top" @submit.prevent="configureSync('sign-in')">
        <el-form-item label="青简服务器 HTTPS 地址"><el-input v-model="syncEndpoint" /></el-form-item>
        <el-form-item label="账号"><el-input v-model="syncUsername" autocomplete="username" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="syncPassword" type="password" autocomplete="current-password" /></el-form-item>
        <el-button :loading="syncBusy" native-type="submit">连接服务器</el-button>
      </el-form>
      <template v-else>
        <p v-if="!syncBooks.length">服务器书架没有作品。</p>
        <p v-for="book in syncBooks" :key="book.id">
          <el-button :loading="syncBusy" @click="openSyncBook(book.id)">{{ book.title }}</el-button>
        </p>
        <el-button text :disabled="syncBusy" @click="configureSync('sign-out')">退出服务器</el-button>
      </template>
    </el-dialog>

    <BookDetailsDialog v-if="shelfOpen" v-model:open="bookEditorOpen" :book="editingBook" :saving="savingBookDetails" @save="saveBookDetails" />
    <BookImportPreview v-if="importPreview" :book="importPreview" :matches="importMatches" :busy="importingBook" @cancel="importPreview = null" @confirm="confirmImport" />
    <ChangePasswordDialog v-if="sessionRequired && shelfOpen" v-model:open="passwordOpen" />

    <el-dialog :model-value="dialog === 'new'" :title="nextFromChapterId ? '新建下一章' : '新建章节'" width="min(420px, 92vw)" @update:model-value="dialog = ''">
      <el-form label-position="top" @submit.prevent="createChapter">
        <p v-if="nextFromChapterId || manuscript.aiMode" class="dialog-description next-chapter-description">先生成并确认摘要，再生成完整初稿。</p>
        <el-form-item v-if="manuscript.volumes.length > 1 && !nextFromChapterId" label="所在卷">
          <el-select v-model="destinationVolumeId" aria-label="所在卷"><el-option v-for="volume in manuscript.volumes" :key="volume.id" :label="volume.title" :value="volume.id" /></el-select>
        </el-form-item>
        <el-form-item label="章节名称"><el-input v-model="newTitle" :placeholder="nextFromChapterId || manuscript.aiMode ? '未命名章节' : '输入章节名称'" maxlength="60" autofocus /></el-form-item>
        <template v-if="(manuscript.aiMode || nextFromChapterId) && newChapterPredecessor?.handoff">
          <div class="handoff-bridge resource-record"><strong>承接 {{ newChapterPredecessor.title }}</strong><p>{{ newChapterPredecessor.handoff.bridge }}</p></div>
          <el-form-item label="预选下一章走向">
            <el-select v-model="nextDirection" filterable allow-create clearable>
              <el-option v-for="item in newChapterPredecessor.handoff.directions || []" :key="item.title" :label="item.title" :value="item.detail" />
            </el-select>
          </el-form-item>
          <p v-if="nextDirection" class="dialog-description">{{ nextDirection }}</p>
        </template>
        <div class="dialog-actions"><el-button @click="dialog = ''">取消</el-button><el-button type="primary" native-type="submit" :disabled="!nextFromChapterId && !manuscript.aiMode && !newTitle.trim()">{{ manuscript.aiMode ? '创建并生成摘要' : '创建章节' }}</el-button></div>
      </el-form>
    </el-dialog>
    <el-dialog :model-value="dialog === 'handoff'" title="本章定稿与下一章交接" width="min(760px, 94vw)" @update:model-value="dialog = ''">
      <el-form v-if="handoffDraft" label-position="top" @submit.prevent="finishHandoff">
        <el-form-item label="本章剧情概括"><el-input v-model="handoffDraft.recap" type="textarea" :rows="3" maxlength="1600" /></el-form-item>
        <el-form-item label="下一章必须承接的铺垫"><el-input v-model="handoffDraft.bridge" type="textarea" :rows="3" maxlength="800" /></el-form-item>
        <div v-if="handoffDraft.lessons.length" class="resource-record"><strong>写作反馈学习</strong><p v-for="(item, index) in handoffDraft.lessons" :key="index">{{ item.kind === 'avoid' ? '避免' : '沿用' }} · {{ item.text }}</p></div>
        <div v-if="handoffDraft.links?.length" class="resource-record"><strong>核对因果候选</strong>
          <div v-for="(item, index) in handoffDraft.links" :key="index">
            <el-checkbox v-model="item.confirmed">{{ storyLinkLabel(item) }}</el-checkbox>
            <p>{{ item.quote }}</p>
          </div>
        </div>
        <div v-if="handoffDraft.directions.length" class="library-record-grid"><article v-for="(item, index) in handoffDraft.directions" :key="index" class="resource-record creative-form"><el-input v-model="item.title" maxlength="80" aria-label="走向标题" /><el-input v-model="item.detail" type="textarea" :rows="2" maxlength="550" aria-label="走向说明" /></article></div>
        <div class="dialog-actions"><el-button :icon="Sparkles" :loading="studioBusy === 'chapter-close'" @click="generateHandoff">AI 整理交接</el-button><el-button @click="dialog = ''">取消</el-button><el-button type="primary" native-type="submit" :disabled="Boolean(studioBusy) || !handoffDraft.recap.trim() || !handoffDraft.bridge.trim()">完成定稿</el-button></div>
      </el-form>
    </el-dialog>

    <el-dialog :model-value="dialog === 'rename'" title="重命名章节" width="min(420px, 92vw)" @update:model-value="dialog = ''">
      <el-form label-position="top" @submit.prevent="renameChapter">
        <el-form-item label="章节名称"><el-input v-model="editTitle" maxlength="60" autofocus /></el-form-item>
        <div class="dialog-actions"><el-button @click="dialog = ''">取消</el-button><el-button type="primary" native-type="submit" :disabled="!editTitle.trim()">保存</el-button></div>
      </el-form>
    </el-dialog>

    <el-dialog :model-value="dialog === 'volume'" :title="volumeToEdit ? '重命名卷' : '新建卷'" width="min(420px, 92vw)" @update:model-value="dialog = ''">
      <el-form label-position="top" @submit.prevent="saveVolume">
        <el-form-item label="卷名"><el-input v-model="volumeTitle" :placeholder="nextVolumeTitle" maxlength="40" autofocus /></el-form-item>
        <el-form-item v-if="!volumeToEdit && manuscript.aiMode" label="下一卷走向"><el-input v-model="volumeDirection" type="textarea" :rows="5" maxlength="1000" /></el-form-item>
        <el-button v-if="!volumeToEdit && manuscript.aiMode" :icon="Sparkles" :loading="studioBusy === 'volume'" @click="requestVolumeSuggestion">AI 建议卷名和走向</el-button>
        <div class="dialog-actions"><el-button @click="dialog = ''">取消</el-button><el-button type="primary" native-type="submit" :disabled="Boolean(volumeToEdit) && !volumeTitle.trim()">{{ volumeToEdit ? '保存' : '创建卷' }}</el-button></div>
      </el-form>
    </el-dialog>
    <el-dialog :model-value="['batch', 'chapter-rewrite'].includes(dialog)" :title="dialog === 'batch' ? '重新生成选段' : '整章改稿'" width="min(670px, 95vw)" @update:model-value="dialog = ''">
      <p v-if="dialog === 'batch'" class="dialog-description">可拖选多段，也可按 Ctrl 点击互不相邻的段落。</p>
      <el-select v-if="dialog === 'batch'" v-model="batchSelection" aria-label="待重新生成的段落" multiple filterable collapse-tags>
        <el-option v-for="(part, index) in activeChapter.paragraphs || []" :key="part.id" :value="part.id" :label="`第 ${index + 1} 段 · ${part.text.slice(0, 65)}`" />
      </el-select>
      <label class="field-stack">改稿要求<el-input v-model="batchPrompt" type="textarea" :rows="4" maxlength="1000" /></label>
      <div class="dialog-actions"><el-button @click="dialog = ''">取消</el-button><el-button type="primary" :disabled="Boolean(studioBusy) || (dialog === 'batch' && !batchSelection.length) || !activeChapter.text.trim()" @click="regenerate(dialog)">生成并对比</el-button></div>
    </el-dialog>

    <el-dialog :model-value="dialog === 'move'" title="移动章节" width="min(420px, 92vw)" @update:model-value="dialog = ''">
      <el-form label-position="top" @submit.prevent="moveChapter">
        <el-form-item label="目标卷">
          <el-select v-model="destinationVolumeId" aria-label="目标卷"><el-option v-for="volume in moveDestinations" :key="volume.id" :label="volume.title" :value="volume.id" /></el-select>
        </el-form-item>
        <div class="dialog-actions"><el-button @click="dialog = ''">取消</el-button><el-button type="primary" native-type="submit" :disabled="!destinationVolumeId">移动章节</el-button></div>
      </el-form>
    </el-dialog>

    <el-dialog :model-value="dialog === 'settings'" title="本章写作目标" width="min(420px, 92vw)" @update:model-value="dialog = ''">
      <el-form label-position="top" novalidate @submit.prevent="applySettings">
        <el-form-item label="本章目标字数"><el-input-number v-model="settings.goal" :min="1" :max="1000000" :step="100" :precision="0" /></el-form-item>
        <div class="dialog-actions"><el-button @click="dialog = ''">取消</el-button><el-button type="primary" native-type="submit" :disabled="!settings.goal">保存设置</el-button></div>
      </el-form>
    </el-dialog>

    <el-dialog :model-value="dialog === 'search'" title="查找替换" width="min(420px, 92vw)" @update:model-value="dialog = ''" @closed="revealSearchMatch">
      <el-form label-position="top" @submit.prevent="findNext">
        <el-form-item label="查找内容"><el-input v-model="search.query" clearable autofocus /></el-form-item>
        <el-form-item label="替换为"><el-input v-model="search.replacement" clearable /></el-form-item>
        <div class="find-actions"><span>{{ searchCount }} 处匹配</span><el-button :disabled="!searchCount" @click="replaceAll">全部替换</el-button><el-button type="primary" native-type="submit" :disabled="!searchCount">查找下一处</el-button></div>
      </el-form>
    </el-dialog>
  </div>
</template>
