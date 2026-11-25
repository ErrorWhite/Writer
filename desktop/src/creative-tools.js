import { validAssetData } from './library-assets.js'
import { profileFields } from './book-profile.js'
import { normalizeWorldBook, worldMatches } from './world-book.js'

export const materialKinds = { character: '人物', rule: '世界规则', scene: '场景' }
export const libraryKinds = { ...materialKinds, foreshadow: '伏笔', event: '剧情事件', annotation: '批注' }
export const promptTasks = { summary: '章节摘要', chapter: '整章生成', paragraph: '段落改稿', 'reference-analysis': '样本分析', 'chapter-review': '章节审阅', 'state-proposal': '状态提案' }
export const referenceFields = { structure: '叙事结构', rhythm: '节奏', viewpoint: '视角', conflict: '冲突', hook: '章末钩子' }
export const reviewRoles = { continuity: '设定一致性', transition: '跨章衔接', style: '文风' }

export function newCreativeTools() {
  return { cards: [], references: [], templates: {}, reviews: [], changes: [], assets: [], worldBook: normalizeWorldBook() }
}

export function loadCreativeTools(value) {
  const defaults = { ...value, ...newCreativeTools() }
  defaults.worldBook = normalizeWorldBook(value?.worldBook)
  for (const key of ['cards', 'references', 'reviews', 'changes']) {
    if (Array.isArray(value?.[key])) defaults[key] = value[key].filter(item => item && typeof item.id === 'string')
  }
  for (const key of Object.keys(promptTasks)) {
    if (typeof value?.templates?.[key] === 'string') defaults.templates[key] = value.templates[key]
  }
  if (Array.isArray(value?.assets)) defaults.assets = value.assets.filter(item =>
    typeof item?.id === 'string' && typeof item.title === 'string' && validAssetData(item.type, item.data)
  ).map(item => ({ ...item, group: typeof item.group === 'string' ? item.group : '',
    summary: typeof item.summary === 'string' ? item.summary : '', updatedAt: Number(item.updatedAt) || 0 }))
  return defaults
}

function comparable(value) {
  return value.toLowerCase().normalize('NFC').replace(/\s+/gu, ' ').trim()
}

function toPromptCard(card) {
  return { id: card.id, 类型: materialKinds[card.kind], 标题: card.title, 说明: card.body, 当前状态: card.state || undefined,
    ...(card.kind === 'character' && card.aliases?.length ? { 别名: card.aliases } : {}),
    ...(card.chapterId ? { 章节编号: card.chapterId } : {}) }
}

export function creativeMaterial(book, entries, chapter, intent, query = '') {
  const creative = book.creative
  if (!creative) return {}
  const index = entries.findIndex(entry => entry.chapter.id === chapter?.id)
  const preceding = new Map(entries.slice(Math.max(0, index - 3), Math.max(0, index)).map(({ chapter: item }, i) => [item.id, i]))
  const relevantText = comparable([query, chapter?.title, chapter?.plan?.summary].filter(Boolean).join('\n'))
  const lore = worldMatches(creative.worldBook, [query, chapter?.title, chapter?.plan?.summary].filter(Boolean).join('\n'), book.context?.budget)
  const cards = creative.cards.filter(card => card.confirmed && Object.hasOwn(materialKinds, card.kind))
  const fixed = cards.filter(card => card.pinned)
  const matches = cards.filter(card => !card.pinned && card.kind !== 'scene')
    .map(card => ({ card, rank: [card.title, ...(card.aliases || []), ...(card.keywords || [])]
      .filter(term => typeof term === 'string' && term.trim() && relevantText.includes(comparable(term))).length }))
    .filter(({ rank }) => rank > 0).sort((a, b) => b.rank - a.rank).map(({ card }) => card)
  const scenes = cards.filter(card => !card.pinned && card.kind === 'scene' && preceding.has(card.chapterId))
    .sort((a, b) => preceding.get(b.chapterId) - preceding.get(a.chapterId))
  const selectedCards = new Set([...fixed, ...matches, ...scenes].map(card => card.id))
  const attachments = (creative.assets || []).filter(asset => asset.context && selectedCards.has(asset.cardId) && asset.summary.trim())
    .slice(0, 6).map(asset => ({ 标题: asset.title, 摘要: asset.summary.slice(0, 400) }))
  const references = ['summary', 'chapter'].includes(intent)
    ? creative.references.filter(({ enabled }) => enabled).map(({ title, ...reference }) => ({
      标题: title, ...Object.fromEntries(Object.keys(referenceFields).map(key => [referenceFields[key], reference[key]])),
    })) : []
  return {
    世界书: lore.chosen.length ? lore.chosen.map(item => ({ 标题: item.title, 内容: item.content })) : undefined,
    固定资料: fixed.map(toPromptCard),
    命中资料: matches.map(toPromptCard),
    前三章场景: scenes.map(toPromptCard),
    关联资料摘要: attachments.length ? attachments : undefined,
    参考卡: references,
    任务模板: creative.templates[intent]?.trim() || undefined,
  }
}

export function evidenceMaterial(chapter, paragraphs) {
  return paragraphs.map(({ id, text }) => ({ chapterId: chapter.id, paragraphId: id, text }))
}

function requireText(value, max) {
  if (typeof value !== 'string' || !value.trim() || value.length > max) throw new Error('模型返回的字段缺失或超长。')
  return value.trim()
}

export function validateCreativeResult(intent, response, material) {
  const json = response.trim().replace(/^```(?:json)?\s*\n([\s\S]+)\n```$/iu, '$1')
  let data
  try { data = JSON.parse(json) } catch { throw new Error('模型未返回可读的 JSON，请重试。') }
  if (!data || typeof data !== 'object') throw new Error('模型未返回可读的结果。')
  if (intent === 'paragraph-polish') {
    if (!['keep', 'accept', 'revise'].includes(data.decision)) throw new Error('复审返回了无效结论。')
    return { decision: data.decision, note: requireText(data.note, 300),
      ...(data.decision === 'revise' ? { text: requireText(data.text, 12000) } : {}) }
  }
  if (intent === 'chapter-close') {
    const list = (items, limit) => Array.isArray(items) ? items.filter(item => item && typeof item === 'object').slice(0, limit) : []
    const quoteFor = item => typeof item.quote === 'string' && item.quote.trim() && material.目标段落?.includes(item.quote)
    const directions = list(data.directions, 4).map(item => ({ title: requireText(item.title, 80), detail: requireText(item.detail, 550) }))
    if (directions.length < 2) throw new Error('模型未给出足够的后续走向，请重试。')
    const events = list(data.events, 6).filter(quoteFor).map(item => ({
      title: requireText(item.title, 80), quote: requireText(item.quote, 280), note: requireText(item.note, 400),
    }))
    const titles = new Set(events.map(item => item.title))
    const earlier = new Set((Array.isArray(material.以往事件) ? material.以往事件 : []).map(item => item.id))
    return {
      recap: requireText(data.recap, 1600), bridge: requireText(data.bridge, 800), directions,
      events, links: list(data.links, 6).filter(item => quoteFor(item) &&
        item.from !== item.to && titles.has(item.to) && (titles.has(item.from) || earlier.has(item.from)))
        .map(item => ({ from: item.from, to: item.to, label: requireText(item.label, 120), quote: requireText(item.quote, 280) })),
      lessons: list(data.lessons, 5).filter(item => ['keep', 'avoid'].includes(item.kind))
        .map(item => ({ kind: item.kind, text: requireText(item.text, 350) })),
      threads: list(data.threads, 6).filter(quoteFor)
        .map(item => ({ title: requireText(item.title, 80), quote: requireText(item.quote, 280), note: requireText(item.note, 400) })),
      relations: list(data.relations, 6).filter(item => quoteFor(item) && item.from && item.to && item.from !== item.to &&
        item.quote.includes(item.from) && item.quote.includes(item.to)).map(item => ({
        from: requireText(item.from, 60), to: requireText(item.to, 60), label: requireText(item.label, 120), quote: requireText(item.quote, 280),
      })),
    }
  }
  if (intent === 'volume') return { title: requireText(data.title, 40), direction: requireText(data.direction, 1000) }
  if (intent === 'batch') {
    const ids = new Set(material.审阅原文.map(item => item.paragraphId))
    if (!Array.isArray(data.items) || data.items.length !== ids.size || data.items.some(item => !ids.delete(item?.paragraphId))) {
      throw new Error('模型返回的段落编号不完整，请重新生成。')
    }
    return data.items.map(item => ({ paragraphId: item.paragraphId, text: requireText(item.text, 20000) }))
  }
  if (intent === 'profile-suggest') {
    const values = {}
    for (const [key, field] of Object.entries(profileFields)) {
      if (!Object.hasOwn(data, key) || data[key] === null || data[key] === '') continue
      if (field.min === undefined) values[key] = requireText(data[key], field.max)
      else {
        const value = data[key]
        if (!Number.isInteger(value) || value < field.min || value > field.max) throw new Error(`模型返回的${field.label}超出范围。`)
        values[key] = value
      }
    }
    return values
  }
  if (intent === 'reference-analysis') {
    const sample = comparable(material.样本)
    return Object.fromEntries(Object.keys(referenceFields).map(key => {
      const value = requireText(data[key], 450)
      const options = comparable(value).split(/[。！？；\n]+/u)
      for (const sentence of options) {
        for (let index = 0; index <= sentence.length - 28; index++) {
          if (sample.includes(sentence.slice(index, index + 28))) throw new Error('分析结果引用了较长的样本原句，请重新生成。')
        }
      }
      return [key, value]
    }))
  }
  if (!Array.isArray(data.items) || data.items.length > (intent === 'library-extract' ? 8 : 12)) throw new Error('模型未返回有效的条目列表。')
  const evidence = new Map((material.审阅原文 || []).map(item => [item.paragraphId, item]))
  return data.items.map(item => {
    const source = evidence.get(item?.paragraphId)
    const quote = requireText(item?.quote, 260)
    if (!source || !source.text.includes(quote)) throw new Error('模型引用与原文不符，请重新审阅。')
    if (intent === 'library-extract') {
      if (!Object.hasOwn(libraryKinds, item.kind) || !material.资料类型?.includes(item.kind)) throw new Error('模型返回了未选择的资料类型。')
      return { kind: item.kind, chapterId: source.chapterId, paragraphId: source.paragraphId, quote,
        title: requireText(item.title, 80), body: requireText(item.body, 600) }
    }
    const shared = { chapterId: source.chapterId, paragraphId: source.paragraphId, quote, note: requireText(item.note, 650) }
    if (intent === 'chapter-review') {
      if (!Object.hasOwn(reviewRoles, item.role)) throw new Error('模型返回了未知审阅类别。')
      return { ...shared, role: item.role }
    }
    const allowed = material.可提案条目 || []
    if (!allowed.some(({ id, kind }) => id === item.targetId && kind === item.kind)) throw new Error('模型引用了不存在的状态条目。')
    return { ...shared, kind: item.kind, targetId: item.targetId, value: requireText(item.value, 800) }
  })
}

export function chapterTextDiff(base, candidate) {
  const before = base.split('\n')
  const after = candidate.split('\n')
  let start = 0
  while (start < before.length && start < after.length && before[start] === after[start]) start++
  let tail = 0
  while (tail < before.length - start && tail < after.length - start && before.at(-1 - tail) === after.at(-1 - tail)) tail++
  const rows = [before, after].map(lines => lines.map((text, index) => ({
    text, changed: index >= start && index < lines.length - tail,
  })))
  const firstEnd = before.length - tail
  const secondEnd = after.length - tail
  const width = secondEnd - start + 1
  const cells = (firstEnd - start + 1) * width
  if (cells > 500000 || start === firstEnd || start === secondEnd) return rows
  const common = new Uint32Array(cells)
  for (let i = firstEnd - start - 1; i >= 0; i--) {
    for (let j = width - 2; j >= 0; j--) {
      const offset = i * width + j
      common[offset] = before[start + i] === after[start + j] ? 1 + common[offset + width + 1]
        : Math.max(common[offset + width], common[offset + 1])
    }
  }
  let left = start
  let right = start
  while (left < firstEnd && right < secondEnd) {
    const offset = (left - start) * width + right - start
    if (before[left] === after[right]) {
      rows[0][left++].changed = false
      rows[1][right++].changed = false
    } else if (common[offset + width] >= common[offset + 1]) left++
    else right++
  }
  return rows
}
