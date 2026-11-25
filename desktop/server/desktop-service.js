import { createReadStream } from 'node:fs'
import { stat } from 'node:fs/promises'
import { createServer } from 'node:http'
import { extname } from 'node:path'
import { createApiHandler } from './local-api.js'

const token = process.env.QINGJIAN_DESKTOP_TOKEN
if (!token || !process.env.QINGJIAN_DATA_DIR) throw new Error('缺少桌面启动参数。')
delete process.env.QINGJIAN_DESKTOP_TOKEN

const root = new URL('../dist/', import.meta.url)
const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.webp': 'image/webp', '.png': 'image/png', '.woff2': 'font/woff2', '.txt': 'text/plain; charset=utf-8' }
const policy = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
const api = createApiHandler(process.env, { desktop: true })

async function request(req, res) {
  if (req.headers['x-qingjian-desktop'] !== token) { res.writeHead(403).end(); return }
  const { pathname } = new URL(req.url, 'http://localhost')
  if (pathname === '/api/session' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
      .end(JSON.stringify({ authenticated: true, required: false, desktop: true, webview: true }))
    return
  }
  if (pathname.startsWith('/api/')) return api(req, res, () => res.writeHead(404).end())

  const asset = pathname !== '/'
  if (req.method !== 'GET' || (asset && !/^\/assets\/[\w.-]+$/u.test(pathname))) { res.writeHead(404).end(); return }
  const file = new URL(asset ? pathname.slice(1) : 'index.html', root)
  const info = await stat(file).catch(() => null)
  if (!info?.isFile()) { res.writeHead(404).end(); return }
  res.writeHead(200, {
    'Content-Type': mime[extname(pathname)] || mime['.html'],
    'Content-Security-Policy': policy, 'X-Content-Type-Options': 'nosniff',
    'Cache-Control': asset ? 'private, max-age=31536000, immutable' : 'no-cache',
    'Content-Length': info.size,
  })
  const stream = createReadStream(file)
  stream.on('error', () => res.destroy())
  res.on('close', () => stream.destroy())
  stream.pipe(res)
}

const server = createServer((req, res) => request(req, res).catch(() => {
  if (res.headersSent) res.destroy()
  else res.writeHead(500).end()
}))
server.listen(0, '127.0.0.1', () => process.stdout.write(String(server.address().port) + '\n'))
process.stdin.resume()
process.stdin.once('end', () => process.exit(0))
