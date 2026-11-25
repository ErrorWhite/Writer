import { createReadStream } from 'node:fs'
import { stat } from 'node:fs/promises'
import { createServer } from 'node:http'
import { extname } from 'node:path'
import { createApiHandler } from './local-api.js'
import { createWebSession } from './web-session.js'

const session = createWebSession({
  origin: process.env.QINGJIAN_HTTPS_ORIGIN,
  path: process.env.QINGJIAN_URL_PATH || '/novel/',
  authorizationHash: process.env.QINGJIAN_AUTHORIZATION_SHA256,
  dataDirectory: process.env.QINGJIAN_DATA_DIR,
})
const staticRoot = new URL('../dist/', import.meta.url)
const mime = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.webp': 'image/webp',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain; charset=utf-8',
}

const handleApi = createApiHandler(process.env, {
  isAuthenticatedSiteRequest: (request) => request.headers.origin === session.origin && session.isAuthorized(request),
})

async function serveStatic(request, response) {
  const { pathname } = new URL(request.url, 'http://localhost')
  if (!['GET', 'HEAD'].includes(request.method) || (pathname !== '/' && !/^\/assets\/[a-z0-9_.-]+$/i.test(pathname))) {
    response.writeHead(404).end()
    return
  }
  const asset = pathname !== '/'
  const file = new URL(asset ? pathname.slice(1) : 'index.html', staticRoot)
  const info = await stat(file).catch(() => null)
  if (!info?.isFile()) {
    response.writeHead(404).end()
    return
  }
  response.writeHead(200, {
    'Content-Type': mime[extname(asset ? pathname : 'index.html')] || 'application/octet-stream',
    'Content-Length': info.size,
    'Cache-Control': asset ? 'private, max-age=31536000, immutable' : 'private, no-cache',
    'X-Content-Type-Options': 'nosniff',
  })
  if (request.method === 'HEAD') response.end()
  else {
    const stream = createReadStream(file)
    stream.on('error', () => response.destroy())
    response.on('close', () => stream.destroy())
    stream.pipe(response)
  }
}

async function dispatch(request, response) {
  const { pathname } = new URL(request.url, 'http://localhost')
  if (pathname === '/api/session') return session.handle(request, response)
  if (pathname === '/api/session/password') return session.changePassword(request, response)
  if (!pathname.startsWith('/api/')) return serveStatic(request, response)
  if (!session.isAuthorized(request)) {
    response.writeHead(401, { 'Cache-Control': 'no-store', 'Content-Type': 'application/json; charset=utf-8' })
      .end(JSON.stringify({ error: '请先登录。' }))
    return
  }
  return handleApi(request, response, () => response.writeHead(404).end())
}

const server = createServer((request, response) => {
  dispatch(request, response).catch(() => {
    if (response.headersSent) response.destroy()
    else response.writeHead(500).end()
  })
})
const port = Number(process.env.QINGJIAN_PORT || 4173)
server.listen(port, '127.0.0.1', () => console.log(`Qingjian listening on 127.0.0.1:${server.address().port}`))
