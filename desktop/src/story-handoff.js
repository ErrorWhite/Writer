import { sameAnchor, syncParagraphs } from './revisions.js'

const normalize = text => text.trim().normalize('NFKC').toLowerCase()
const sameTitle = (record, title) => normalize(record.title) === normalize(title)
const collections = ['foreshadows', 'events', 'links']
const snapshot = research => Object.fromEntries(collections.map(key => [
  key, new Map(research[key].map(record => [record.id, JSON.stringify(record)])),
]))

export function syncStoryArchive(book, chapter, handoff) {
  const research = book.research
  const previous = snapshot(research)
  syncParagraphs(chapter)
  const anchorFor = quote => {
    if (typeof quote !== 'string' || !quote || !chapter.text.includes(quote)) return null
    const firstLine = quote.split(/\r?\n/u)[0].trim()
    const paragraph = firstLine && chapter.paragraphs.find(item => item.text.includes(firstLine))
    return paragraph ? { chapterId: chapter.id, paragraphId: paragraph.id } : null
  }
  function addEvent(title, description, quote, threadId) {
    const anchor = anchorFor(quote)
    if (!anchor) return
    let event = research.events.find(item => sameTitle(item, title) && sameAnchor(item.anchor, anchor))
    if (!event) {
      event = { id: crypto.randomUUID(), title, description, anchor, quote, source: 'handoff', ...(threadId && { threadId }) }
      research.events.push(event)
    }
    return event.id
  }

  for (const thread of handoff.threads || []) {
    const anchor = anchorFor(thread.quote)
    if (!anchor) continue
    let record = research.foreshadows.find(item => sameTitle(item, thread.title))
    if (!record) {
      record = { id: crypto.randomUUID(), title: thread.title, description: thread.note, status: 'open', anchors: [], source: 'handoff' }
      research.foreshadows.push(record)
    }
    if (record.status === 'recovered') continue
    if (!record.anchors.some(item => sameAnchor(item.anchor, anchor))) {
      record.anchors.push({ id: crypto.randomUUID(), anchor, role: '线索', quote: thread.quote, source: 'handoff' })
    }
    addEvent(thread.title, thread.note, thread.quote, record.id)
  }

  const current = new Map((handoff.events || []).map(item => [item.title, addEvent(item.title, item.note, item.quote)]))
  for (const relation of handoff.relations || []) {
    addEvent(`${relation.from} · ${relation.label} · ${relation.to}`, relation.label, relation.quote)
  }
  for (const link of (handoff.links || []).filter(item => item.confirmed)) {
    const from = current.get(link.from) || research.events.find(item => item.id === link.from)?.id
    const to = current.get(link.to)
    const anchor = anchorFor(link.quote)
    if (!anchor || !from || !to || from === to || research.links.some(item => item.from === from && item.to === to)) continue
    research.links.push({ id: crypto.randomUUID(), from, to, text: link.label, quote: link.quote, anchor, source: 'handoff' })
  }
  return collections.flatMap(key => research[key].flatMap(record => {
    const before = previous[key].get(record.id)
    const after = JSON.stringify(record)
    return before === after ? [] : [{ key, id: record.id, before, after }]
  }))
}

export function undoStoryArchive(research, delta) {
  const inverse = []
  for (const { key, id, before, after } of [...delta].reverse()) {
    const list = research[key]
    const index = list.findIndex(record => record.id === id)
    if (JSON.stringify(list[index]) !== after) continue
    if (before === undefined) {
      if (key === 'events' && research.links.some(link => link.from === id || link.to === id)) continue
      if (key === 'foreshadows' && research.events.some(event => event.threadId === id)) continue
      list.splice(index, 1)
    } else if (index < 0) list.push(JSON.parse(before))
    else list.splice(index, 1, JSON.parse(before))
    inverse.push({ key, id, before: after, after: before })
  }
  return inverse
}

export function storyChronology(events, chapters) {
  const order = new Map(chapters.map(({ chapter }, index) => [chapter.id, index]))
  const groups = new Map()
  for (const event of events) {
    const index = order.get(event.anchor?.chapterId)
    if (event.source !== 'handoff' || index === undefined) continue
    const group = groups.get(index) || []
    group.push(event)
    groups.set(index, group)
  }
  const indexed = [...groups].sort((a, b) => a[0] - b[0])
  return indexed.slice(1).map(([, group], index) => {
    const from = indexed[index][1].at(-1).id
    const to = group[0].id
    return { id: `chronology-${from}-${to}`, from, to, text: '章节顺序', chronology: true }
  })
}
