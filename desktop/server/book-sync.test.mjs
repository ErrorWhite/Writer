import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { createServer } from 'node:http'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { createApiHandler } from './local-api.js'

test('existing copies are upgraded with conditional writes and deletion remains idempotent', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'qingjian-sync-'))
  t.after(() => rm(directory, { recursive: true, force: true }))
  async function start() {
    const handler = createApiHandler({ QINGJIAN_DATA_DIR: directory })
    const server = createServer((req, res) => handler(req, res, () => res.writeHead(404).end()))
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
    t.after(() => new Promise(resolve => server.close(resolve)))
    return async (path, method = 'GET', payload) => {
      const response = await fetch('http://127.0.0.1:' + server.address().port + path, {
        method, headers: payload ? { 'Content-Type': 'application/json' } : undefined,
        body: payload ? JSON.stringify(payload) : undefined,
      })
      return { status: response.status, body: await response.json() }
    }
  }
  const request = await start()
  const id = 'test-manuscript'
  const path = '/api/sync/v1/book?id=' + id
  const oldPath = '/api/book?id=' + id
  const book = { id, title: '雪路', volumes: [{ chapters: [{ text: '第一段。' }] }] }
  const next = { ...book, title: '雪路二稿' }

  assert.equal((await request('/api/sync/v1')).body.version, 1)
  assert.equal((await request(path)).status, 404)
  assert.equal((await request('/api/book', 'PUT', book)).status, 200)
  const { body: initial } = await request(path)
  assert.deepEqual(initial.book, book)
  const change = { operationId: 'request-a', baseRevision: initial.revision, book: next }
  const [first, second] = await Promise.all([
    request(path, 'PUT', change),
    request(path, 'PUT', { ...change, operationId: 'request-b', book: { ...book, title: '并发三稿' } }),
  ])
  const winner = first.status === 200 ? first : second
  assert.deepEqual([first.status, second.status].sort(), [200, 412])
  const accepted = first.status === 200 ? change : { ...change, operationId: 'request-b', book: { ...book, title: '并发三稿' } }
  assert.deepEqual(await request(path, 'PUT', accepted), winner)
  assert.equal((await request(path, 'PUT', { ...accepted, book })).status, 412)
  assert.equal((await request(path + '&since=' + winner.body.revision)).body.unchanged, true)
  assert.deepEqual((await request(oldPath)).body, accepted.book)
  assert.equal((await request('/api/book', 'PUT', book)).status, 428)
  assert.equal((await request('/api/book/import', 'POST', { book, target: id })).status, 428)
  assert.equal((await request(oldPath, 'DELETE')).status, 428)

  const restarted = await start()
  assert.deepEqual((await restarted(path)).body.book, accepted.book)
  const deletion = { operationId: 'request-delete', baseRevision: winner.body.revision }
  const deleted = await restarted(path, 'DELETE', deletion)
  assert.equal(deleted.status, 200)
  assert.deepEqual(await restarted(path, 'DELETE', deletion), deleted)
  assert.equal((await restarted(path)).status, 410)
  assert.equal((await restarted(path, 'PUT', change)).status, 410)
  assert.deepEqual((await restarted('/api/books?include=deleted')).body.deleted, [id])
})

test('production sync uses the same authenticated writing studio session', { timeout: 12000 }, async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'qingjian-prod-sync-'))
  const origin = 'https://sync.example.test'
  const username = 'sync-test'
  const password = 'synthetic-check-password'
  const hash = createHash('sha256').update('Basic ' + Buffer.from(username + ':' + password).toString('base64')).digest('hex')
  const child = spawn(process.execPath, [fileURLToPath(new URL('./production.js', import.meta.url))], {
    env: { ...process.env, QINGJIAN_PORT: '0', QINGJIAN_HTTPS_ORIGIN: origin,
      QINGJIAN_URL_PATH: '/', QINGJIAN_AUTHORIZATION_SHA256: hash, QINGJIAN_DATA_DIR: directory },
    stdio: ['ignore', 'pipe', 'inherit'],
  })
  t.after(async () => {
    if (child.exitCode === null && child.signalCode === null) {
      await new Promise(resolve => { child.once('exit', resolve); child.kill() })
    }
    await rm(directory, { recursive: true, force: true })
  })
  const port = await new Promise((resolve, reject) => {
    child.once('exit', code => reject(new Error('Production server exited with code ' + code)))
    child.once('error', reject)
    let output = ''
    child.stdout.on('data', chunk => {
      output += chunk
      const match = /listening on 127\.0\.0\.1:(\d+)/.exec(output)
      if (match) resolve(match[1])
    })
  })
  const base = 'http://127.0.0.1:' + port
  const path = '/api/sync/v1/book?id=auth-test'
  assert.equal((await fetch(base + path)).status, 401)
  const session = await fetch(base + '/api/session', {
    method: 'POST', headers: { Origin: origin, 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  assert.equal(session.status, 200)
  const cookie = session.headers.get('set-cookie').split(';')[0]
  const body = JSON.stringify({ baseRevision: null, operationId: 'auth-operation',
    book: { id: 'auth-test', title: '同步验收', volumes: [{ chapters: [] }] } })
  const write = (source) => fetch(base + path, {
    method: 'PUT', headers: { Origin: source, Cookie: cookie, 'Content-Type': 'application/json' }, body,
  })
  assert.equal((await write('https://unrelated.example.test')).status, 403)
  assert.equal((await write(origin)).status, 200)
  const catalog = await fetch(base + '/api/sync/v1/books', { headers: { Cookie: cookie } })
  assert.equal(catalog.status, 200)
  assert.deepEqual((await catalog.json()).books.map(book => book.id), ['auth-test'])
})
