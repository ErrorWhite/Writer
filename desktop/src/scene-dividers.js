export const sceneMarker = '＊　＊　＊'
export const dividerStyles = [
  { id: 'ornament', label: '花饰' },
  { id: 'line', label: '细线' },
  { id: 'stars', label: '星号' },
]

export function isSceneDivider(line) {
  const marker = line.trim().normalize('NFKC').replace(/[ \t]/g, '')
  return /^(?:\*{3,}|[-‐‑‒–—―─━]{2,}|_{3,}|={3,}|[·•]{3,}|[✦❖※◇✧]{1,5})$/u.test(marker)
}

export function manuscriptLines(text) {
  let fence
  const lines = []
  for (const match of text.matchAll(/([^\r\n]*)(\r\n|[\r\n]|$)/gu)) {
    const [raw, line, newline] = match
    if (!raw) break
    const start = match.index
    const end = start + line.length
    const marker = line.match(/^ {0,3}(`{3,}|~{3,})(.*)$/u)
    const inFence = fence
    if (!fence && marker && (marker[1][0] !== '`' || !marker[2].includes('`'))) {
      fence = { delimiter: marker[1], start }
    }
    const codeEnd = Boolean(inFence && marker && marker[1][0] === inFence.delimiter[0] &&
      marker[1].length >= inFence.delimiter.length && !marker[2].trim())
    lines.push({ text: line, start, end, newline, codeStart: fence?.start, codeEnd })
    if (codeEnd) fence = undefined
  }
  return lines
}

export function contentSegments(text) {
  return manuscriptLines(text).filter(line => line.text.trim()).map(line => ({
    ...line, divider: line.codeStart === undefined && isSceneDivider(line.text),
  }))
}

export function editorDividerBlocks(text) {
  if (!/[*＊_—–\-─━=·•✦❖※◇✧]/u.test(text)) return []
  const blocks = []
  let first = 0
  for (const line of contentSegments(text)) {
    if (!line.divider) continue
    if (line.start > first) blocks.push({ text: text.slice(first, line.start).replace(/\r?\n$/u, '') })
    blocks.push(line)
    first = line.end + line.newline.length
  }
  if (!blocks.length) return []
  if (first < text.length) blocks.push({ text: text.slice(first) })
  return blocks
}

export function manuscriptMarkdown(text) {
  return manuscriptLines(text).map(line => line.codeStart === undefined && !/^(?: {4}|\t)/u.test(line.text) &&
    isSceneDivider(line.text) && !/^ *(?:-{3,}|_{3,}|\*{3,}) *$/u.test(line.text)
    ? '\n---\n' + line.newline : line.text + line.newline).join('')
}

export function generatedManuscript(value) {
  const text = value.trim().replace(/\r\n?/g, '\n')
  return manuscriptLines(text).map(line =>
    (line.codeStart === undefined && isSceneDivider(line.text) ? sceneMarker : line.text) + line.newline).join('')
}

export function insertSceneDivider(text, offset) {
  const before = text.slice(0, offset)
  const after = text.slice(offset)
  const leading = !before || before.endsWith('\n\n') ? '' : before.endsWith('\n') ? '\n' : '\n\n'
  const trailing = after.startsWith('\n\n') ? '' : after.startsWith('\n') ? '\n' : '\n\n'
  const insertion = leading + sceneMarker + trailing
  return { text: before + insertion + after, offset: offset + insertion.length }
}

export function removeSceneDivider(text, divider) {
  const before = text.slice(0, divider.start)
  const after = text.slice(divider.end)
  const between = before.endsWith('\n\n') ? after.replace(/^\r?\n(?:\r?\n)?/, '') : after
  return before + between
}
