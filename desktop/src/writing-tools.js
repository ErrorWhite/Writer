import { wordCount } from './manuscript.js'

const paragraphsOf = (text) => text.split(/\n+/).map((paragraph) => paragraph.trim()).filter(Boolean)
const sentencesOf = (text) => text.match(/[^。！？!?\n]+[。！？!?]?[”’」』]?/gu) || []
const shorten = (text, size = 62) => text.length > size ? `${text.slice(0, size - 1)}…` : text

export function chapterDigest(text) {
  const paragraphs = paragraphsOf(text)
  if (!paragraphs.length) return ['写下正文后，这里会提取本章的关键句。']
  const opening = sentencesOf(paragraphs[0])[0] || paragraphs[0]
  const ending = paragraphs.length > 1 ? paragraphs.at(-1) : sentencesOf(text).at(-1)?.trim()
  return [...new Set([opening.trim(), ending].filter(Boolean))].map((part) => shorten(part, 46))
}

function proofread(text) {
  const findings = []
  const repeatedPunctuation = text.match(/[，。！？；：]{2,}/u)
  const longSentences = sentencesOf(text).filter((sentence) => wordCount(sentence) > 60)
  const brackets = [['“', '”'], ['「', '」'], ['（', '）'], ['《', '》']]
  if (repeatedPunctuation) findings.push(`检查连续标点「${repeatedPunctuation[0]}」是否有意保留。`)
  for (const [open, close] of brackets) {
    if (text.split(open).length !== text.split(close).length) {
      findings.push(`「${open} ${close}」数量不匹配，请检查成对标记。`)
    }
  }
  for (const sentence of longSentences.slice(0, 3)) {
    findings.push(`长句可检查停顿与主语衔接：「${shorten(sentence.trim(), 24)}」`)
  }
  return findings.length ? findings : ['常见标点、成对标记和长句检查已完成，未发现提示项。']
}

export function runWritingTool(tool, { text, title, question }) {
  if (!text.trim()) return ['目前所选范围没有正文。写下几句后，再试试这个工具。']

  const paragraphs = paragraphsOf(text)
  switch (tool) {
    case 'proofread':
      return proofread(text)
    case 'continue':
      return [
        `接续上一段：「${shorten(paragraphs.at(-1), 40)}」`,
        '先让视角人物通过一个具体动作回应刚发生的事，再交代动作带来的后果。',
        '试写一句与读者预期相反的对白，让场景在回答一个问题的同时，留下另一个问题。',
      ]
    case 'summary': {
      const [opening, ending] = chapterDigest(text)
      const middle = paragraphs.length > 2 ? shorten(paragraphs[Math.floor(paragraphs.length / 2)]) : ''
      return [...new Set([opening, middle, ending].filter(Boolean))]
    }
    case 'inspire':
      return [
        `围绕「${title}」选一个刚出现的物件，让另一位人物对它给出截然不同的解释。`,
        '让主角必须在眼前的线索和一件紧迫的旧事之间取舍，写清楚放弃其中一项的代价。',
        '重写同一个场景的最后一幕，换成一个动作或声音来收尾。',
      ]
    default: {
      const terms = question.match(/[\p{Script=Han}]{2,}|[A-Za-z0-9]{2,}/gu) || []
      const lines = sentencesOf(text)
      const matches = lines.filter((line) => terms.some((term) => line.includes(term))).slice(0, 3)
      return matches.length
        ? matches.map((line) => `正文相关句：「${shorten(line.trim())}」`)
        : ['正文里未找到与提问直接匹配的词句。可以试试人物名、地点或正文里的短语。', ...chapterDigest(text).slice(0, 1)]
    }
  }
}

export function questionTool(question) {
  if (/总结|概括|摘要|简述/u.test(question)) return 'summary'
  if (/校对|错字|标点|检查/u.test(question)) return 'proofread'
  if (/续写|接续|接下来|下文/u.test(question)) return 'continue'
  if (/灵感|构思|创意|伏笔/u.test(question)) return 'inspire'
  return 'search'
}
