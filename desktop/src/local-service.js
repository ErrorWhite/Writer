import { aiActivity } from './ai-activity.js'
import { androidCall, androidWorkbench } from './android-workbench.js'
import { prepareRewrite, structuredIntents } from './rewrite-request.js'
import { validateCreativeResult } from './creative-tools.js'

export function apiUrl(path) {
  return new URL(`.${path}`, document.baseURI)
}

export async function localRequest(path, { method = 'GET', body, signal } = {}) {
  const stream = path === '/api/ai/rewrite'
  const activity = stream || path === '/api/ai/image'
  const id = activity ? ++aiActivity.id : -1
  const show = patch => { if (aiActivity.id === id) Object.assign(aiActivity, patch) }
  if (activity) show({ intent: body?.intent || (stream ? 'paragraph' : 'image'), stage: '准备资料', pending: true, preview: '', summary: '' })
  try {
    if (androidWorkbench) {
      const url = new URL(path, document.baseURI)
      if (url.pathname === '/api/book/versions') return androidCall('versions', {
        chapter: url.searchParams.get('chapter'), version: url.searchParams.get('version'), create: method === 'POST',
      }, signal)
      if (url.pathname === '/api/ai/usage') return androidCall('usage', {}, signal)
      if (!stream) throw new Error('请在原生工作台使用此功能。')
      const { intent = 'paragraph', material } = body
      const { instructions, promptMaterial } = prepareRewrite(intent, material)
      show({ stage: '等待模型生成' })
      const result = await androidCall('rewrite', { ...body, intent, instructions, prompt: JSON.stringify(promptMaterial) },
        signal ?? AbortSignal.timeout(180000), (type, value) => {
          if (type === 'stage') show({ stage: value })
          if (type === 'delta' && !structuredIntents.has(intent)) show({ preview: (aiActivity.preview + value).slice(-1800) })
          if (type === 'summary') show({ summary: (aiActivity.summary + value).slice(-700) })
        })
      if (structuredIntents.has(intent) && !Object.hasOwn(result, 'data')) {
        result.data = validateCreativeResult(intent, result.text, material)
        delete result.text
      }
      show({ stage: '已完成' })
      return result
    }
    const response = await fetch(apiUrl(path), {
      method, signal,
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json', ...(stream && { Accept: 'application/x-ndjson' }) },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    if (response.ok && stream && response.headers.get('content-type')?.includes('application/x-ndjson')) {
      const reader = response.body.pipeThrough(new TextDecoderStream()).getReader()
      let input = ''
      let result
      const consume = line => {
        if (!line.trim()) return
        const event = JSON.parse(line)
        if (event.type === 'error') throw new Error(event.message)
        if (event.type === 'result') result = event.value
        if (event.type === 'stage') show({ stage: event.text })
        if (event.type === 'delta') show({ preview: (aiActivity.preview + event.text).slice(-1800) })
        if (event.type === 'summary') show({ summary: (aiActivity.summary + event.text).slice(-700) })
      }
      try {
        while (true) {
          const { value, done } = await reader.read()
          if (done) break
          input += value
          const lines = input.split('\n')
          input = lines.pop()
          lines.forEach(consume)
        }
        consume(input)
      } finally { reader.releaseLock() }
      if (!result) throw new Error('生成连接提前结束，请重试。')
      show({ stage: '已完成' })
      return result
    }
    const result = await response.json().catch(() => ({}))
    if (!response.ok) throw new Error(result.error || `请求失败（HTTP ${response.status}）。`)
    if (activity) show({ stage: '已完成' })
    return result
  } catch (error) {
    if (activity) show({ stage: signal?.aborted ? '已取消' : '生成失败' })
    throw error
  } finally {
    if (activity) show({ pending: false })
  }
}
