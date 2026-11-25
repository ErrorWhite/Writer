import { skillApproved } from './writing-skills.js'

export function newContextOptions() {
  return {
    mode: 'summary', budget: 12000, neighbors: 1, outline: true, memory: true, feedback: false,
    summary: '', memories: [], notes: [],
  }
}

export function providerReady(info, provider) {
  return ['openai', 'deepseek', 'codex'].includes(provider) ? Boolean(info[provider]) : Boolean(info.configured)
}

function headTail(text, limit) {
  if (text.length <= limit) return text
  if (limit < 12) return text.slice(0, limit)
  const first = Math.ceil((limit - 1) / 2)
  return text.slice(0, first) + '…' + text.slice(-(limit - first - 1))
}

function fitValue(value, available, mode) {
  if (typeof value === 'string') return mode === 'raw' ? value.slice(0, available) : headTail(value, available)
  if (!value || typeof value !== 'object') return value
  if (available < 32) return undefined
  if (Array.isArray(value)) {
    let remaining = available - 2
    const result = []
    for (const item of value) {
      const selected = fitValue(item, remaining, mode)
      if (selected === undefined) break
      const size = JSON.stringify(selected).length + 1
      if (size > remaining) break
      result.push(selected)
      remaining -= size
      if (remaining < 18) break
    }
    return result
  }
  const result = {}
  let remaining = available - 2
  for (const [key, item] of Object.entries(value)) {
    const budget = remaining - key.length - 5
    if (budget <= 0) continue
    const selected = fitValue(item, budget, mode)
    if (selected === undefined) continue
    const size = JSON.stringify(selected).length + key.length + 4
    if (size > remaining) continue
    result[key] = selected
    remaining -= size
  }
  return result
}

export function withContext(book, material) {
  const options = book.context
  const { 全书大纲: outline, ...rest } = material
  const prioritized = {
    ...Object.fromEntries(Object.entries(rest).filter(([key]) => !['全书正文', '逐段记录', '批注', '前文', '后文', '上一候选'].includes(key))),
    ...(options.mode === 'summary' && options.summary.trim() ? { 已保存的压缩摘要: options.summary } : {}),
    ...(options.memory ? { 记忆: options.memories.filter(({ enabled }) => enabled).map(({ title, text }) => ({ title, text })) } : {}),
    ...(options.feedback ? { 写作反馈: options.notes.map(({ text }) => text) } : {}),
    ...(options.outline ? { 全书大纲: outline || book.outline } : {}),
    ...Object.fromEntries(Object.entries(rest).filter(([key]) => ['前文', '后文', '上一候选', '逐段记录', '批注', '全书正文'].includes(key))),
  }
  const protectedKeys = ['目标段落', '初稿', '逐段提示', '章节摘要', '任务', '审阅原文', '可提案条目', '上一章交接', '按此方向推进']
  const essential = Object.fromEntries(Object.entries(prioritized).filter(([key]) => protectedKeys.includes(key)))
  const optional = Object.fromEntries(Object.entries(prioritized).filter(([key, value]) =>
    !protectedKeys.includes(key) && value !== undefined && value !== '' && (!Array.isArray(value) || value.length)))
  const limit = Number(options.budget) || 12000
  const skillReserve = rest.技能?.length && book.skills?.custom.some(skill =>
    book.skills.enabled.includes(skill.id) && skillApproved(book, skill)) ? Math.min(2600, Math.floor(limit * .22)) : 0
  const remaining = Math.max(0, limit - JSON.stringify(essential).length - 2 - skillReserve)
  return { ...essential, ...fitValue(optional, remaining, options.mode) }
}

export function ensureContextBudget(book, material) {
  const limit = Number(book.context.budget) || 12000
  if (JSON.stringify(material).length > limit) throw new Error('资料超过上下文预算，请缩小原文范围或提高预算。')
  return material
}
