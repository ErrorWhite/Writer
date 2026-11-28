import { newBookProfile } from './book-profile.js'
import { legacyBookStorageKey, readBrowserBook, starterBookId } from './shelf-store.js'
import { newContextOptions } from './context-options.js'
import { loadCreativeTools, newCreativeTools } from './creative-tools.js'

export const storageKey = legacyBookStorageKey

const chapters = [
  ['出发', '这是一份青简演示稿。\n\n你可以点这里开始写作。'],
  ['途中', '这段文字用于演示切章与续写。'],
  ['归来', '这一章用于演示章节查找与版本恢复。'],
  ['第四章', ''],
  ['第五章', ''],
  ['继续写作', ''],
]

const numbers = ['零', '一', '二', '三', '四', '五', '六', '七', '八', '九']
function numberedLabel(position, unit) {
  if (position < 10) return `第${numbers[position]}${unit}`
  if (position < 100) {
    const tens = Math.floor(position / 10)
    return `第${tens > 1 ? numbers[tens] : ''}十${position % 10 ? numbers[position % 10] : ''}${unit}`
  }
  return `第${position}${unit}`
}

export const chapterLabel = (position) => numberedLabel(position, '章')
export const volumeLabel = (position) => numberedLabel(position, '卷')

export function wordCount(text) {
  const chineseCharacters = text.match(/\p{Script=Han}/gu)?.length || 0
  const otherWords = text.replace(/\p{Script=Han}/gu, ' ').match(/[\p{L}\p{N}]+/gu)?.length || 0
  return chineseCharacters + otherWords
}
export const formatCount = (number) => number.toLocaleString('zh-CN')

function starterManuscript() {
  return {
    id: starterBookId,
    cover: '',
    title: '青简示例',
    activeId: 'chapter-6',
    volumes: [{
      id: 'volume-1',
      title: '第一卷 · 演示',
      chapters: chapters.map(([title, text], index) => ({
        id: `chapter-${index + 1}`,
        title,
        text: text.replace(/^([^\n])/gm, '　　$1'),
        complete: index < 5,
      })),
    }],
    outline: '',
    notes: '',
    goal: 500,
    fontSize: 16,
    paper: 'white',
    dividerStyle: 'ornament',
    navigationOpen: true,
    assistantOpen: true,
    aiMode: false,
    aiAuto: true,
    aiSetup: newBookProfile(),
    aiProvider: 'auto',
    context: newContextOptions(),
    skills: { enabled: [], custom: [], styleSample: '' },
    research: { annotations: [], foreshadows: [], events: [], links: [] },
    creative: newCreativeTools(),
    roleplay: { cards: [], sessions: [] },
  }
}

export function createManuscript(title) {
  const chapter = { id: crypto.randomUUID(), title: '开篇', text: '', complete: false }
  return {
    ...starterManuscript(),
    id: crypto.randomUUID(),
    title,
    activeId: chapter.id,
    volumes: [{ id: crypto.randomUUID(), title: '第一卷', chapters: [chapter] }],
    outline: '',
    notes: '',
  }
}

const validChapters = (items) => Array.isArray(items) && items.every((chapter) =>
  typeof chapter?.id === 'string' && typeof chapter.title === 'string' && typeof chapter.text === 'string')

function normalizeManuscript(source, defaults) {
  const { chapters: legacyChapters, volume: legacyTitle, ...saved } = source || {}
  delete saved.__proto__
  saved.research = { ...defaults.research, ...saved.research }
  saved.skills = { ...defaults.skills, ...saved.skills }
  saved.aiSetup = { ...defaults.aiSetup, ...saved.aiSetup }
  saved.context = { ...defaults.context, ...saved.context }
  saved.creative = loadCreativeTools(saved.creative)
  saved.roleplay = {
    ...saved.roleplay,
    cards: Array.isArray(saved.roleplay?.cards) ? saved.roleplay.cards.filter(item => item && typeof item.name === 'string' && typeof item.id === 'string') : [],
    sessions: Array.isArray(saved.roleplay?.sessions) ? saved.roleplay.sessions.filter(item => item && typeof item.cardId === 'string' && Array.isArray(item.messages) && typeof item.id === 'string') : [],
  }
  if (!['auto', 'openai', 'deepseek', 'codex'].includes(saved.aiProvider)) saved.aiProvider = 'auto'
  if (Array.isArray(saved.volumes) && saved.volumes.every((volume) =>
    typeof volume?.id === 'string' && typeof volume.title === 'string' && validChapters(volume.chapters)
  ) && saved.volumes.some((volume) => volume.chapters.length)) {
    return { ...defaults, ...saved }
  }
  if (validChapters(legacyChapters) && legacyChapters.length) {
    return { ...defaults, ...saved, volumes: [{
      id: defaults.volumes[0].id,
      title: typeof legacyTitle === 'string' && legacyTitle.trim() ? legacyTitle : defaults.volumes[0].title,
      chapters: legacyChapters,
    }] }
  }
  return null
}

export function loadManuscript(snapshot) {
  const defaults = starterManuscript()
  try {
    const source = snapshot === undefined ? JSON.parse(localStorage.getItem(storageKey)) : snapshot
    return normalizeManuscript(source, defaults) || defaults
  } catch {
    // A missing or invalid local copy starts with the sample manuscript.
    return defaults
  }
}

export function importManuscript(text, { preserveIdentity = false } = {}) {
  const source = JSON.parse(text.replace(/^\uFEFF/u, ''))
  if (typeof source?.title !== 'string' || !source.title.trim()) throw new Error('备份缺少书名。')
  const book = normalizeManuscript(source, starterManuscript())
  if (!book) throw new Error('备份缺少有效的卷和章节。')
  const chapters = book.volumes.flatMap((volume) => volume.chapters)
  for (const records of [book.volumes, chapters]) {
    if (records.some(({ id }) => !id.trim()) || new Set(records.map(({ id }) => id)).size !== records.length) {
      throw new Error('备份包含空白或重复的卷章编号。')
    }
  }
  const validAnchor = (anchor) => typeof anchor?.chapterId === 'string' && typeof anchor.paragraphId === 'string'
  const validRecords = (items, valid) => Array.isArray(items) && items.every((item) => typeof item?.id === 'string' && valid(item))
  const { annotations, foreshadows, events, links } = book.research
  if (!validRecords(annotations, ({ anchor }) => validAnchor(anchor)) ||
      !validRecords(foreshadows, ({ anchors }) => Array.isArray(anchors) && anchors.every((item) => validAnchor(item?.anchor))) ||
      !validRecords(events, ({ anchor }) => validAnchor(anchor)) || !validRecords(links, () => true)) {
    throw new Error('备份中的批注、伏笔或剧情资料格式有误。')
  }
  if (!chapters.some(({ id }) => id === book.activeId)) book.activeId = chapters[0].id
  const id = preserveIdentity && typeof source.id === 'string' && /^[a-z0-9-]{1,80}$/i.test(source.id)
    ? source.id : crypto.randomUUID()
  return { ...book, id, aiMode: false, aiAuto: false }
}

export function hasBrowserBackup(id) {
  try {
    return Boolean(readBrowserBook(id)) || ((!id || id === starterBookId) && Boolean(localStorage.getItem(storageKey)))
  } catch {
    return false
  }
}

export function downloadFile(title, file, extension) {
  const fileName = title.replace(/[<>:"/\\|?*\u0000-\u001f]/g, '_').trim() || '书稿'
  if (window.QingjianFiles?.postMessage) {
    const reader = new FileReader()
    reader.onload = () => window.QingjianFiles.postMessage(JSON.stringify({
      name: `${fileName}.${extension}`, base64: reader.result.split(',')[1],
    }))
    reader.readAsDataURL(file)
    return
  }
  const url = URL.createObjectURL(file)
  const link = document.createElement('a')
  link.href = url
  link.download = `${fileName}.${extension}`
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export function downloadText(title, text, extension = 'txt') {
  const body = extension === 'md' ? [text] : ['\ufeff', text]
  downloadFile(title, new Blob(body, { type: extension === 'md' ? 'text/markdown;charset=utf-8' : 'text/plain;charset=utf-8' }), extension)
}

export function downloadBookBackup(book) {
  downloadFile(`${book.title} · 全书备份`, new Blob([JSON.stringify(book, null, 2)], { type: 'application/json' }), 'json')
}
