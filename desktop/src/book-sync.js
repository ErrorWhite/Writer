import { apiUrl } from './local-service.js'

const syncKey = 'qingjian.book-sync.v1'

export function bookSyncLinks() {
  try { return JSON.parse(localStorage.getItem(syncKey)) || {} } catch { return {} }
}

export function writeBookSyncLink(id, link) {
  const links = bookSyncLinks()
  if (link) links[id] = link
  else delete links[id]
  localStorage.setItem(syncKey, JSON.stringify(links))
}

export function syncSnapshot(book) {
  const { activeId, ...document } = book
  return document
}

export async function syncDigest(book) {
  function order(value) {
    if (Array.isArray(value)) return value.map(order)
    if (value && typeof value === 'object') {
      return Object.fromEntries(Object.keys(value).sort().map(key => [key, order(value[key])]))
    }
    return value
  }
  const encoded = new TextEncoder().encode(JSON.stringify(order(syncSnapshot(book))))
  const digest = await crypto.subtle.digest('SHA-256', encoded)
  return [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, '0')).join('')
}

export function bookSyncClient(desktop) {
  const base = desktop ? '/api/desktop/sync' : '/api/sync'
  async function request(path, method = 'GET', body) {
    const response = await fetch(apiUrl(base + path), {
      method, signal: AbortSignal.timeout(30000),
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      body: body ? JSON.stringify(body) : undefined,
    })
    const value = await response.json().catch(() => ({}))
    return { ...value, status: response.status, ok: response.ok }
  }
  return {
    async target() {
      if (!desktop) return document.baseURI
      const result = await request('/session')
      return result.endpoint || ''
    },
    signIn: (credentials) => request('/session', 'POST', credentials),
    signOut: () => request('/session', 'DELETE'),
    capabilities: () => request('/v1'),
    list: () => request('/v1/books'),
    read(id, since) {
      const query = new URLSearchParams({ id })
      if (since) query.set('since', since)
      return request('/v1/book?' + query)
    },
    submit(id, link, operationId, book, method = 'PUT') {
      return request('/v1/book?' + new URLSearchParams({ id }), method, {
        baseRevision: link.revision, operationId, ...(book && { book }),
      })
    },
  }
}
