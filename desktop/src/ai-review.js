import { marked } from 'marked'
import { contentSegments } from './scene-dividers.js'

function children(token) {
  if (token.type === 'table') return [...token.header, ...token.rows.flat()].flatMap(cell => cell.tokens || [])
  return token.tokens || token.items || []
}
function plain(tokens) {
  return tokens.map(token => {
    if (token.type === 'br' || token.type === 'space' || token.type === 'hr') return ' '
    const nested = children(token)
    if (nested.length) return plain(nested) + (['list_item', 'paragraph', 'heading'].includes(token.type) ? ' ' : '')
    return token.text || token.raw || ''
  }).join('')
}
export function sameProse(before, after) {
  if (before === after) return true
  const normalize = value => scenes(value).map(scene => plain(marked.lexer(scene, { gfm: true })))
    .join(' ').replace(/[\s\u3000]+/gu, ' ').trim()
  return normalize(before) === normalize(after)
}

const structural = new Set(['strong', 'em', 'del', 'codespan', 'heading', 'code', 'list', 'blockquote', 'table', 'link', 'image', 'hr', 'br'])
function scenes(value) {
  let start = 0
  const chunks = contentSegments(value).filter(line => line.divider).map(line => {
    const scene = value.slice(start, line.start)
    start = line.end + line.newline.length
    return scene
  })
  chunks.push(value.slice(start))
  return chunks
}

function hasMarkup(tokens) {
  return tokens.some(token => structural.has(token.type) || hasMarkup(children(token)))
}

function signature(tokens) {
  return tokens.filter(token => token.type !== 'space' && !(token.type === 'text' && !token.text?.trim())).map(token => {
    if (token.type === 'list') return ['list', token.ordered, token.start,
      token.items.map(item => [Boolean(item.task), item.checked ?? null, signature(item.tokens)])]
    if (token.type === 'table') return ['table', token.align,
      [token.header, ...token.rows].map(row => row.map(cell => signature(cell.tokens || [])))]
    const nested = children(token)
    if (structural.has(token.type)) return [token.type, token.depth, token.lang?.trim(), token.href, token.title, signature(nested)]
    return hasMarkup(nested) ? ['block', signature(nested)] : 'text'
  })
}
export function preservesMarkup(before, after) {
  const expected = scenes(before)
  const actual = scenes(after)
  if (expected.length !== actual.length) return false
  return expected.every((scene, index) => {
    const beforeTokens = marked.lexer(scene, { gfm: true })
    if (!hasMarkup(beforeTokens)) return true
    return JSON.stringify(signature(beforeTokens)) === JSON.stringify(signature(marked.lexer(actual[index], { gfm: true })))
  })
}

export const markdownCommands = [
  ['heading', '标题'], ['bold', '粗体'], ['italic', '斜体'], ['quote', '引用'], ['list', '列表'],
  ['task', '任务清单'], ['table', '表格'], ['code', '代码块'], ['link', '链接'],
]
export function markdownSnippet(command, selection = '') {
  if (command === 'bold') return `**${selection || '粗体'}**`
  if (command === 'italic') return `*${selection || '斜体'}*`
  if (command === 'link') return `[${selection || '链接文字'}](https://example.com)`
  const text = {
    heading: '# ' + (selection || '标题'),
    quote: '> ' + (selection || '引用'),
    list: '- ' + (selection || '项目'),
    task: '- [ ] ' + (selection || '任务'),
    table: '| 列一 | 列二 |\n| --- | --- |\n| 内容 | 内容 |',
    code: '```text\n' + (selection || '代码') + '\n```',
  }[command]
  return text ? '\n\n' + text + '\n\n' : selection
}
