import { marked } from 'marked'
import { manuscriptLines } from './scene-dividers.js'

const blockTypes = new Set(['heading', 'blockquote', 'list', 'table', 'code', 'hr'])
const inlineTypes = new Set(['strong', 'em', 'del', 'link', 'image', 'codespan', 'escape'])

// Shared by the human editor and the paragraph-review view. Ranges are offsets
// into the original manuscript, including for imported Windows line endings.
export function markupBlocks(source) {
  if (!/[#>*_`~|\[\]<(=+\\-]|(?:^|\n) {4}|(?:^|\n)\d+\./u.test(source)) return []
  const normalized = source.replace(/\r\n?/gu, '\n')
  const positions = []
  if (normalized.length !== source.length) {
    for (const match of source.matchAll(/\r\n/gu)) positions.push(match.index - positions.length)
  }
  function offset(index) {
    let lo = 0
    let hi = positions.length
    while (lo < hi) {
      const mid = (lo + hi) >> 1
      if (positions[mid] < index) lo = mid + 1
      else hi = mid
    }
    return index + lo
  }
  const result = []
  let cursor = 0
  for (const token of marked.lexer(normalized, { gfm: true, breaks: true })) {
    const at = normalized.indexOf(token.raw, cursor)
    if (at < 0) return []
    cursor = at + token.raw.length
    if (!blockTypes.has(token.type) && !token.tokens?.some(part => inlineTypes.has(part.type))) continue
    const raw = token.raw.replace(/\n+$/u, '')
    if (!raw) continue
    if (token.type === 'code') {
      const lines = manuscriptLines(raw)
      if (lines[0]?.codeStart === 0 && !lines.at(-1)?.codeEnd) continue
    }
    result.push({ start: offset(at), end: offset(at + raw.length), token, type: token.type })
  }
  return result
}
