import { onMounted, onUnmounted, ref, computed } from 'vue'
import { clearSnapshot, pendingSnapshots, retainSnapshot } from './book-journal.js'

const bridge = import.meta.env?.MODE === 'android' ? window.QingjianWorkbench : undefined
export const androidWorkbench = Boolean(bridge)
const pending = new Map()
if (bridge) bridge.onmessage = ({ data }) => {
  const reply = JSON.parse(data)
  pending.get(reply.id)?.(reply)
}

export function androidCall(operation, argument = {}, signal = AbortSignal.timeout(20000), onEvent) {
  return new Promise((resolve, reject) => {
    if (!bridge) { reject(new Error('无法连接 Android 写作工作区。')); return }
    if (signal.aborted) { reject(signal.reason); return }
    const id = crypto.randomUUID()
    function finish() {
      pending.delete(id)
      signal.removeEventListener('abort', abort)
    }
    function abort() {
      finish()
      try { bridge.postMessage(JSON.stringify({ id, operation: 'cancel' })) } catch { /* The view already closed. */ }
      reject(signal.reason)
    }
    signal.addEventListener('abort', abort, { once: true })
    pending.set(id, (reply) => {
      if (reply.event) { onEvent?.(reply.event, reply.text); return }
      finish()
      if (reply.error) reject(new Error(reply.error))
      else resolve(reply.result)
    })
    try { bridge.postMessage(JSON.stringify({ id, operation, argument })) }
    catch (error) { finish(); reject(error) }
  })
}

export async function openAndroidBook() {
  let session = await androidCall('book')
  const edit = (await pendingSnapshots().catch(() => [])).find(item => item.id === session.book.id)
  if (edit) {
    const result = await androidCall('save', edit)
    await clearSnapshot(edit.id, edit.version)
    session = await androidCall('book')
    session.recoveredConflict = result.conflict
  }
  return session
}

export function useAndroidBookBackup(manuscript, session) {
  const bookId = manuscript.id
  const state = ref('saved')
  const status = computed(() => ({
    pending: '正在保存…', saved: '已保存', failed: '保存失败', conflict: '已保留冲突副本',
  })[state.value])
  let revision = session.revision
  let lastSaved = JSON.stringify(manuscript)
  let staged = false
  let write
  let journalTimer
  let saveTimer
  let inFlight
  let conflicted = false
  let disposed = false

  function retain(entry) {
    return retainSnapshot(bookId, entry).catch(() => {})
  }

  function checkpoint() {
    clearTimeout(journalTimer)
    journalTimer = undefined
    if (!staged) return
    staged = false
    const snapshot = JSON.stringify(manuscript)
    if (snapshot === (write?.snapshot ?? lastSaved)) {
      if (!write) state.value = conflicted ? 'conflict' : 'saved'
      return
    }
    write = { id: bookId, version: crypto.randomUUID(), revision, snapshot }
    void retain(write)
  }

  function saveSoon() {
    if (state.value === 'conflict') return
    staged = true
    state.value = 'pending'
    journalTimer ??= setTimeout(checkpoint, 120)
    clearTimeout(saveTimer)
    saveTimer = setTimeout(flush, 550)
  }

  async function savePending() {
    while (write && !disposed) {
      const entry = write
      try {
        await retain(entry)
        const result = await androidCall('save', entry)
        if (result.conflict) conflicted = true
        else {
          revision = result.revision
          lastSaved = entry.snapshot
        }
        if (write === entry) write = undefined
        checkpoint()
        if (write && !conflicted) {
          write.revision = revision
          await retain(write)
        }
        await clearSnapshot(bookId, entry.version).catch(() => {})
        if (!write) state.value = conflicted ? 'conflict' : 'saved'
      } catch {
        state.value = 'failed'
        return false
      }
    }
    return state.value !== 'failed'
  }

  function flush() {
    clearTimeout(saveTimer)
    checkpoint()
    inFlight ??= savePending().finally(() => { inFlight = undefined })
    return inFlight
  }

  function visibility() {
    if (document.visibilityState === 'hidden') void flush()
  }
  onMounted(() => document.addEventListener('visibilitychange', visibility))
  onUnmounted(() => {
    disposed = true
    clearTimeout(saveTimer)
    checkpoint()
    document.removeEventListener('visibilitychange', visibility)
  })

  return {
    state, status, saveSoon, flush, checkpoint,
    links: {}, synchronization: new Map(), stateFor: () => state.value,
  }
}
