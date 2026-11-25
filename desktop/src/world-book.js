const string = (value, max) => typeof value === 'string' ? value.trim().slice(0, max) : ''
const integer = (value, fallback, min, max) => Number.isFinite(Number(value)) ? Math.max(min, Math.min(max, Math.round(Number(value)))) : fallback
export const worldKeys = input => [...new Set((Array.isArray(input) ? input : String(input || '').split(/[,，、\n]/u))
  .map(key => string(key, 80)).filter(Boolean))].slice(0, 32)
const secondaryLogic = ['any', 'all', 'not-any', 'not-all']

export function worldEntry(input = {}) {
  const keys = worldKeys(input.keys ?? input.key)
  const title = string(input.title ?? input.comment, 80) || keys[0] || '未命名条目'
  const logic = input.logic ?? ['any', 'not-all', 'not-any', 'all'][input.selectiveLogic] ?? 'any'
  return {
    id: typeof input.id === 'string' && input.id ? input.id : crypto.randomUUID(),
    title, content: string(input.content, 9000), keys, secondary: worldKeys(input.secondary ?? input.keysecondary),
    enabled: input.enabled ?? !input.disable, always: Boolean(input.always ?? input.constant),
    logic: secondaryLogic.includes(logic) ? logic : 'any',
    priority: integer(input.priority ?? input.order, 100, 0, 1000),
    caseSensitive: Boolean(input.caseSensitive),
    excludeRecursion: Boolean(input.excludeRecursion),
    preventRecursion: Boolean(input.preventRecursion),
    recursionDelay: integer(input.recursionDelay ?? input.delayUntilRecursion, 0, 0, 3),
  }
}

export function normalizeWorldBook(input) {
  const entries = input?.entries
  return { entries: Array.isArray(entries) ? entries.filter(item => item && typeof item === 'object').slice(0, 800).map(worldEntry) : [],
    budget: integer(input?.budget, 1800, 100, 16000), recursion: integer(input?.recursion, 0, 0, 3) }
}

export function importWorldBook(input) {
  const records = input?.entries
  if (!records || typeof records !== 'object') throw new Error('请选择带 entries 的世界书 JSON。')
  return (Array.isArray(records) ? records : Object.values(records)).slice(0, 800)
    .filter(item => item && typeof item.content === 'string' && item.content.trim())
    .map(item => worldEntry({ ...item, id: undefined, ...(item.selective === false ? { keysecondary: [] } : {}) }))
}

export function worldMatches(world, query, outerBudget = 12000) {
  const entries = (world?.entries || []).filter(item => item.enabled && item.content)
    .sort((a, b) => Number(b.always) - Number(a.always) || b.priority - a.priority)
  const budget = Math.min(world?.budget || 1800, Math.floor(outerBudget * .35))
  const depth = integer(world?.recursion, 0, 0, 3)
  const active = []
  const chosen = []
  const seen = new Set()
  const discovered = new Map()
  let text = String(query || '').normalize('NFKC')
  let used = 0
  for (let round = 0; round <= depth; round++) {
    const lowered = text.toLowerCase()
    const matches = (key, sensitive) => {
      const normalized = key.normalize('NFKC')
      return sensitive ? text.includes(normalized) : lowered.includes(normalized.toLowerCase())
    }
    const activate = item => {
      if (item.always) return true
      if (!item.keys.some(key => matches(key, item.caseSensitive))) return false
      const found = item.secondary.map(key => matches(key, item.caseSensitive))
      if (!found.length) return true
      if (item.logic === 'all') return found.every(Boolean)
      if (item.logic === 'not-any') return !found.some(Boolean)
      if (item.logic === 'not-all') return !found.every(Boolean)
      return found.some(Boolean)
    }
    const next = []
    for (const item of entries) {
      if (seen.has(item.id) || (!item.always && ((round && item.excludeRecursion) ||
        round < (item.recursionDelay || 0))) || !activate(item)) continue
      seen.add(item.id)
      active.push(item)
      const size = JSON.stringify({ 标题: item.title, 内容: item.content }).length + 1
      if (used + size > budget) continue
      used += size
      chosen.push(item)
      discovered.set(item.id, round)
      if (!item.preventRecursion) next.push(item.content)
    }
    if (!round && !next.length) break
    if (next.length) text += '\n' + next.join('\n').normalize('NFKC')
  }
  return { active, chosen, used, budget, discovered }
}
