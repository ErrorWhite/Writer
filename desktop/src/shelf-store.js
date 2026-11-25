const shelfKey = 'qingjian.shelf.v1'
const activeBookKey = 'qingjian.active-book.v1'
const retiredBookKey = 'qingjian.deleted-books.v1'
export const legacyBookStorageKey = 'qingjian.manuscript.v1'
export const starterBookId = 'book-sample'
export const bookTitleKey = (title) => title.trim().normalize('NFC')
const bookKey = (id) => `qingjian.book.${id}`

export function bookCover(book) {
  const source = book?.cover
  return typeof source === 'string' && source.length <= 120000 && /^data:image\/(?:webp|png|jpeg);base64,[A-Za-z0-9+/]+=*$/u.test(source) ? source : ''
}

export function summarizeBook(book, updatedAt = Date.now()) {
  return {
    id: book.id,
    title: book.title,
    genre: book.aiSetup?.genre || '',
    cover: bookCover(book),
    chapters: book.volumes.reduce((sum, volume) => sum + volume.chapters.length, 0),
    updatedAt,
  }
}

export function readRetiredBooks() {
  try {
    const ids = JSON.parse(localStorage.getItem(retiredBookKey))
    return Array.isArray(ids) ? ids : []
  } catch {
    return []
  }
}

export function retireBrowserBooks(ids) {
  try {
    const retired = new Set([...readRetiredBooks(), ...ids])
    localStorage.setItem(retiredBookKey, JSON.stringify([...retired]))
    for (const id of ids) {
      localStorage.removeItem(bookKey(id))
      if (id === starterBookId) localStorage.removeItem(legacyBookStorageKey)
      if (savedBookId() === id) localStorage.removeItem(activeBookKey)
    }
    localStorage.setItem(shelfKey, JSON.stringify(readShelf()))
  } catch {
    // The server retains the deletion if browser storage is unavailable.
  }
}

export function readShelf() {
  try {
    const items = JSON.parse(localStorage.getItem(shelfKey))
    const retired = new Set(readRetiredBooks())
    return Array.isArray(items) ? items.filter(({ id }) => !retired.has(id)) : []
  } catch {
    return []
  }
}

export function savedBookId() {
  try {
    return localStorage.getItem(activeBookKey)
  } catch {
    return null
  }
}

export function rememberBook(id) {
  try {
    localStorage.setItem(activeBookKey, id)
  } catch {
    // The active book remains available in the current session.
  }
}

export function readBrowserBook(id) {
  try {
    return id && !readRetiredBooks().includes(id) ? JSON.parse(localStorage.getItem(bookKey(id))) : null
  } catch {
    return null
  }
}

export function writeBrowserBook(book, snapshot) {
  if (readRetiredBooks().includes(book.id)) return
  localStorage.setItem(bookKey(book.id), snapshot)
  registerBrowserBook(book)
}

export function registerBrowserBook(book) {
  const summary = summarizeBook(book)
  const shelf = readShelf().filter(({ id }) => book.id !== id)
  try {
    localStorage.setItem(shelfKey, JSON.stringify([summary, ...shelf]))
  } catch {
    localStorage.setItem(shelfKey, JSON.stringify([{ ...summary, cover: '' }, ...shelf]))
  }
}
