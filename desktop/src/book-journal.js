let opening
function database() {
  opening ||= new Promise((resolve, reject) => {
    if (!globalThis.indexedDB) { reject(new Error('IndexedDB unavailable')); return }
    const request = indexedDB.open('qingjian-autosave', 2)
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains('pending')) request.result.createObjectStore('pending')
      request.result.createObjectStore('books')
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
    request.onblocked = () => reject(new Error('Autosave database unavailable'))
  })
  return opening
}

async function transaction(mode, run, store = 'pending') {
  const db = await database()
  return new Promise((resolve, reject) => {
    const tx = db.transaction(store, mode)
    const read = run(tx.objectStore(store))
    tx.oncomplete = () => resolve(read?.result)
    tx.onabort = tx.onerror = () => reject(tx.error)
  })
}

export const retainSnapshot = (id, entry) => transaction('readwrite', store => store.put(entry, id))
export const rememberSyncedBook = (id, snapshot) => transaction('readwrite', store => store.put(snapshot, id), 'books')
export const forgetSyncedBook = (id) => transaction('readwrite', store => store.delete(id), 'books')
export function clearSnapshot(id, version) {
  return transaction('readwrite', store => {
    const request = store.get(id)
    request.onsuccess = () => { if (!version || request.result?.version === version) store.delete(id) }
  })
}

export async function recoverSnapshot(id) {
  if (!id) return null
  try {
    const pending = await transaction('readonly', store => store.get(id))
    const snapshot = pending?.snapshot || await transaction('readonly', store => store.get(id), 'books')
    return snapshot ? JSON.parse(snapshot) : null
  } catch { return null }
}
