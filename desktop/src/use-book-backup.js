import { computed, onMounted, onUnmounted, reactive } from 'vue'
import { readBrowserBook, readRetiredBooks, registerBrowserBook, retireBrowserBooks, writeBrowserBook } from './shelf-store.js'
import { apiUrl } from './local-service.js'
import { clearSnapshot, forgetSyncedBook, pendingSnapshots, recoverSnapshot, rememberSyncedBook, retainSnapshot } from './book-journal.js'
import { bookSyncClient, bookSyncLinks, syncDigest, writeBookSyncLink } from './book-sync.js'

export function useBookBackup(manuscript, { desktop, normalize, applyRemote, syncChanged }) {
  const sync = bookSyncClient(desktop)
  const links = reactive(bookSyncLinks())
  const synchronization = reactive(new Map())
  const states = reactive(new Map())
  const state = computed(() => states.get(manuscript.id) || 'pending')
  const status = computed(() => ({
    pending: '正在保存…',
    saved: '已保存',
    local: '仅本页备份',
    server: '仅服务端备份',
    failed: '保存失败',
    deleted: '书籍已删除',
  })[state.value])
  const pending = new Map()
  const staged = new Map()
  const editSerial = new Map()
  let timer
  let browserTimer
  let inFlight
  let retry
  let pollTimer
  let polling = false
  let retries = 0
  let stopped = false

  function setLink(id, link) {
    writeBookSyncLink(id, link)
    if (link) links[id] = link
    else delete links[id]
    synchronization.set(id, link?.paused ? '已暂停同步' : link ? '已同步' : '未开启同步')
  }

  async function localBook(id) {
    return staged.get(id) || (pending.has(id) ? JSON.parse(pending.get(id).snapshot) : await recoverSnapshot(id) || readBrowserBook(id))
  }

  async function persistSynced(book, snapshot) {
    await rememberSyncedBook(book.id, snapshot)
    try {
      writeBrowserBook(book, snapshot)
    } catch {
      registerBrowserBook(book)
    }
  }

  async function preserveConflict(id, book, revision) {
    const digest = await syncDigest(book)
    const copyId = 'conflict-' + (await syncDigest({ id, digest, revision })).slice(0, 48)
    if (await localBook(copyId)) return
    const copy = { ...book, id: copyId, title: book.title + '（同步冲突）' }
    const snapshot = JSON.stringify(copy)
    await persistSynced(copy, snapshot)
    syncChanged('同步冲突已保留为本地副本。')
  }

  async function adopt(id, result, link, preserve) {
    const serial = editSerial.get(id)
    const value = await localBook(id)
    const local = value && JSON.parse(JSON.stringify(value))
    if (preserve && local) {
      await preserveConflict(id, local, result.revision || 'deleted')
    }
    if (result.status === 410) {
      if (editSerial.get(id) !== serial) return false
      setLink(id, null)
      applyRemote(id, null)
      return true
    }
    if (!result.ok || !result.book) throw new Error('无法读取服务器版本。')
    const book = normalize({ ...result.book, activeId: local?.activeId || result.book.activeId })
    const digest = await syncDigest(book)
    const snapshot = JSON.stringify(book)
    if (desktop) await saveLegacy(snapshot)
    if (editSerial.get(id) !== serial) return false
    await persistSynced(book, snapshot)
    if (editSerial.get(id) !== serial) return false
    const stagedWrite = pending.get(id)
    staged.delete(id)
    pending.delete(id)
    if (stagedWrite) clearSnapshot(id, stagedWrite.version).catch(() => {})
    setLink(id, { ...link, revision: result.revision, digest })
    applyRemote(id, book)
    return true
  }

  async function enableSync(id) {
    await flush()
    const target = await sync.target()
    const capabilities = await sync.capabilities()
    if (!target || capabilities.version !== 1) throw new Error('连接的青简服务器尚未提供书稿同步。')
    const existing = links[id]
    if (existing?.paused && existing.target === target) {
      setLink(id, { ...existing, paused: false })
      const local = await localBook(id)
      if (local) saveSoon(local)
      await flush()
      await pollSync()
      return
    }
    const result = await sync.read(id)
    const link = { target, revision: null, digest: '' }
    if (result.status === 404) {
      setLink(id, link)
      const local = await localBook(id)
      if (local) saveSoon(local)
      await flush()
    } else {
      const local = await localBook(id)
      const different = result.book && local && await syncDigest(normalize(result.book)) !== await syncDigest(local)
      if (!await adopt(id, result, link, Boolean(different || result.status === 410))) {
        throw new Error('书稿仍在写入，请稍后开启同步。')
      }
      if (result.ok && !result.managed) {
        setLink(id, { ...links[id], digest: '' })
        const local = await localBook(id)
        if (local) saveSoon(local)
        await flush()
      }
    }
  }

  async function saveLegacy(snapshot) {
    const response = await fetch(apiUrl('/api/book'), {
      method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: snapshot,
      signal: AbortSignal.timeout(12000),
    })
    return { ...await response.json(), status: response.status, ok: response.ok }
  }

  async function sendSync(id, write, link) {
    if (await sync.target() !== link.target) throw new Error('请连接此书稿绑定的青简服务器。')
    const book = JSON.parse(write.snapshot)
    const digest = await syncDigest(book)
    if (digest === link.digest) return true
    const result = await sync.submit(id, link, write.version, book)
    if (result.ok) {
      await rememberSyncedBook(id, write.snapshot)
      setLink(id, { ...link, revision: result.revision, digest })
      return true
    }
    if (result.status === 412 || result.status === 410) {
      const current = result.status === 410 ? result : await sync.read(id)
      const preserve = !current.book || await syncDigest(normalize(current.book)) !== digest
      if (!await adopt(id, current, link, preserve)) throw new Error('书稿仍在编辑。')
      return true
    }
    throw new Error('书稿同步未完成。')
  }

  async function pollSync() {
    if (polling || inFlight) return
    polling = true
    try {
      const target = await sync.target()
      for (const [id, link] of Object.entries(links)) {
        if (inFlight) break
        if (link.paused || link.target !== target || staged.has(id) || pending.has(id)) continue
        try {
          const result = await sync.read(id, link.revision)
          if (inFlight || staged.has(id) || pending.has(id) || links[id] !== link) continue
          if (result.unchanged) {
            synchronization.set(id, '已同步')
            continue
          }
          if (result.status === 410 || result.ok) await adopt(id, result, link, false)
          else synchronization.set(id, '等待连接')
        } catch {
          synchronization.set(id, '等待连接')
        }
      }
    } catch {
      for (const [id, link] of Object.entries(links)) if (!link.paused) synchronization.set(id, '等待连接')
    } finally {
      polling = false
    }
  }

  async function deleteSynced(id) {
    const link = links[id]
    if (!link) return false
    await flush()
    if (await sync.target() !== link.target) throw new Error('请连接此书稿绑定的青简服务器。')
    const result = await sync.submit(id, links[id], crypto.randomUUID(), null, 'DELETE')
    if (!result.ok && result.status !== 410) throw new Error('书稿已在其他设备修改，请同步后重试。')
    setLink(id, null)
    return true
  }

  function saveSoon(book = manuscript) {
    const id = book.id
    if (readRetiredBooks().includes(id)) return
    editSerial.set(id, (editSerial.get(id) || 0) + 1)
    staged.set(id, book)
    states.set(id, 'pending')
    if (!browserTimer) browserTimer = setTimeout(checkpoint, 120)
    clearTimeout(timer)
    timer = setTimeout(flush, 550)
  }

  function checkpoint() {
    clearTimeout(browserTimer)
    browserTimer = undefined
    for (const [id, book] of staged) {
      staged.delete(id)
      if (book.id !== id || readRetiredBooks().includes(id)) continue
      let snapshot
      try {
        snapshot = JSON.stringify(book)
      } catch {
        states.set(id, 'failed')
        continue
      }
      let browserSaved = true
      try {
        writeBrowserBook(book, snapshot)
      } catch {
        browserSaved = false
      }
      const version = crypto.randomUUID()
      const entry = { snapshot, browserSaved, version }
      pending.set(id, entry)
      retainSnapshot(id, { id, snapshot, version }).then(() => {
        entry.browserSaved = true
        if (pending.get(id) === entry && states.get(id) === 'failed') states.set(id, 'local')
      }).catch(() => {})
    }
  }

  async function sendPending() {
    const attempted = new Set()
    while ([...pending.values()].some((write) => !attempted.has(write))) {
      for (const [id, write] of pending) {
        if (attempted.has(write)) continue
        attempted.add(write)
        try {
          const link = links[id]
          const result = !link || desktop ? await saveLegacy(write.snapshot) : { ok: true, saved: true }
          if (result.status === 410 && result.code === 'book_deleted' && !link) {
            retireBrowserBooks([id])
            discard(id)
            continue
          }
          if (!result.ok || !result.saved) throw new Error('服务器未确认保存。')
          if (link?.paused) {
            await rememberSyncedBook(id, write.snapshot)
          } else if (link) {
            synchronization.set(id, '正在同步')
            await sendSync(id, write, link)
          }
          if (pending.get(id) === write) {
            pending.delete(id)
            clearSnapshot(id, write.version).catch(() => {})
            if (!staged.has(id)) states.set(id, write.browserSaved ? 'saved' : 'server')
          }
        } catch {
          if (links[id]) synchronization.set(id, '待同步')
          if (pending.get(id) === write && !staged.has(id)) states.set(id, write.browserSaved ? 'local' : 'failed')
        }
      }
    }
  }

  function discard(id) {
    staged.delete(id)
    pending.delete(id)
    clearSnapshot(id).catch(() => {})
    forgetSyncedBook(id).catch(() => {})
    states.set(id, 'deleted')
  }

  function flush() {
    clearTimeout(timer)
    clearTimeout(retry)
    checkpoint()
    if (!inFlight) inFlight = sendPending().finally(() => {
      inFlight = null
      if (pending.size && !stopped) retry = setTimeout(flush, Math.min(45000, 1500 * 2 ** Math.min(retries++, 5)))
      else retries = 0
    })
    return inFlight
  }

  function beforeUnload(event) {
    checkpoint()
    if (!pending.size && ![...states.values()].includes('failed')) return
    event.preventDefault()
    event.returnValue = ''
  }

  function visibility() { if (document.visibilityState === 'hidden') { checkpoint(); void flush() } }
  async function recoverPending() {
    for (const entry of await pendingSnapshots().catch(() => [])) {
      const id = entry.id || JSON.parse(entry.snapshot).id
      if (readRetiredBooks().includes(id) || staged.has(id) || pending.has(id)) continue
      pending.set(id, { ...entry, browserSaved: true })
    }
    await flush()
    await pollSync()
  }
  onMounted(() => {
    window.addEventListener('beforeunload', beforeUnload)
    window.addEventListener('online', flush)
    document.addEventListener('visibilitychange', visibility)
    pollTimer = setInterval(pollSync, 15000)
    recoverPending().catch(() => {})
  })
  onUnmounted(() => {
    stopped = true
    clearTimeout(timer)
    clearTimeout(retry)
    clearInterval(pollTimer)
    checkpoint()
    window.removeEventListener('beforeunload', beforeUnload)
    window.removeEventListener('online', flush)
    document.removeEventListener('visibilitychange', visibility)
  })
  return { state, status, saveSoon, flush, checkpoint, discard, stateFor: (id) => states.get(id),
    links, synchronization, sync, enableSync, deleteSynced, pollSync,
    disableSync: id => { if (links[id]) setLink(id, { ...links[id], paused: true }) } }
}
