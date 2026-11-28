import { test } from 'node:test'
import { strict as assert } from 'node:assert'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createCodexSession } from './codex-session.js'

test('Codex 账号独立授权、取消后保留其他登录，切换后使用所选账号', async t => {
  const root = await mkdtemp(join(tmpdir(), 'qingjian-cli-test-'))
  t.after(() => rm(root, { recursive: true, force: true }))
  const signedIn = new Set([undefined])
  const authorizations = new Map()
  let lastHome
  function execute(_command, args, { home, onOutput, signal }) {
    const result = (stdout = '', code = 0) => ({ code, stdout, stderr: '' })
    if (home) assert(args.includes('cli_auth_credentials_store="file"'))
    if (args.includes('--version')) return Promise.resolve(result('codex-cli mock'))
    if (args.includes('status')) return Promise.resolve(signedIn.has(home) ? result('Logged in using ChatGPT') : result('', 1))
    if (args.includes('--device-auth')) return new Promise((resolve, reject) => {
      onOutput(authorizations.size ? 'BBBB-2222' : 'AAAA-1111')
      authorizations.set(home, () => { signedIn.add(home); resolve(result()) })
      signal.addEventListener('abort', () => reject(signal.reason), { once: true })
    })
    lastHome = home
    return Promise.resolve(result([
      { type: 'item.completed', item: { type: 'agent_message', text: '独立会话' } },
      { type: 'turn.completed', model: 'fixture-resolved-model',
        usage: { input_tokens: 42, output_tokens: 7, cached_input_tokens: 12 } },
    ].map(event => JSON.stringify(event)).join('\n')))
  }
  const env = { QINGJIAN_CODEX_BIN: process.execPath }
  const codex = createCodexSession(env, root, execute)
  await Promise.all(['个人', '工作'].map(name => codex.manage({ action: 'add', name })))
  const [, one, two] = (await codex.status()).accounts
  await Promise.all([one, two].map(account => codex.loginAction('login', account.id)))
  const pending = (await codex.status()).accounts.slice(1)
  assert.equal(new Set(pending.map(account => account.login.code)).size, 2)
  await codex.loginAction('cancel', two.id)
  const oneHome = join(root, 'codex-accounts', one.id)
  authorizations.get(oneHome)()
  for (let i = 0; i < 10 && !(await codex.status()).accounts[1].connected; i++) {
    await new Promise(resolve => setTimeout(resolve, 10))
  }
  await codex.manage({ action: 'select', id: one.id })
  const status = await codex.status()
  assert.equal(status.selected, one.id)
  assert.deepEqual(status.accounts.map(account => account.connected), [true, true, false])
  const restarted = createCodexSession(env, root, execute)
  const generated = await restarted.generate({ prompt: '内容', instructions: '写作' })
  assert.equal(generated.model, 'fixture-resolved-model')
  assert.deepEqual(generated.usage, { input: 42, output: 7, cached: 12 })
  assert.equal(generated.account, '个人')
  assert.equal(generated.accountId, one.id)
  assert.equal(lastHome, oneHome)
})
