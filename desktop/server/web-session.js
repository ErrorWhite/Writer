import { createHash, randomBytes, scrypt, timingSafeEqual } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { mkdir, rename, rm, writeFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { promisify } from 'node:util'

const cookieName = 'qingjian_novel_session'
const duration = 7 * 24 * 60 * 60
const attemptWindow = 10 * 60 * 1000
const derive = promisify(scrypt)

function respond(response, status, payload) {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
  response.end(JSON.stringify(payload))
}

async function readInput(request) {
  if (!request.headers['content-type']?.startsWith('application/json')) throw new Error('请输入账号和密码。')
  let size = 0
  const chunks = []
  for await (const part of request.iterator({ destroyOnReturn: false })) {
    size += part.length
    if (size > 4096) throw new Error('登录信息过长。')
    chunks.push(part)
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'))
}

function readPasswordRecord(file) {
  try {
    const record = JSON.parse(readFileSync(file, 'utf8'))
    if (typeof record?.username !== 'string' || !/^[a-f0-9]{32}$/u.test(record.salt) || !/^[a-f0-9]{64}$/u.test(record.digest)) {
      throw new Error('Invalid saved password record.')
    }
    return record
  } catch (error) {
    if (error.code === 'ENOENT') return null
    throw error
  }
}

export function createWebSession({ origin, path, authorizationHash, dataDirectory = '.qingjian' }) {
  const site = new URL(origin)
  if (site.protocol !== 'https:' || !/^[a-f0-9]{64}$/i.test(authorizationHash || '') || !/^\/(?:[a-z0-9-]+\/)*$/i.test(path)) {
    throw new Error('Production requires an HTTPS origin, a valid cookie path, and an authorization hash.')
  }
  const expected = Buffer.from(authorizationHash, 'hex')
  const passwordDirectory = resolve(dataDirectory)
  const passwordFile = join(passwordDirectory, 'web-password.json')
  let record = readPasswordRecord(passwordFile)
  let passwordUpdate = Promise.resolve()
  const sessions = new Map()
  const attempts = new Map()

  function tokenFrom(request) {
    return request.headers.cookie?.match(/(?:^|;\s*)qingjian_novel_session=([a-f0-9]{64})(?:;|$)/)?.[1]
  }

  function sessionFor(request) {
    const token = tokenFrom(request)
    if (!token) return null
    const session = sessions.get(token)
    if (session?.expiry > Date.now()) return session
    sessions.delete(token)
    return null
  }

  const isAuthorized = (request) => Boolean(sessionFor(request))

  function cookie(response, token, maxAge) {
    response.setHeader('Set-Cookie', `${cookieName}=${token}; Path=${path}; Max-Age=${maxAge}; HttpOnly; Secure; SameSite=Strict`)
  }

  async function matches(username, password, saved) {
    if (!saved) {
      const header = `Basic ${Buffer.from(`${username}:${password}`).toString('base64')}`
      return timingSafeEqual(expected, createHash('sha256').update(header).digest())
    }
    if (username !== saved.username) return false
    const digest = await derive(password, Buffer.from(saved.salt, 'hex'), 32)
    return timingSafeEqual(digest, Buffer.from(saved.digest, 'hex'))
  }

  function issueSession(username, response) {
    const now = Date.now()
    for (const [token, session] of sessions) if (session.expiry <= now) sessions.delete(token)
    const token = randomBytes(32).toString('hex')
    sessions.set(token, { username, expiry: now + duration * 1000 })
    cookie(response, token, duration)
    return respond(response, 200, { authenticated: true, required: true })
  }

  async function handle(request, response) {
    if (request.method === 'GET') return respond(response, 200, { authenticated: isAuthorized(request), required: true })
    if (request.headers.origin !== site.origin) return respond(response, 403, { error: '请从工作台登录。' })
    if (request.method === 'DELETE') {
      sessions.delete(tokenFrom(request))
      cookie(response, '', 0)
      return respond(response, 200, { authenticated: false, required: true })
    }
    if (request.method !== 'POST') return respond(response, 405, { error: '请求方式不支持。' })

    const ip = request.headers['x-qingjian-client-ip'] || request.socket.remoteAddress
    const now = Date.now()
    for (const [address, attempt] of attempts) if (now >= attempt.until) attempts.delete(address)
    const entry = attempts.get(ip) || { count: 0, until: now + attemptWindow }
    if (entry.count >= 10) return respond(response, 429, { error: '尝试次数过多，请稍后再试。' })

    try {
      const { username, password } = await readInput(request)
      if (typeof username !== 'string' || typeof password !== 'string') throw new Error('Invalid credentials.')
      const checkedRecord = record
      if (!await matches(username, password, checkedRecord) || checkedRecord !== record) {
        entry.count += 1
        attempts.set(ip, entry)
        return respond(response, 401, { error: '账号或密码不正确。' })
      }
      attempts.delete(ip)
      return issueSession(username, response)
    } catch {
      return respond(response, 400, { error: '请输入有效的登录信息。' })
    }
  }

  async function changePassword(request, response) {
    if (request.method !== 'POST') return respond(response, 405, { error: '请求方式不支持。' })
    if (request.headers.origin !== site.origin) return respond(response, 403, { error: '请从工作台修改密码。' })
    if (!sessionFor(request)) return respond(response, 401, { error: '请先登录。' })
    let input
    try {
      input = await readInput(request)
    } catch {
      return respond(response, 400, { error: '请输入有效的密码。' })
    }
    const { currentPassword, newPassword } = input || {}
    if (typeof currentPassword !== 'string' || typeof newPassword !== 'string' || newPassword.length < 8 ||
        newPassword.length > 256 || !newPassword.trim() || newPassword === currentPassword) {
      return respond(response, 400, { error: '新密码需为 8 至 256 个字符，并与当前密码不同。' })
    }
    const update = passwordUpdate.then(async () => {
      const currentSession = sessionFor(request)
      if (!currentSession) return respond(response, 401, { error: '请先登录。' })
      if (!await matches(currentSession.username, currentPassword, record)) {
        return respond(response, 400, { error: '当前密码不正确。' })
      }
      const salt = randomBytes(16)
      const digest = await derive(newPassword, salt, 32)
      const next = { username: currentSession.username, salt: salt.toString('hex'), digest: digest.toString('hex') }
      await mkdir(passwordDirectory, { recursive: true })
      const temporary = join(passwordDirectory, `web-password-${randomBytes(8).toString('hex')}.tmp`)
      try {
        await writeFile(temporary, JSON.stringify(next), { flag: 'wx', mode: 0o600 })
        await rename(temporary, passwordFile)
      } finally {
        await rm(temporary, { force: true })
      }
      record = next
      sessions.clear()
      return issueSession(currentSession.username, response)
    })
    passwordUpdate = update.catch(() => {})
    return update
  }

  return { handle, changePassword, isAuthorized, origin: site.origin }
}
