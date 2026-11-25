import { app, BrowserWindow, dialog, Menu, nativeTheme, protocol, safeStorage, shell, Tray } from 'electron'
import { randomUUID } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { createServer } from 'node:http'
import { extname, join } from 'node:path'
import { createApiHandler } from './local-api.js'
import { desktopSync } from './desktop-sync.js'

const scheme = 'qingjian'
const studio = `${scheme}://studio`
const permittedDomains = new Set(['auth.openai.com', 'platform.openai.com', 'platform.deepseek.com', 'help.openai.com'])
const mimeTypes = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.webp': 'image/webp', '.png': 'image/png', '.woff2': 'font/woff2', '.txt': 'text/plain; charset=utf-8' }
const contentPolicy = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
let window
let apiServer
let tray
let quitting = false

function themeColors(dark, accent = '#3d62df') {
  const base = dark ? [27, 34, 48] : [255, 255, 255]
  const color = '#' + base.map((channel, index) => {
    const tone = Number.parseInt(accent.slice(1 + index * 2, 3 + index * 2), 16)
    return Math.round(channel * .95 + tone * .05).toString(16).padStart(2, '0')
  }).join('')
  return { height: 40, color, symbolColor: dark ? '#dce4f1' : '#24354e' }
}

protocol.registerSchemesAsPrivileged([{ scheme, privileges: { standard: true, secure: true, supportFetchAPI: true } }])
app.setName('Qingjian')
app.setAppUserModelId('Qingjian.WritingStudio')

function showStudio() {
  if (!window || window.isDestroyed()) return
  if (window.isMinimized()) window.restore()
  window.show()
  window.focus()
}

function setupTray(icon) {
  tray = new Tray(icon)
  tray.setToolTip('青简 · 小说写作工作台')
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: '打开青简', click: showStudio },
    { type: 'separator' },
    { label: '退出青简', click: () => app.quit() },
  ]))
  tray.on('double-click', showStudio)
}

async function startStudio() {
  const accessToken = randomUUID()
  const dataDir = join(app.getPath('userData'), 'books')
  const api = createApiHandler({ ...process.env, QINGJIAN_DATA_DIR: dataDir }, { desktop: true })
  apiServer = createServer((request, response) => {
    if (request.headers['x-qingjian-desktop'] !== accessToken) { response.writeHead(403).end(); return }
    api(request, response, () => response.writeHead(404).end()).catch(() => {
      if (!response.headersSent) response.writeHead(500)
      response.end()
    })
  })
  await new Promise((resolve, reject) => {
    apiServer.once('error', reject)
    apiServer.listen(0, '127.0.0.1', resolve)
  })
  const localApiUrl = `http://127.0.0.1:${apiServer.address().port}`
  const staticRoot = join(app.getAppPath(), 'dist')
  const icon = join(app.getAppPath(), 'server', 'qingjian.ico')
  const sync = desktopSync(app.getPath('userData'), safeStorage)

  protocol.handle(scheme, async (request) => {
    const url = new URL(request.url)
    if (url.host !== 'studio') return new Response(null, { status: 404 })
    if (url.pathname.startsWith('/api/desktop/sync/')) {
      try {
        return await sync(request, url)
      } catch {
        return Response.json({ error: '青简服务器暂时无法连接。' }, { status: 503 })
      }
    }
    if (url.pathname === '/api/session' && request.method === 'GET') {
      return Response.json({ authenticated: true, required: false, desktop: true })
    }
    if (url.pathname === '/api/desktop/theme' && request.method === 'PUT') {
      const { dark, accent } = await request.json().catch(() => ({})) || {}
      if (typeof dark !== 'boolean') return new Response(null, { status: 400 })
      window.setTitleBarOverlay(themeColors(dark, typeof accent === 'string' && /^#[\da-f]{6}$/iu.test(accent) ? accent : undefined))
      window.setBackgroundColor(dark ? '#151a24' : '#f4f7fc')
      return Response.json({ dark })
    }
    if (url.pathname.startsWith('/api/')) {
      const headers = { 'x-qingjian-desktop': accessToken }
      const contentType = request.headers.get('content-type')
      if (contentType) headers['content-type'] = contentType
      const accept = request.headers.get('accept')
      if (accept) headers.accept = accept
      try {
        return await fetch(localApiUrl + url.pathname + url.search, {
          method: request.method, body: request.body, duplex: 'half', headers, signal: request.signal,
        })
      } catch {
        return Response.json({ error: '本地书稿服务暂时不可用。' }, { status: 503 })
      }
    }
    if (request.method !== 'GET') return new Response(null, { status: 405 })
    const path = url.pathname === '/' ? 'index.html' : url.pathname.slice(1)
    if (path !== 'index.html' && !/^assets\/[\w.-]+$/u.test(path)) return new Response(null, { status: 404 })
    try {
      return new Response(await readFile(join(staticRoot, path)), { headers: {
        'Content-Type': mimeTypes[extname(path)] || 'application/octet-stream',
        'Content-Security-Policy': contentPolicy,
        'X-Content-Type-Options': 'nosniff',
      } })
    } catch {
      return new Response(null, { status: 404 })
    }
  })

  window = new BrowserWindow({
    title: '青简 · 小说写作工作台', width: 1400, height: 950, minWidth: 640, minHeight: 570,
    icon,
    titleBarStyle: 'hidden', titleBarOverlay: themeColors(nativeTheme.shouldUseDarkColors),
    backgroundColor: nativeTheme.shouldUseDarkColors ? '#151a24' : '#f4f7fc', show: false,
    webPreferences: { contextIsolation: true, sandbox: true, nodeIntegration: false },
  })
  Menu.setApplicationMenu(null)
  setupTray(icon)
  window.on('close', (event) => {
    if (quitting) return
    event.preventDefault()
    window.hide()
  })
  window.webContents.setWindowOpenHandler(({ url }) => {
    const target = new URL(url)
    if (target.protocol === 'https:' && permittedDomains.has(target.host) && !target.username && !target.password) {
      shell.openExternal(url).catch(() => {})
    }
    return { action: 'deny' }
  })
  window.webContents.on('will-navigate', (event) => event.preventDefault())
  window.webContents.session.setPermissionRequestHandler((_contents, _permission, callback) => callback(false))
  window.webContents.on('will-prevent-unload', (event) => {
    if (quitting) showStudio()
    const choice = dialog.showMessageBoxSync(window, {
      type: 'warning', message: '书稿仍在保存', detail: '可以继续写作，等保存完成后再退出。',
      buttons: ['继续写作', '退出'], defaultId: 0, cancelId: 0,
    })
    if (choice === 1) event.preventDefault()
    else quitting = false
  })
  window.once('ready-to-show', () => window.show())
  await window.loadURL(`${studio}/#shelf`)
}

if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on('second-instance', showStudio)
  app.on('before-quit', () => { quitting = true })
  app.whenReady().then(startStudio).catch((error) => {
    dialog.showErrorBox('青简启动失败', error.message)
    app.exit(1)
  })
  app.on('window-all-closed', () => app.quit())
  app.on('quit', () => {
    tray?.destroy()
    apiServer?.close()
  })
}
