import { contentSegments } from './scene-dividers.js'
import { preservesMarkup, sameProse } from './ai-review.js'

export function paragraphSpans(text) {
  return contentSegments(text).filter(({ divider }) => !divider)
}

const chapterLayouts = new WeakMap()

export function chapterLayout(chapter) {
  const text = chapter.text
  const cached = chapterLayouts.get(chapter)
  if (cached?.text === text) return cached
  const segments = contentSegments(text)
  const layout = { text, segments, spans: segments.filter(({ divider }) => !divider) }
  chapterLayouts.set(chapter, layout)
  return layout
}

export function paragraphInput(value) {
  if (value.includes('\n') && contentSegments(value).some(line => line.codeStart !== undefined)) {
    return value.replace(/\r\n?/g, '\n')
  }
  return value.replace(/\r?\n[ \t\u3000]*/g, ' ')
}

export function revisionSnapshot(chapter) {
  return {
    text: chapter.text,
    ...(chapter.paragraphs ? { paragraphs: JSON.parse(JSON.stringify(chapter.paragraphs)) } : {}),
    ...(chapter.plan ? { plan: { ...chapter.plan } } : {}),
    ...(chapter.batchReview ? { batchReview: JSON.parse(JSON.stringify(chapter.batchReview)) } : {}),
  }
}

export function paragraphRevisionSnapshot(chapter, paragraph, includeText = false) {
  return {
    paragraphRevision: JSON.parse(JSON.stringify(paragraph)),
    ...(includeText ? { text: chapter.text } : {}),
  }
}

export const pendingDrafts = paragraph => paragraph.drafts.filter(({ state }) => state === 'pending')
export function currentDraft(paragraph, pending = pendingDrafts(paragraph)) {
  return pending.find(({ id }) => id === paragraph.activeDraftId) || pending.at(-1)
}

export function restoreParagraphRevision(chapter, snapshot) {
  const saved = snapshot.paragraphRevision
  const paragraph = chapter.paragraphs?.find(({ id }) => id === saved.id)
  if (!paragraph) return null
  const includesText = Object.hasOwn(snapshot, 'text')
  const reciprocal = paragraphRevisionSnapshot(chapter, paragraph, includesText)
  if (includesText) chapter.text = snapshot.text
  for (const key of Object.keys(paragraph)) if (!Object.hasOwn(saved, key)) delete paragraph[key]
  Object.assign(paragraph, saved)
  return reciprocal
}

export function acceptBatchReview(chapter, choices) {
  const originals = chapter.batchReview || []
  const chosen = new Set(choices.map(item => item.id))
  if (!chosen.size || chosen.size !== choices.length || [...chosen].some(id => !originals.some(item => item.id === id))) return false
  syncParagraphs(chapter)
  const { spans } = chapterLayout(chapter)
  const indices = new Map(chapter.paragraphs.map(({ id }, index) => [id, index]))
  const changes = choices.map(choice => {
    const original = originals.find(item => item.id === choice.id)
    const index = indices.get(choice.id)
    const span = spans[index]
    const after = typeof choice.after === 'string' ? choice.after.trimEnd() : ''
    if (!span || span.text !== original.before || !after.trim() || /[\r\n]/u.test(after) ||
      sameProse(span.text, after) || !preservesMarkup(span.text, after)) return null
    const indentation = span.text.match(/^[ \t\u3000]*/u)[0]
    return { span, paragraph: chapter.paragraphs[index], after: indentation + after.trimStart() }
  })
  if (changes.some(item => !item)) return false
  const next = changes.sort((a, b) => b.span.start - a.span.start).reduce((text, { span, after }) =>
    text.slice(0, span.start) + after + text.slice(span.end), chapter.text)
  if (!preservesMarkup(chapter.text, next)) return false
  for (const { span, paragraph, after } of changes) {
    paragraph.text = after
    paragraph.drafts ||= []
    paragraph.drafts.push({ id: crypto.randomUUID(), text: after, base: span.text, state: 'accepted' })
  }
  chapter.text = next
  chapter.batchReview = originals.filter(item => !chosen.has(item.id))
  return true
}

export function paragraphMarkupSafe(chapter, id, candidate) {
  const index = chapter.paragraphs.findIndex(paragraph => paragraph.id === id)
  if (index < 0) return false
  const span = chapterLayout(chapter).spans[index]
  if (!span) return !chapter.text.trim()
  const text = paragraphInput(candidate)
  return preservesMarkup(span.text, text) &&
    preservesMarkup(chapter.text, chapter.text.slice(0, span.start) + text + chapter.text.slice(span.end))
}

const newParagraph = (text) => ({ id: crypto.randomUUID(), text, original: text, instruction: '', vote: '', drafts: [] })

export function syncParagraphs(chapter) {
  const current = chapter.paragraphs || []
  const { spans: lines } = chapterLayout(chapter)
  const spans = lines.length ? lines : [{ text: '' }]
  if (current.length === spans.length && current.every((paragraph, index) => paragraph.text === spans[index].text)) return

  let leading = 0
  while (leading < current.length && leading < spans.length && current[leading].text === spans[leading].text) leading += 1
  let trailing = 0
  while (trailing < current.length - leading && trailing < spans.length - leading &&
      current.at(-trailing - 1).text === spans.at(-trailing - 1).text) trailing += 1
  const oldMiddle = current.slice(leading, current.length - trailing)
  const newMiddle = spans.slice(leading, spans.length - trailing)
  const unused = new Set(oldMiddle)
  const byText = new Map()
  for (const paragraph of oldMiddle) {
    if (!byText.has(paragraph.text)) byText.set(paragraph.text, [])
    byText.get(paragraph.text).push(paragraph)
  }
  const matched = newMiddle.map(({ text }) => {
    const paragraph = byText.get(text)?.shift()
    if (paragraph) unused.delete(paragraph)
    return paragraph
  })
  const unmatched = [...unused]
  const reuseChanges = unmatched.length === matched.filter((paragraph) => !paragraph).length
  const middle = newMiddle.map(({ text }, index) => {
    if (matched[index]) return matched[index]
    if (reuseChanges) return { ...unmatched.shift(), text }
    return newParagraph(text)
  })
  chapter.paragraphs = [...current.slice(0, leading), ...middle, ...current.slice(current.length - trailing)]
}

export function updateParagraph(chapter, paragraphId, value) {
  const index = chapter.paragraphs.findIndex(({ id }) => id === paragraphId)
  if (index < 0) return false
  const text = paragraphInput(value)
  const paragraph = chapter.paragraphs[index]
  if (!text.trim() && paragraph.original !== '') return false
  const span = chapterLayout(chapter).spans[index]
  chapter.text = span ? chapter.text.slice(0, span.start) + text + chapter.text.slice(span.end) : chapter.text + text
  if (/[\r\n]/u.test(text)) {
    paragraph.text = paragraphSpans(text)[0]?.text ?? text
    syncParagraphs(chapter)
  } else paragraph.text = text
  return true
}

export const sameAnchor = (left, right) => left?.chapterId === right?.chapterId && left?.paragraphId === right?.paragraphId

export function anchorLabel(anchor, chapters) {
  const entry = chapters.find(({ chapter }) => chapter.id === anchor?.chapterId)
  const index = entry?.chapter.paragraphs?.findIndex(({ id }) => id === anchor.paragraphId)
  return index >= 0 ? `${entry.label} ${entry.chapter.title} · 第 ${index + 1} 段` : '段落已删除'
}

export function manuscriptStatistics(entries, research) {
  const paragraphs = entries.flatMap(({ chapter }) => chapter.paragraphs || paragraphSpans(chapter.text))
  return {
    paragraphs: paragraphs.filter(({ text }) => text.trim()).length,
    edited: paragraphs.filter(({ original, text }) => original !== undefined && !sameProse(original, text)).length,
    likes: paragraphs.filter(({ vote }) => vote === 'keep').length,
    dislikes: paragraphs.filter(({ vote }) => vote === 'revise').length,
    annotations: research.annotations.length,
  }
}
