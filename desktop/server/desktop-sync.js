import { readFile, rename, rm, writeFile } from 'node:fs/promises'
import { join } from 'node:path'

export function desktopSync(directory, safeStorage) {
  const file = join(directory, 'sync-session.json')
  const json = (value, status = 200) => Response.json(value, { status, headers: { 'Cache-Control': 'no-store' } })

  async function session() {
    try {
      const { endpoint, secret } = JSON.parse(await readFile(file, 'utf8'))
      return { endpoint, cookie: safeStorage.decryptString(Buffer.from(secret, 'base64')) }
    } catch {
      return null
    }
  }

  return async (request, url) => {
    const statusRoute = url.pathname === '/api/desktop/sync/session'
    if (statusRoute && request.method === 'POST') {
      const { endpoint, username, password } = await request.json()
      const target = new URL(endpoint)
      if (target.protocol !== 'https:' || target.username || target.password || target.search || target.hash) {
        return json({ error: '请输入青简服务器的 HTTPS 地址。' }, 400)
      }
      if (!safeStorage.isEncryptionAvailable()) return json({ error: '系统暂时无法保存会话。' }, 503)
      target.pathname = target.pathname.replace(/\/+$/, '') + '/'
      const response = await fetch(new URL('api/session', target), {
        method: 'POST', redirect: 'error', signal: AbortSignal.timeout(12000),
        headers: { Origin: target.origin, 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      })
      if (!response.ok) return json({ error: '服务器登录失败。' }, response.status)
      const header = response.headers.get('set-cookie') || ''
      const cookie = /^qingjian_novel_session=[a-f0-9]{64}(?=;)/.exec(header)?.[0]
      if (!cookie || !/;\s*Secure\b/i.test(header) || !/;\s*HttpOnly\b/i.test(header)) {
        return json({ error: '服务器没有返回有效会话。' }, 502)
      }
      const secret = safeStorage.encryptString(cookie).toString('base64')
      const temporary = file + '.tmp'
      await writeFile(temporary, JSON.stringify({ endpoint: target.href, secret }), { mode: 0o600, flush: true })
      await rename(temporary, file)
      return json({ endpoint: target.href })
    }
    const current = await session()
    if (statusRoute && request.method === 'GET') return json({ endpoint: current?.endpoint || '' })
    if (statusRoute && request.method === 'DELETE') {
      await rm(file, { force: true })
      if (current) {
        const endpoint = new URL(current.endpoint)
        await fetch(new URL('api/session', endpoint), {
          method: 'DELETE', redirect: 'error', signal: AbortSignal.timeout(5000),
          headers: { Origin: endpoint.origin, Cookie: current.cookie },
        }).catch(() => {})
      }
      return json({})
    }
    const resource = url.pathname.slice('/api/desktop/sync/'.length)
    const permitted = resource === 'v1/book' ? ['GET', 'PUT', 'DELETE'] : ['GET']
    if (!['v1', 'v1/book', 'v1/books'].includes(resource) || !permitted.includes(request.method)) {
      return json({ error: '未知同步操作。' }, 404)
    }
    if (!current) return json({ error: '请先连接青简服务器。' }, 401)
    const endpoint = new URL(current.endpoint)
    const server = await fetch(new URL('api/sync/' + resource + url.search, endpoint), {
      method: request.method, redirect: 'error', signal: AbortSignal.timeout(30000),
      headers: { Origin: endpoint.origin, Cookie: current.cookie, 'Content-Type': 'application/json' },
      body: request.method === 'GET' ? undefined : request.body,
      duplex: 'half',
    })
    return new Response(server.body, { status: server.status, headers: { 'Content-Type': 'application/json' } })
  }
}
