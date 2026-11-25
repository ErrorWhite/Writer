import { aiActivity } from './ai-activity.js'

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
