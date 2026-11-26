import { createHash, randomUUID } from 'node:crypto'
import { mkdir, readFile, readdir, rename, rm, writeFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { createAiPool } from './ai-accounts.js'
import { createCodexSession } from './codex-session.js'
import { bookCover, bookTitleKey, starterBookId, summarizeBook } from '../src/shelf-store.js'
import { bookGitHistory } from './git-history.js'
import { validateCreativeResult } from '../src/creative-tools.js'
import { prepareRewrite, structuredIntents, taskInstructions } from '../src/rewrite-request.js'
import { runSkillScript, skillFeedback } from './skill-runtime.js'

const validBookId = (id) => typeof id === 'string' && /^[a-z0-9-]{1,80}$/i.test(id)
const sha256 = (value) => createHash('sha256').update(value).digest('hex')
const validRevision = (revision) => typeof revision === 'string' && /^[a-f0-9]{64}$/.test(revision)
function reply(response, status, value) {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
  response.end(JSON.stringify(value))
}

async function readBody(request) {
  if (!request.headers['content-type']?.startsWith('application/json')) throw new Error('请求需要 JSON 数据。')
  const chunks = []
  let size = 0
  for await (const chunk of request) {
    size += chunk.length
    if (size > 32 * 1024 * 1024) throw new Error('请求内容超过接口大小上限。')
    chunks.push(chunk)
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'))
}

export function createApiHandler(env, { isAuthenticatedSiteRequest = () => false, desktop = false } = {}) {
  const data = resolve(env.QINGJIAN_DATA_DIR || '.qingjian')
  const preferencesPath = join(data, 'preferences.json')
  const defaultPreferences = { provider: 'auto', accent: '#3d62df', shelfLayout: 'cards', images: false }
  const preferences = async () => ({ ...defaultPreferences, ...await readJson(preferencesPath, {}) })
  const legacyBookPath = join(data, 'book.json')
  const shelfPath = join(data, 'shelf.json')
  const bookPath = (id) => join(data, `book-${id}.json`)
  const syncPath = (id) => join(data, `sync-v1-${id}.json`)
  const versionsPath = (id) => join(data, `book-${id}-versions.json`)
  const versionPath = (id, version) => join(data, `book-${id}-version-${version}.json`)
  const chapterVersionsDirectory = (id) => join(data, `book-${id}-chapter-versions`)
  const historyDirectory = (id) => join(data, `book-${id}-git`)
  const chapterVersionPath = (id, chapter) => join(chapterVersionsDirectory(id), createHash('sha256').update(chapter).digest('hex') + '.json')
  let pendingWrite = Promise.resolve()
  let syncIndex
  const ai = createAiPool(data, env)
  const codex = createCodexSession(env, data)

  async function aiStatus(check = false, deferCli = false) {
    const [apis, cli] = await Promise.all([ai.status(), deferCli ? null : codex.status(check)])
    const connected = Boolean(cli?.connected && cli.enabled)
    return { ...apis, cli, codex: connected, configured: apis.configured || connected }
  }

  async function rewrite({ provider, material, intent = 'paragraph', scripts, contextBudget }, signal, events) {
    provider ||= (await preferences()).provider
    const { instructions, promptMaterial } = prepareRewrite(intent, material)
    if (Array.isArray(scripts) && scripts.length && ['paragraph', 'paragraph-polish', 'batch', 'chapter-rewrite', 'chapter'].includes(intent)) {
      events?.('stage', '运行写作技能')
      const feedback = await skillFeedback(scripts, material, intent, signal)
      const limit = Math.max(0, Math.min(100000, Number(contextBudget) || 12000) - JSON.stringify(promptMaterial).length - 14)
      let remaining = limit
      const fitted = feedback.filter(item => {
        const size = JSON.stringify(item).length + 1
        if (size > remaining) return false
        remaining -= size
        return true
      })
      if (fitted.length) promptMaterial.技能工具反馈 = fitted
      if (remaining === limit && feedback.length) events?.('stage', '上下文预算不足，已省略技能工具反馈')
    }
    const prompt = JSON.stringify(promptMaterial)
    const outputTokens = intent === 'roleplay' ? 900 : ['chapter', 'chapter-rewrite'].includes(intent)
      ? Math.min(24000, Math.max(900, Math.ceil((Number(material.作品设定?.单章目标字数) || 1500) * 1.8 + 250)))
      : intent === 'batch' ? Math.min(16000, 650 + material.审阅原文.reduce((size, part) => size + part.text.length * 2, 0))
      : ['outline', 'settings'].includes(intent) ? 4000
      : intent === 'chapter-close' ? 3600 : intent === 'profile-suggest' ? 1450 : intent === 'paragraph-polish' ? 1500 : structuredIntents.has(intent) ? 2200 : intent === 'context' ? 1000 : intent === 'summary' || intent === 'style' ? 720
        : Math.min(1700, Math.max(280, Math.ceil((material.目标段落?.length || 150) * 1.8 + 220)))
    const timeout = ['chapter', 'chapter-rewrite', 'batch'].includes(intent) ? 180000 : 90000
    const parameters = { provider, instructions, prompt, outputTokens, timeout, signal,
      events: events && ((type, value) => { if (type !== 'delta' || !structuredIntents.has(intent)) events(type, value) }) }
    const useCodex = provider === 'codex' || ((!provider || provider === 'auto') && !(await ai.status()).configured)
    const result = await (useCodex ? codex.generate(parameters) : ai.generate(parameters))
    if (!structuredIntents.has(intent)) return result
    const { text, ...details } = result
    return { ...details, data: validateCreativeResult(intent, text, material) }
  }

  const usagePath = join(data, 'ai-usage.json')
  function trackUsage(intent, success, usage) {
    return queueWrite(async () => {
      const history = await readJson(usagePath, {})
      const now = new Date()
      const date = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
      const limits = history[date] || { requests: 0, successful: 0, input: 0, output: 0, tasks: {} }
      limits.requests++
      if (success) limits.successful++
      limits.input += Number(usage?.input) || 0
      limits.output += Number(usage?.output) || 0
      if (Object.hasOwn(taskInstructions, intent) || intent === 'image') limits.tasks[intent] = (limits.tasks[intent] || 0) + 1
      history[date] = limits
      await atomicWrite(usagePath, JSON.stringify(Object.fromEntries(Object.entries(history).sort().slice(-400))))
    }).catch(() => {})
  }

  async function readJson(path, fallback) {
    try {
      return JSON.parse(await readFile(path, 'utf8'))
    } catch (error) {
      if (error.code === 'ENOENT') return fallback
      throw error
    }
  }

  async function atomicWrite(path, content) {
    const temporary = join(data, `write-${randomUUID()}.tmp`)
    try {
      await writeFile(temporary, content, { flag: 'wx', flush: true })
      await rename(temporary, path)
    } finally {
      await rm(temporary, { force: true })
    }
  }

  async function readLegacyShelf() {
    const saved = await readJson(shelfPath, [])
    return Array.isArray(saved) ? { books: saved, deleted: [] } : saved
  }

  async function readSyncIndex() {
    if (!syncIndex) {
      syncIndex = (async () => {
        const names = await readdir(data).catch((error) => {
          if (error.code === 'ENOENT') return []
          throw error
        })
        const entries = new Map()
        for (const name of names) {
          const id = /^sync-v1-([a-z0-9-]{1,80})\.json$/i.exec(name)?.[1]
          if (id) {
            const record = await readJson(syncPath(id), null)
            if (record) entries.set(id, record.summary)
          }
        }
        return entries
      })().catch((error) => {
        syncIndex = undefined
        throw error
      })
    }
    return syncIndex
  }

  async function readShelf() {
    const shelf = await readLegacyShelf()
    const synced = await readSyncIndex()
    return {
      books: [...shelf.books.filter(({ id }) => !synced.has(id)), ...[...synced.values()].filter(Boolean)]
        .sort((left, right) => right.updatedAt - left.updatedAt),
      deleted: [...new Set([...shelf.deleted, ...[...synced].filter(([, book]) => !book).map(([id]) => id)])],
    }
  }

  function queueWrite(action) {
    const write = pendingWrite.then(async () => {
      await mkdir(data, { recursive: true })
      return action()
    })
    pendingWrite = write.catch(() => {})
    return write
  }

  async function readLegacyBook(id) {
    return await readJson(bookPath(id), null) || (id === starterBookId ? await readJson(legacyBookPath, null) : null)
  }

  async function readBook(id) {
    const record = await readJson(syncPath(id), null)
    return record ? record.book : readLegacyBook(id)
  }

  function checkedBook(book, id) {
    if (!book || typeof book.title !== 'string' || !Array.isArray(book.volumes)) throw new Error('书稿格式不正确。')
    if (!validBookId(id) || !book.volumes.every((volume) => Array.isArray(volume?.chapters))) throw new Error('书稿格式不正确。')
    if (book.cover && !bookCover(book)) throw new Error('封面格式不正确。')
    return { ...book, id }
  }

  async function bookSyncState(id) {
    const record = await readJson(syncPath(id), null)
    if (record) return record
    if ((await readLegacyShelf()).deleted.includes(id)) return { revision: null, book: null, deleted: true }
    const book = await readLegacyBook(id)
    return { revision: book ? sha256(JSON.stringify(book)) : null, book, deleted: false }
  }

  function updateSyncedBook(id, { baseRevision, operationId, book, deleted = false }) {
    if (baseRevision !== null && !validRevision(baseRevision)) throw new Error('同步版本无效。')
    if (!validBookId(operationId)) throw new Error('同步提交编号无效。')
    if (!deleted && book?.id !== id) throw new Error('同步书稿编号不一致。')
    const next = deleted ? null : checkedBook(book, id)
    const operationHash = sha256(JSON.stringify([baseRevision, next, deleted]))
    return queueWrite(async () => {
      const current = await bookSyncState(id)
      if (current.operationId === operationId && current.operationHash === operationHash) {
        return { revision: current.revision, deleted: current.deleted }
      }
      if (current.deleted) return { status: 410, code: 'book_deleted' }
      if (current.revision !== baseRevision || current.operationId === operationId) {
        return { status: 412, code: 'stale_revision', revision: current.revision }
      }
      if (deleted && !current.book) return { status: 404, code: 'book_missing' }
      const index = await readSyncIndex()
      const revision = sha256(JSON.stringify([baseRevision, operationId, operationHash]))
      const summary = next ? summarizeBook(next) : null
      await atomicWrite(syncPath(id), JSON.stringify({
        revision, operationId, operationHash, book: next, deleted, summary,
      }))
      index.set(id, summary)
      return { revision, deleted }
    })
  }

  function saveBook(book, { replace = false } = {}) {
    const id = book?.id || starterBookId
    const content = JSON.stringify(checkedBook(book, id))
    return queueWrite(async () => {
      if (await readJson(syncPath(id), null)) return 'sync_required'
      const shelf = await readLegacyShelf()
      if (shelf.deleted.includes(id)) return false
      if (replace) {
        const previous = await readBook(id)
        if (!previous || bookTitleKey(previous.title) !== bookTitleKey(book.title)) return false
        await writeVersion(id, { ...previous, id })
      }
      await atomicWrite(bookPath(id), content)
      shelf.books = [summarizeBook({ ...book, id }), ...shelf.books.filter((item) => item.id !== id)]
      await atomicWrite(shelfPath, JSON.stringify(shelf))
      return true
    })
  }

  function deleteBook(id) {
    return queueWrite(async () => {
      if (await readJson(syncPath(id), null)) return false
      const shelf = await readLegacyShelf()
      if (!shelf.deleted.includes(id)) {
        shelf.deleted.push(id)
        shelf.books = shelf.books.filter((book) => book.id !== id)
        await atomicWrite(shelfPath, JSON.stringify(shelf))
      }
      await rm(bookPath(id), { force: true })
      const versions = await readJson(versionsPath(id), [])
      await Promise.all(versions.filter(({ id: version }) => validBookId(version)).map(({ id: version }) => rm(versionPath(id, version), { force: true })))
      await rm(versionsPath(id), { force: true })
      await rm(chapterVersionsDirectory(id), { recursive: true, force: true })
      await rm(historyDirectory(id), { recursive: true, force: true })
      if (id === starterBookId) await rm(legacyBookPath, { force: true })
      return true
    })
  }

  function createVersion(id, book) {
    if (!book || book.id !== id || typeof book.title !== 'string' || !Array.isArray(book.volumes) ||
        !book.volumes.some((volume) => Array.isArray(volume?.chapters) && volume.chapters.length)) {
      throw new Error('无法备份这份书稿。')
    }
    return queueWrite(async () => {
      if ((await readShelf()).deleted.includes(id)) throw new Error('这本书已删除。')
      return writeVersion(id, book)
    })
  }

  async function writeVersion(id, book) {
    const earlier = await readJson(versionsPath(id), [])
    const version = { id: randomUUID(), at: Date.now(), title: book.title }
    const versions = [version, ...earlier].slice(0, 12)
    await atomicWrite(versionPath(id, version.id), JSON.stringify(book))
    await atomicWrite(versionsPath(id), JSON.stringify(versions))
    for (const old of earlier.slice(11)) {
      if (validBookId(old.id)) await rm(versionPath(id, old.id), { force: true })
    }
    return versions
  }

  function createChapterVersion(id, chapter, snapshot) {
    if (snapshot?.id !== chapter || typeof snapshot.title !== 'string' || typeof snapshot.text !== 'string') throw new Error('章节版本格式不正确。')
    return queueWrite(async () => {
      if ((await readShelf()).deleted.includes(id)) throw new Error('这本书已删除。')
      const book = await readBook(id)
      if (!book?.volumes.some(volume => volume.chapters.some(entry => entry.id === chapter))) throw new Error('找不到该章节，请先保存书稿。')
      const path = chapterVersionPath(id, chapter)
      const versions = await readJson(path, [])
      const entry = { id: randomUUID(), at: Date.now(), title: snapshot.title, snapshot }
      const next = [entry, ...versions].slice(0, 12)
      await mkdir(chapterVersionsDirectory(id), { recursive: true })
      await atomicWrite(path, JSON.stringify(next))
      return next.map(({ snapshot: _snapshot, ...version }) => version)
    })
  }

  async function middleware(request, response, next) {
    const url = new URL(request.url, 'http://localhost')
    if (!url.pathname.startsWith('/api/')) return next()
    const host = request.headers.host
    const origin = request.headers.origin
    const loopback = /^(localhost|127\.0\.0\.1|\[::1\])(?::\d+)?$/i.test(host || '')
    if (!loopback || (origin && origin !== `http://${host}` && origin !== `https://${host}` && !isAuthenticatedSiteRequest(request))) {
      return reply(response, 403, { error: '仅当前本地工作台可访问此接口。' })
    }
    try {
      const route = `${request.method} ${url.pathname}`
      if (!desktop && route === 'GET /api/sync/v1') return reply(response, 200, { version: 1 })
      if (!desktop && route === 'GET /api/sync/v1/books') {
        const { books } = await readShelf()
        return reply(response, 200, { books: books.map(({ id, title, chapters }) => ({ id, title, chapters })) })
      }
      if (!desktop && url.pathname === '/api/sync/v1/book' && ['GET', 'PUT', 'DELETE'].includes(request.method)) {
        const id = url.searchParams.get('id')
        if (!validBookId(id)) return reply(response, 400, { error: '书稿编号无效。' })
        if (request.method === 'GET') {
          const { revision, book, deleted, operationId } = await bookSyncState(id)
          if (deleted) return reply(response, 410, { code: 'book_deleted' })
          if (!book) return reply(response, 404, { code: 'book_missing' })
          if (url.searchParams.get('since') === revision) return reply(response, 200, { revision, unchanged: true })
          return reply(response, 200, { revision, book, managed: Boolean(operationId) })
        }
        const result = await updateSyncedBook(id, {
          ...await readBody(request), deleted: request.method === 'DELETE',
        })
        return reply(response, result.status || 200, result)
      }
      if (route === 'GET /api/session') return reply(response, 200, { authenticated: true, required: false })
      if (route === 'GET /api/preferences') return reply(response, 200, await preferences())
      if (route === 'PUT /api/preferences') {
        const patch = await readBody(request)
        const allowed = {
          provider: value => ['auto', 'openai', 'deepseek', 'codex'].includes(value),
          accent: value => typeof value === 'string' && /^#[\da-f]{6}$/iu.test(value),
          shelfLayout: value => ['cards', 'rows'].includes(value),
          images: value => typeof value === 'boolean',
        }
        const entries = Object.entries(patch || {})
        if (!entries.length || entries.some(([key, value]) => !Object.hasOwn(allowed, key) || !allowed[key](value))) {
          return reply(response, 400, { error: '全局设置内容无效。' })
        }
        const saved = await queueWrite(async () => {
          const next = { ...await preferences(), ...Object.fromEntries(entries) }
          await atomicWrite(preferencesPath, JSON.stringify(next))
          return next
        })
        return reply(response, 200, saved)
      }
      if (route === 'GET /api/books') {
        const shelf = await readShelf()
        return reply(response, 200, url.searchParams.get('include') === 'deleted' ? shelf : shelf.books)
      }
      if (route === 'GET /api/book') {
        const id = url.searchParams.get('id') || starterBookId
        if (!validBookId(id)) return reply(response, 400, { error: '书稿编号无效。' })
        if ((await readShelf()).deleted.includes(id)) return reply(response, 410, { code: 'book_deleted', error: '书籍已删除。' })
        const saved = await readBook(id)
        return saved ? reply(response, 200, saved) : reply(response, 404, { error: '尚无服务端备份。' })
      }
      if (route === 'PUT /api/book') {
        const saved = await saveBook(await readBody(request))
        if (saved === 'sync_required') return reply(response, 428, { code: saved, error: '请用新版青简同步书稿。' })
        if (!saved) return reply(response, 410, { code: 'book_deleted', error: '书籍已删除。' })
        return reply(response, 200, { saved: true })
      }
      if (route === 'POST /api/book/import') {
        const { book, target = '' } = await readBody(request)
        if (target && !validBookId(target)) return reply(response, 400, { error: '请选择要覆盖的书。' })
        const id = target || randomUUID()
        const saved = await saveBook({ ...book, id }, { replace: Boolean(target) })
        if (saved === 'sync_required') return reply(response, 428, { code: saved, error: '请在同步书稿中采用导入稿。' })
        if (!saved) {
          return reply(response, 409, { error: '目标书籍已更名或删除，请重新选择。' })
        }
        return reply(response, 200, { id })
      }
      if (route === 'DELETE /api/book') {
        const id = url.searchParams.get('id')
        if (!validBookId(id)) return reply(response, 400, { error: '书稿编号无效。' })
        if (!await deleteBook(id)) return reply(response, 428, { code: 'sync_required', error: '请用新版青简删除同步书稿。' })
        return reply(response, 200, { deleted: true })
      }
      if (route === 'GET /api/book/git' || route === 'POST /api/book/git') {
        const id = url.searchParams.get('id')
        if (!validBookId(id)) return reply(response, 400, { error: '书稿编号无效。' })
        const action = request.method === 'POST' ? await readBody(request) : null
        const result = await queueWrite(async () => {
          if ((await readShelf()).deleted.includes(id)) return null
          const history = bookGitHistory(historyDirectory(id))
          if (request.method === 'POST') {
            if (action?.type === 'branch') return history.createBranch(action.name, action.oid)
            if (action?.type === 'commit') {
              const { book } = action
              if (book?.id !== id || typeof book.title !== 'string' || !Array.isArray(book.volumes) ||
                !book.volumes.every(volume => Array.isArray(volume?.chapters) &&
                  volume.chapters.every(chapter => typeof chapter?.text === 'string' && typeof chapter.title === 'string')) ||
                !book.volumes.some(volume => volume.chapters.length)) throw new Error('无法提交这份书稿。')
              return history.create(book, action)
            }
            throw new Error('未知的 Git 操作。')
          }
          const oid = url.searchParams.get('oid')
          if (!oid) return history.list()
          if (url.searchParams.has('restore')) return history.snapshot(oid)
          return history.compare(oid, url.searchParams.get('file'))
        })
        return reply(response, result ? 200 : 410, result || { error: '书籍已删除。' })
      }
      if (route === 'GET /api/book/versions' || route === 'POST /api/book/versions') {
        const id = url.searchParams.get('id')
        if (!validBookId(id)) return reply(response, 400, { error: '书稿编号无效。' })
        if ((await readShelf()).deleted.includes(id)) return reply(response, 410, { error: '书籍已删除。' })
        if (url.searchParams.has('chapter')) {
          const chapter = url.searchParams.get('chapter')
          if (!chapter || chapter.length > 256) return reply(response, 400, { error: '章节编号无效。' })
          if (request.method === 'POST') return reply(response, 200, { versions: await createChapterVersion(id, chapter, await readBody(request)) })
          const versions = await readJson(chapterVersionPath(id, chapter), [])
          const version = url.searchParams.get('version')
          if (!version) return reply(response, 200, { versions: versions.map(({ snapshot: _snapshot, ...entry }) => entry) })
          const snapshot = versions.find(entry => entry.id === version)?.snapshot
          return reply(response, snapshot ? 200 : 404, snapshot || { error: '找不到该章节版本。' })
        }
        if (request.method === 'POST') return reply(response, 200, { versions: await createVersion(id, await readBody(request)) })
        const versions = await readJson(versionsPath(id), [])
        const version = url.searchParams.get('version')
        if (!version) return reply(response, 200, { versions })
        if (!validBookId(version) || !versions.some((entry) => entry.id === version)) return reply(response, 404, { error: '找不到此备份。' })
        const book = await readJson(versionPath(id, version), null)
        return reply(response, book ? 200 : 404, book || { error: '备份文件不存在。' })
      }
      if (route === 'GET /api/ai/status' || route === 'GET /api/ai/accounts') {
        return reply(response, 200, await aiStatus(url.searchParams.has('check'), desktop && url.searchParams.has('defer-cli')))
      }
      if (route === 'POST /api/ai/accounts') { await ai.save(await readBody(request)); return reply(response, 200, await aiStatus()) }
      if (route === 'DELETE /api/ai/accounts') { await ai.remove(url.searchParams.get('id')); return reply(response, 200, await aiStatus()) }
      if (route === 'POST /api/ai/codex/login') {
        const { id, action } = await readBody(request)
        await codex.loginAction(action, id)
        return reply(response, 202, await aiStatus())
      }
      if (route === 'POST /api/ai/codex/accounts') { await codex.manage(await readBody(request)); return reply(response, 200, await aiStatus()) }
      if (route === 'POST /api/ai/codex/settings') { await codex.configure(await readBody(request)); return reply(response, 200, await aiStatus()) }
      if (route === 'GET /api/ai/usage') return reply(response, 200, await readJson(usagePath, {}))
      if (route === 'POST /api/skills/test') {
        const { script, input } = await readBody(request)
        return reply(response, 200, { result: await runSkillScript(script, input) })
      }
      if (route === 'POST /api/ai/image') {
        const { prompt, cover } = await readBody(request)
        if (!(await preferences()).images) return reply(response, 403, { error: '请先在全局设置启用配图。' })
        if (typeof prompt !== 'string' || !prompt.trim() || prompt.length > 2500) return reply(response, 400, { error: '请填写 2500 字以内的画面描述。' })
        const controller = new AbortController()
        response.once('close', () => { if (!response.writableFinished) controller.abort() })
        try {
          const result = await ai.image({ prompt: prompt.trim(), cover: cover === true, signal: controller.signal })
          if (!controller.signal.aborted) await trackUsage('image', true)
          if (!controller.signal.aborted) return reply(response, 200, result)
        } catch (error) {
          if (!controller.signal.aborted) await trackUsage('image', false)
          throw error
        }
        return
      }
      if (route === 'POST /api/ai/rewrite') {
        const controller = new AbortController()
        response.once('close', () => { if (!response.writableFinished) controller.abort() })
        const input = await readBody(request)
        const streaming = request.headers.accept?.includes('application/x-ndjson')
        const write = (type, value) => {
          if (!controller.signal.aborted) response.write(JSON.stringify(type === 'result' ? { type, value }
            : type === 'error' ? { type, message: value } : { type, text: value }) + '\n')
        }
        if (streaming) {
          response.writeHead(200, { 'Content-Type': 'application/x-ndjson; charset=utf-8', 'Cache-Control': 'no-store', 'X-Accel-Buffering': 'no' })
          write('stage', '等待模型生成')
        }
        try {
          const result = await rewrite(input, controller.signal, streaming ? write : undefined)
          if (controller.signal.aborted) return
          await trackUsage(input.intent || 'paragraph', true, result.usage)
          if (!streaming) return reply(response, 200, result)
          write('stage', '整理候选')
          write('result', result)
        } catch (error) {
          if (controller.signal.aborted) return
          await trackUsage(input.intent || 'paragraph', false)
          if (!streaming) throw error
          write('error', error.message)
        }
        if (streaming) response.end()
        return
      }
      return reply(response, 404, { error: '接口不存在。' })
    } catch (error) {
      if (response.destroyed) return
      return reply(response, 400, { error: url.pathname === '/api/book' ? '书稿服务端保存失败。' : error.message })
    }
  }

  return middleware
}

export function localApi(env) {
  const middleware = createApiHandler(env)
  return {
    name: 'qingjian-local-api',
    configureServer(server) { server.middlewares.use(middleware) },
    configurePreviewServer(server) { server.middlewares.use(middleware) },
  }
}
