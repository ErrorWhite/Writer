import { randomUUID } from 'node:crypto'
import { mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises'
import { join } from 'node:path'

const platforms = {
  openai: { label: 'OpenAI', base: 'https://api.openai.com/v1', defaultModel: 'gpt-6.2-sol', efforts: ['auto', 'none', 'minimal', 'low', 'medium', 'high', 'xhigh'] },
  deepseek: { label: 'DeepSeek', base: 'https://api.deepseek.com', defaultModel: 'deepseek-flash', efforts: ['auto', 'none', 'low', 'high', 'max'] },
}

const isPlatform = (provider) => Object.hasOwn(platforms, provider)
const safeAccount = ({ id, name, provider, model, effort, enabled, managed }) => ({ id, name, provider, model, effort, enabled, managed })

function apiFailure(provider, status) {
  if (status === 429) return new Error(`${platforms[provider].label} 已限流或额度不足，请稍后重试或检查额度。`)
  if (status === 401 || status === 403) return new Error(`${platforms[provider].label} 未接受当前密钥，请检查权限。`)
  return new Error(`${platforms[provider].label} 请求失败（HTTP ${status}）。请检查模型和思考等级。`)
}

async function providerRequest(account, path, { body, timeout = 12000, signal, events } = {}) {
  const deadline = AbortSignal.timeout(timeout)
  const response = await fetch(platforms[account.provider].base + path, {
    method: body ? 'POST' : 'GET',
    headers: { Authorization: `Bearer ${account.key}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    redirect: 'error',
    body: body ? JSON.stringify(body) : undefined,
    signal: signal ? AbortSignal.any([signal, deadline]) : deadline,
  })
  if (!response.ok) {
    const error = apiFailure(account.provider, response.status)
    error.status = response.status
    throw error
  }
  if (events && response.headers.get('content-type')?.includes('text/event-stream')) return readEvents(response, account.provider, events)
  return response.json()
}

async function readEvents(response, provider, emit) {
  let pending = ''
  let text = ''
  let final
  let finish
  let usage
  for await (const chunk of response.body.pipeThrough(new TextDecoderStream())) {
    const lines = (pending + chunk).split(/\r?\n/u)
    pending = lines.pop()
    for (const line of lines) {
      if (!line.startsWith('data:')) continue
      const data = line.slice(5).trim()
      if (!data || data === '[DONE]') continue
      const event = JSON.parse(data)
      if (event.type === 'error' || event.type === 'response.failed') throw new Error('模型流式生成失败，请重试。')
      if (provider === 'openai') {
        if (event.type === 'response.completed' || event.type === 'response.incomplete') final = event.response
        if (event.type === 'response.output_text.delta') { text += event.delta; emit('delta', event.delta) }
        if (event.type === 'response.reasoning_summary_text.delta') emit('summary', event.delta)
      } else {
        const choice = event.choices?.[0]
        const delta = choice?.delta?.content
        if (delta) { text += delta; emit('delta', delta) }
        finish = choice?.finish_reason || finish
        usage = event.usage || usage
      }
    }
  }
  if (provider === 'openai') {
    if (!final) throw new Error('模型连接提前结束，请重试。')
    return { ...final, output: [{ type: 'message', content: [{ type: 'output_text', text }] }] }
  }
  if (!finish) throw new Error('模型连接提前结束，请重试。')
  return { choices: [{ finish_reason: finish, message: { content: text } }], usage }
}

export function createAiPool(dataDirectory, env) {
  const file = join(dataDirectory, 'ai-accounts.json')
  const managed = [['openai', env.OPENAI_API_KEY], ['deepseek', env.DEEPSEEK_API_KEY]]
    .filter(([, key]) => key)
    .map(([provider, key]) => ({
      id: `env-${provider}`, provider, key, name: `${platforms[provider].label} · 服务预配置`,
      model: platforms[provider].defaultModel, effort: provider === 'openai' ? 'auto' : 'none', enabled: true, managed: true,
    }))
  let records
  let writeQueue = Promise.resolve()
  const cursors = new Map()

  async function stored() {
    records ||= readFile(file, 'utf8').then(JSON.parse).catch((error) => {
      if (error.code === 'ENOENT') return []
      throw error
    })
    return records
  }

  async function store(items) {
    await mkdir(dataDirectory, { recursive: true })
    const temporary = join(dataDirectory, `ai-accounts-${randomUUID()}.tmp`)
    try {
      await writeFile(temporary, JSON.stringify(items), { flag: 'wx', mode: 0o600 })
      await rename(temporary, file)
      records = Promise.resolve(items)
    } finally {
      await rm(temporary, { force: true })
    }
  }

  async function status() {
    const accounts = [...managed, ...await stored()].map(safeAccount)
    return {
      accounts, configured: accounts.some(({ enabled }) => enabled),
      openai: accounts.some(({ enabled, provider }) => enabled && provider === 'openai'),
      deepseek: accounts.some(({ enabled, provider }) => enabled && provider === 'deepseek'),
    }
  }

  function queue(callback) {
    const result = writeQueue.then(callback)
    writeQueue = result.catch(() => {})
    return result
  }

  function save(input) {
    return queue(async () => {
      const items = await stored()
      const existing = items.find(({ id }) => id === input.id)
      if (input.id && !existing) throw new Error('找不到可编辑的账号。')
      const provider = input.provider
      if (!isPlatform(provider)) throw new Error('请选择 AI 服务商。')
      const platform = platforms[provider]
      const text = (value) => typeof value === 'string' ? value.trim() : ''
      const key = text(input.key) || (existing?.provider === provider ? existing.key : '')
      const model = text(input.model) || platform.defaultModel
      const effort = input.effort || (provider === 'openai' ? 'auto' : 'none')
      const name = text(input.name) || platform.label
      if (!/^[\x21-\x7e]{8,512}$/.test(key)) throw new Error('请输入有效的服务商 API 密钥。')
      if (!/^[\w./:@-]{1,160}$/u.test(model)) throw new Error('模型 ID 格式不正确。')
      if (!platform.efforts.includes(effort)) throw new Error('所选思考等级与服务商不兼容。')
      if (name.length > 60) throw new Error('账号备注不得超过 60 个字符。')
      const account = { id: existing?.id || randomUUID(), key, name, provider, model, effort, enabled: input.enabled !== false }
      if (!existing || key !== existing.key || provider !== existing.provider) await providerRequest(account, '/models')
      await store(existing ? items.map((item) => item.id === account.id ? account : item) : [...items, account])
      return status()
    })
  }

  function remove(id) {
    return queue(async () => {
      const items = await stored()
      if (!items.some((account) => account.id === id)) throw new Error('找不到可删除的账号。')
      await store(items.filter((account) => account.id !== id))
      return status()
    })
  }

  async function generate({ provider, instructions, prompt, outputTokens, timeout, signal, events }) {
    const requested = isPlatform(provider) ? provider : 'auto'
    const accounts = [...managed, ...await stored()].filter((account) => account.enabled && (requested === 'auto' || account.provider === requested))
    if (!accounts.length) throw new Error('请先在 AI 服务中连接并启用账号。')
    const cursor = cursors.get(requested) || 0
    const account = accounts[cursor % accounts.length]
    cursors.set(requested, (cursor + 1) % accounts.length)
    const { model, effort } = account
    let result, text, truncated
    if (account.provider === 'openai') {
      const configuredEffort = effort !== 'auto' ? { effort: /^(?:gpt-6\.2-)/u.test(model) && effort === 'none' ? 'low' : effort } : {}
      const wantsSummary = events && /^gpt-6\.2-/u.test(model)
      const request = summary => {
        const reasoning = summary ? { ...configuredEffort, summary: 'auto' } : configuredEffort
        return providerRequest(account, '/responses', {
          timeout, signal, events, body: {
            model, instructions, input: prompt, store: false,
            max_output_tokens: outputTokens,
            ...(events ? { stream: true } : {}),
            ...(Object.keys(reasoning).length ? { reasoning } : {}),
          },
        })
      }
      try { result = await request(wantsSummary) }
      catch (error) {
        if (!wantsSummary || ![400, 403].includes(error.status) || signal?.aborted) throw error
        result = await request(false)
      }
      truncated = result.incomplete_details?.reason === 'max_output_tokens'
      text = result.output?.flatMap((item) => item.type === 'message' ? item.content : [])
        .filter((item) => item.type === 'output_text').map((item) => item.text).join('\n')
    } else {
      result = await providerRequest(account, '/chat/completions', {
        timeout, signal, events, body: {
          model, messages: [{ role: 'system', content: instructions }, { role: 'user', content: prompt }],
          max_tokens: outputTokens,
          ...(events ? { stream: true, stream_options: { include_usage: true } } : {}),
          ...(effort !== 'auto' ? { thinking: { type: effort === 'none' ? 'disabled' : 'enabled' }, reasoning_effort: effort } : {}),
        },
      })
      const choice = result.choices?.[0]
      truncated = choice?.finish_reason === 'length'
      text = choice?.message?.content
    }
    if (truncated) throw new Error('生成到达输出上限。可降低思考等级或缩短生成目标再试。')
    if (!text?.trim()) throw new Error('模型未返回可用正文。')
    return {
      text: text.trim(), provider: account.provider, model, account: account.name,
      usage: {
        input: result.usage?.input_tokens ?? result.usage?.prompt_tokens ?? null,
        output: result.usage?.output_tokens ?? result.usage?.completion_tokens ?? null,
      },
    }
  }

  async function image({ prompt, cover, signal }) {
    const accounts = [...managed, ...await stored()].filter(account => account.enabled && account.provider === 'openai')
    if (!accounts.length) throw new Error('请先连接启用的 OpenAI API 账号。')
    const cursor = cursors.get('images') || 0
    const account = accounts[cursor % accounts.length]
    cursors.set('images', cursor + 1)
    const result = await providerRequest(account, '/images/generations', { signal, timeout: 180000, body: {
      model: 'gpt-image-2.5-flare', prompt, size: cover ? '1024x1536' : '1536x1024', quality: 'medium',
      output_format: 'webp', n: 1,
    } })
    const base64 = result.data?.[0]?.b64_json
    if (typeof base64 !== 'string' || !/^[A-Za-z0-9+/]+={0,2}$/u.test(base64)) throw new Error('图像服务未返回可用图片。')
    return { image: 'data:image/webp;base64,' + base64 }
  }

  return { status, save, remove, generate, image }
}
