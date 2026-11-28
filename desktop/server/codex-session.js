import { spawn } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { access, mkdir, mkdtemp, readFile, rename, rm, rmdir, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { tmpdir } from 'node:os'
import { delimiter, dirname, join } from 'node:path'

const verificationUrl = 'https://auth.openai.com/codex/device'
const supportedEfforts = ['auto', 'minimal', 'low', 'medium', 'high', 'xhigh']

function deviceError(output, issued) {
  const httpStatus = output.match(/device code request failed with status\s+(\d{3})/i)?.[1]
  if (httpStatus === '403') return { reason: 'request-forbidden', message: 'OpenAI 拒绝了服务器的设备码请求（HTTP 403）。请检查服务器所在地区是否受 OpenAI 支持。' }
  if (httpStatus) return { message: `OpenAI 未能签发设备码（HTTP ${httpStatus}）。` }
  if (/device code login is not enabled/i.test(output)) return { message: 'OpenAI 授权端点当前未提供设备码登录。' }
  if (/timed out|timeout/i.test(output)) return { message: issued ? '设备码已过期，请重新发起授权。' : '服务器请求 OpenAI 设备码超时，请稍后重试。' }
  if (/error sending request|connection|certificate|\bTLS\b/i.test(output)) return { message: '服务器连接 OpenAI 授权服务失败，请检查网络。' }
  return { message: issued ? '设备授权未完成。请检查 ChatGPT 安全设置中的 Codex 设备码授权。' : 'Codex 未能获取设备码，请刷新状态后重试。' }
}

async function commandFor(env) {
  const isWindows = process.platform === 'win32'
  if (env.QINGJIAN_CODEX_BIN) {
    await access(env.QINGJIAN_CODEX_BIN)
    return [env.QINGJIAN_CODEX_BIN]
  }
  for (const directory of (process.env.PATH || '').split(delimiter).filter(Boolean)) {
    const binary = join(directory, isWindows ? 'codex.exe' : 'codex')
    if (await access(binary).then(() => true, () => false)) return [binary]
    if (isWindows) {
      const script = join(directory, 'node_modules', '@openai', 'codex', 'bin', 'codex.js')
      if (await access(script).then(() => true, () => false)) {
        try {
          const { arch } = process
          const nativePackage = createRequire(script).resolve(`@openai/codex-win32-${arch}/package.json`)
          const triple = arch === 'arm64' ? 'aarch64-pc-windows-msvc' : 'x86_64-pc-windows-msvc'
          const nativeBinary = join(dirname(nativePackage), 'vendor', triple, 'bin', 'codex.exe')
          await access(nativeBinary)
          return [nativeBinary]
        } catch { return [process.execPath, script] }
      }
    }
  }
  return null
}

function run(command, args, { input = '', timeout = 100000, onOutput, onStdout, cwd, signal, home } = {}) {
  return new Promise((resolve, reject) => {
    const useEmbeddedNode = process.versions.electron && command[0] === process.execPath
    const childEnv = { ...process.env, NO_COLOR: '1', ...(useEmbeddedNode ? { ELECTRON_RUN_AS_NODE: '1' } : {}) }
    if (home) {
      for (const key of Object.keys(childEnv)) {
        if (/^(CODEX_HOME|CODEX_API_KEY|OPENAI_API_KEY|CODEX_ACCESS_TOKEN|OPENAI_IDENTITY_TOKEN_FILE)$/iu.test(key)) delete childEnv[key]
      }
      childEnv.CODEX_HOME = home
    }
    const child = spawn(command[0], [...command.slice(1), ...args], {
      cwd, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'], signal,
      env: childEnv,
    })
    let stdout = ''
    let stderr = ''
    child.stdout.setEncoding('utf8').on('data', (chunk) => { stdout += chunk; onOutput?.(stdout + stderr); onStdout?.(chunk) })
    child.stderr.setEncoding('utf8').on('data', (chunk) => { stderr += chunk; onOutput?.(stdout + stderr) })
    const timer = setTimeout(() => {
      child.kill()
      reject(new Error('Codex 请求超时。'))
    }, timeout)
    child.once('error', (error) => { clearTimeout(timer); reject(error) })
    child.once('close', (code) => { clearTimeout(timer); resolve({ code, stdout, stderr }) })
    child.stdin.on('error', () => {})
    child.stdin.end(input)
  })
}

function createProfileSession(env, directory, execute, home) {
  const settingsPath = join(directory, 'ai-codex.json')
  let settings
  let command
  let connected = false
  let version = ''
  let checkedAt = 0
  let checking
  let login = { state: 'idle' }
  let loginController
  const invoke = (args, options) => execute(command,
    home ? ['-c', 'cli_auth_credentials_store="file"', ...args] : args,
    { ...options, home })

  function readSettings() {
    settings ||= readFile(settingsPath, 'utf8').then(JSON.parse).catch((error) => {
      if (error.code === 'ENOENT') return { model: 'gpt-6.2-sol', effort: 'auto', enabled: true }
      throw error
    })
    return settings
  }

  async function refresh() {
    checkedAt = Date.now()
    try {
      command = await commandFor(env)
      if (!command) { connected = false; version = ''; return }
      const [status, build] = await Promise.all([
        invoke(['login', 'status'], { timeout: 6500 }),
        version ? Promise.resolve({ stdout: version }) : invoke(['--version'], { timeout: 3000 }),
      ])
      connected = status.code === 0 && /ChatGPT/i.test(status.stdout + status.stderr)
      version = build.stdout.trim().slice(0, 80)
    } catch {
      connected = false
    }
  }

  async function status(force = false) {
    if (login.state !== 'pending' && (force || !checkedAt || Date.now() - checkedAt > 20000)) {
      checking ||= refresh().finally(() => { checking = null })
      await checking
    }
    return { installed: Boolean(command), connected, version, login, ...await readSettings() }
  }

  async function startLogin() {
    if (login.state === 'pending') return
    await status()
    if (login.state === 'pending') return
    if (!command) throw new Error('服务器尚未安装 Codex CLI。')
    const controller = new AbortController()
    loginController = controller
    login = { state: 'pending', url: verificationUrl }
    invoke(['login', '--device-auth'], {
      timeout: 960000,
      signal: controller.signal,
      onOutput(output) {
        if (loginController !== controller) return
        const text = output.replace(/\x1b\[[0-?]*[ -/]*[@-~]/g, '')
        const code = text.match(/\b[A-Z0-9]{4,6}-[A-Z0-9]{4,6}\b/)?.[0]
        if (code) login = { state: 'pending', url: verificationUrl, code }
      },
    }).then(async ({ code, stdout, stderr }) => {
      if (loginController !== controller) return
      if (code !== 0) {
        login = { state: 'error', ...deviceError(`${stderr}\n${stdout}`, Boolean(login.code)) }
        return
      }
      await refresh()
      if (loginController === controller) login = connected ? { state: 'connected' } : { state: 'error', message: 'CLI 尚未识别到 ChatGPT 登录。' }
    }).catch((error) => {
      if (loginController !== controller) return
      const output = error.message.includes('超时') ? 'timeout' : error.message
      login = { state: 'error', ...deviceError(output, Boolean(login.code)) }
    }).finally(() => { if (loginController === controller) loginController = undefined })
  }

  function cancelLogin() {
    const controller = loginController
    loginController = undefined
    controller?.abort()
    if (controller) login = { state: 'idle' }
  }

  async function configure(input) {
    const model = typeof input.model === 'string' ? input.model.trim() : ''
    if (model && !/^[\w./:@-]{1,160}$/u.test(model)) throw new Error('模型 ID 格式不正确。')
    if (!supportedEfforts.includes(input.effort)) throw new Error('Codex 思考等级不正确。')
    const next = { model: model || 'gpt-6.2-sol', effort: input.effort, enabled: input.enabled !== false }
    await mkdir(directory, { recursive: true })
    const temporary = join(directory, `ai-codex-${randomUUID()}.tmp`)
    try {
      await writeFile(temporary, JSON.stringify(next), { flag: 'wx', mode: 0o600 })
      await rename(temporary, settingsPath)
      settings = Promise.resolve(next)
    } finally {
      await rm(temporary, { force: true })
    }
  }

  async function generate({ instructions, prompt, timeout, signal, events }, defaults, account) {
    const current = { ...await status(), ...defaults }
    if (!current.connected || !current.enabled) throw new Error('请在 AI 服务中完成 Codex 登录并启用。')
    const cwd = await mkdtemp(join(tmpdir(), 'qingjian-edit-'))
    try {
      const args = ['--ask-for-approval', 'never', 'exec', '--sandbox', 'read-only', '--skip-git-repo-check',
        '--ephemeral', '--ignore-user-config', '--ignore-rules', '--disable', 'shell_tool', '--json']
      if (current.model) args.push('--model', current.model)
      if (current.effort !== 'auto') args.push('-c', 'model_reasoning_effort=' + JSON.stringify(current.effort))
      let pending = ''
      let emitted = ''
      const onStdout = events && (chunk => {
        const lines = (pending + chunk).split('\n')
        pending = lines.pop()
        for (const line of lines) {
          try {
            const event = JSON.parse(line)
            if (event.type === 'turn.started') events('stage', 'Codex 正在生成')
            if (event.item?.type === 'agent_message' && typeof event.item.text === 'string' && event.item.text.startsWith(emitted)) {
              events('delta', event.item.text.slice(emitted.length))
              emitted = event.item.text
            }
          } catch { /* Ignore CLI startup messages. */ }
        }
      })
      const result = await invoke([...args, '-'], { cwd, timeout, signal, onStdout, input: instructions + '\n' + prompt })
      if (result.code !== 0) throw new Error('Codex 生成失败。请检查登录状态、模型和额度。')
      let text = ''
      let usage
      let model = current.model
      for (const line of result.stdout.split('\n')) {
        try {
          const event = JSON.parse(line)
          if (event.type === 'item.completed' && event.item?.type === 'agent_message') text = event.item.text
          if (event.type === 'turn.completed') { usage = event.usage; model = event.model || model }
        } catch { /* Some CLI startup messages are not JSON. */ }
      }
      if (!text.trim()) throw new Error('Codex 未返回可用正文。')
      return { text: text.trim(), provider: 'codex', model, account: account.name, accountId: account.id,
        usage: { input: usage?.input_tokens ?? null, output: usage?.output_tokens ?? null, cached: usage?.cached_input_tokens ?? null } }
    } finally {
      await rmdir(cwd).catch(() => {})
    }
  }

  return { status, startLogin, cancelLogin, configure, generate, readSettings }
}

export function createCodexSession(env, directory, execute = run) {
  const metadataPath = join(directory, 'ai-codex-accounts.json')
  const systemAccount = { id: 'system', name: '系统 CLI 账号' }
  const system = createProfileSession(env, directory, execute)
  const sessions = new Map([['system', system]])
  let writes = Promise.resolve()

  async function readAccounts() {
    try { return JSON.parse(await readFile(metadataPath, 'utf8')) }
    catch (error) {
      if (error.code !== 'ENOENT') throw error
      return { selected: 'system', accounts: [] }
    }
  }

  function accountFor(accounts, id) {
    const account = id === 'system' ? systemAccount : accounts.accounts.find(item => item.id === id)
    if (!account) throw new Error('找不到该 Codex 账号。')
    return account
  }

  function sessionFor(account) {
    if (!sessions.has(account.id)) {
      const home = join(directory, 'codex-accounts', account.id)
      sessions.set(account.id, createProfileSession(env, directory, execute, home))
    }
    return sessions.get(account.id)
  }

  function updateAccounts(update) {
    const result = writes.then(async () => {
      const data = await readAccounts()
      await update(data)
      await mkdir(directory, { recursive: true })
      const temp = metadataPath + '-' + randomUUID()
      try {
        await writeFile(temp, JSON.stringify(data), { flag: 'wx', mode: 0o600 })
        await rename(temp, metadataPath)
      } finally { await rm(temp, { force: true }) }
    })
    writes = result.catch(() => {})
    return result
  }

  const nameOf = value => {
    const name = typeof value === 'string' ? value.trim() : ''
    if (!name || name.length > 60) throw new Error('请输入 1 至 60 个字符的账号备注。')
    return name
  }

  async function status(force = false) {
    const data = await readAccounts()
    const accounts = await Promise.all([systemAccount, ...data.accounts].map(async account => {
      const { installed, version, login, connected } = await sessionFor(account).status(force)
      return { ...account, installed, version, login, connected }
    }))
    const active = accounts.find(account => account.id === data.selected) || accounts[0]
    return { ...active, selected: active.id, accounts, ...await system.readSettings() }
  }

  async function loginAction(action, id) {
    const accounts = await readAccounts()
    const account = accountFor(accounts, id ?? accounts.selected)
    if (action === 'cancel') { sessionFor(account).cancelLogin(); return }
    if (account.id !== 'system') await mkdir(join(directory, 'codex-accounts', account.id), { recursive: true, mode: 0o700 })
    await sessionFor(account).startLogin()
  }

  async function manage({ action, id, name }) {
    return updateAccounts(data => {
      if (action === 'add') {
        if (data.accounts.length >= 12) throw new Error('最多添加 12 个独立 Codex 账号。')
        data.accounts.push({ id: randomUUID(), name: nameOf(name) })
      } else if (action === 'select') data.selected = accountFor(data, id).id
      else if (action === 'rename' && id !== 'system') accountFor(data, id).name = nameOf(name)
      else throw new Error('不支持该 Codex 账号操作。')
    })
  }

  async function generate(parameters) {
    const data = await readAccounts()
    const account = accountFor(data, data.selected)
    return sessionFor(account).generate(parameters, await system.readSettings(), account)
  }

  return { status, loginAction, manage, configure: system.configure, generate }
}
