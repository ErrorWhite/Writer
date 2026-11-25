import { excerpt } from './book-profile.js'
import { strFromU8, unzipSync } from 'fflate'
import { parse } from 'yaml'

const builtInSkills = [
  {
    id: 'style-distillation',
    name: '文风蒸馏',
    description: '从样稿或本章提取叙述习惯，保持作者语体。',
    instruction: '先从作者样稿归纳句长、视角、对话节奏、意象密度和标点使用，再把这些规律用于目标段。样稿缺失时参考相邻段落。只借用写法，避免照搬独特措辞、人物或情节。',
  },
  {
    id: 'break-patterns',
    name: '破甲',
    description: '拆掉套话和惯性转折，把动作和观察放回场景。',
    instruction: '找出这一段里最可预料的衔接、抽象感受或叙事姿势。优先利用原文已有的动作、感官和话语来重组句子，改变节奏或聚焦点。保留人物选择、已确认因果和世界设定。',
  },
  {
    id: 'natural-prose',
    name: '去 AI 味',
    description: '局部清理模板腔，保留原有语气和信息。',
    instruction: '最后局部清理重复总结、空泛评价、机械对称句式和无依据的渲染。保留原有叙事视角、信息、对话习惯和有意的文学表达。未明确命中这些问题的句子保持原样。',
  },
]

export function availableSkills(book) {
  return [...builtInSkills, ...book.skills.custom]
}

export function activeSkills(book, task = '') {
  return availableSkills(book).filter(({ id }) => book.skills.enabled.includes(id)).map(({ id, name, instruction, files }) => ({
    name,
    instruction,
    ...(files?.length ? { references: files.filter(file => file.text && (
      instruction.includes(file.path) || instruction.includes(file.path.split('/').at(-1)) ||
      (task && file.path.toLowerCase().includes(task.toLowerCase()))))
      .slice(0, 5).map(file => ({ path: file.path, text: excerpt(file.text, 1800) })) } : {}),
    ...(id === 'style-distillation' && book.skills.styleSample.trim() ? { sample: excerpt(book.skills.styleSample.trim(), 1600) } : {}),
  }))
}

export function parseSkillFile(filename, content) {
  let name = filename.replace(/\.[^.]+$/, '')
  let description = ''
  let instruction = content.trim()
  if (/\.json$/i.test(filename)) {
    const data = JSON.parse(instruction)
    if (typeof data?.name !== 'string' || typeof (data.instruction ?? data.prompt) !== 'string') {
      throw new Error('JSON 技能需要 name 和 instruction 字段。')
    }
    name = data.name
    description = typeof data.description === 'string' ? data.description : ''
    instruction = data.instruction ?? data.prompt
  } else {
    const frontmatter = instruction.match(/^---\r?\n([\s\S]*?)\r?\n---(?:\r?\n|$)/)
    const heading = instruction.match(/^#\s+(.+)$/m)
    if (frontmatter) {
      const metadata = parse(frontmatter[1], { maxAliasCount: 0, uniqueKeys: true, schema: 'core' })
      if (typeof metadata?.name === 'string') name = metadata.name
      if (typeof metadata?.description === 'string') description = metadata.description
      instruction = instruction.slice(frontmatter[0].length)
      if (!name.trim() || !instruction.trim()) throw new Error('技能名称和提示内容不能为空。')
      return { id: crypto.randomUUID(), name: name.trim().slice(0, 60), description, instruction: instruction.trim(), metadata }
    }
    name = heading?.[1] || name
  }
  if (!name.trim() || !instruction.trim()) throw new Error('技能名称和提示内容不能为空。')
  return { id: crypto.randomUUID(), name: name.trim().slice(0, 60), description: description.trim(), instruction: instruction.trim() }
}

const packageLimit = 3 * 1024 * 1024
const textAsset = /\.(?:md|txt|json|yaml|yml|csv|tsv|xml|html|css|js|ts|py|sh|ps1|toml)$/iu
const validPath = path => !path.includes('\\') && !path.includes(':') && !path.includes('\0') && path.split('/').every(part => part && part !== '..' && part !== '.')
const toolNames = ['wordCount', 'find', 'readReference']
const fallbackApproval = new Map()

export function skillScript(skill) {
  const config = skill.metadata?.qingjian
  if (typeof config?.entry !== 'string' || !config.entry.endsWith('.js') || !validPath(config.entry)) return null
  const files = Array.isArray(skill.files) ? skill.files : []
  const entry = files.find(file => file.path === config.entry && typeof file.text === 'string')
  if (!entry || entry.text.length > 32000) return null
  const tools = Array.isArray(config.tools) ? config.tools.filter(name => toolNames.includes(name)) : []
  return { name: skill.name, entry: config.entry, source: entry.text, tools,
    references: tools.includes('readReference') ? files.filter(file => file.path !== entry.path && file.text && !/\.(?:js|ts|sh|py|ps1)$/iu.test(file.path))
      .slice(0, 8).map(file => ({ path: file.path, text: file.text.slice(0, 2400) })) : [] }
}

const approvalKey = (book, skill) => `qingjian:skill-approval:${book.id}:${skill.id}`
export function skillApproved(book, skill) {
  const script = skillScript(skill)
  if (!script) return false
  const key = approvalKey(book, skill)
  try { return localStorage.getItem(key) === JSON.stringify(script) }
  catch { return fallbackApproval.get(key) === JSON.stringify(script) }
}

export function approveSkill(book, skill, enabled) {
  const key = approvalKey(book, skill)
  const script = skillScript(skill)
  if (!script || !enabled) {
    fallbackApproval.delete(key)
    try { localStorage.removeItem(key) } catch {}
    return
  }
  const proof = JSON.stringify(script)
  try { localStorage.setItem(key, proof) }
  catch { fallbackApproval.set(key, proof) }
}

export function skillRequest(book, intent) {
  if (!['paragraph', 'paragraph-polish', 'batch', 'chapter-rewrite', 'chapter'].includes(intent)) return {}
  const scripts = book.skills.custom.filter(skill => book.skills.enabled.includes(skill.id) && skillApproved(book, skill))
    .map(skillScript).slice(0, 4)
  return scripts.length ? { scripts, contextBudget: book.context.budget } : {}
}

export async function importSkillPackage(files) {
  const inputs = Array.from(files)
  if (!inputs.length) throw new Error('请选择技能包。')
  const [first] = inputs
  let entries
  if (inputs.length === 1 && /\.zip$/iu.test(first.name)) {
    if (first.size > packageLimit) throw new Error('技能包不能超过 3 MB。')
    let size = 0
    let count = 0
    entries = Object.entries(unzipSync(new Uint8Array(await first.arrayBuffer()), {
      filter({ name, originalSize }) {
        if (name.endsWith('/')) return false
        if (!validPath(name)) throw new Error('技能包包含无效文件路径。')
        size += originalSize
        if (size > packageLimit || ++count > 150) throw new Error('解压后的技能包不能超过 3 MB 或 150 个文件。')
        return true
      },
    }))
  } else if (inputs.length === 1 && !first.webkitRelativePath && !/^SKILL\.md$/iu.test(first.name)) {
    if (first.size > 128 * 1024) throw new Error('单个技能文件不能超过 128 KB。')
    return parseSkillFile(first.name, await first.text())
  } else {
    if (inputs.length > 150 || inputs.reduce((bytes, file) => bytes + file.size, 0) > packageLimit) throw new Error('技能包不能超过 3 MB 或 150 个文件。')
    entries = await Promise.all(inputs.map(async file => [file.webkitRelativePath || file.name, new Uint8Array(await file.arrayBuffer())]))
  }
  const entry = entries.find(([path]) => /(^|\/)SKILL\.md$/iu.test(path))
  if (!entry) throw new Error('技能包缺少 SKILL.md。')
  const [path, data] = entry
  const root = path.slice(0, path.length - 'SKILL.md'.length)
  const skill = parseSkillFile(first.name, strFromU8(data))
  skill.files = entries.filter(([name]) => name.startsWith(root) && name !== path && validPath(name)).map(([name, bytes]) =>
    textAsset.test(name) ? { path: name.slice(root.length), text: strFromU8(bytes) }
      : { path: name.slice(root.length), base64: btoa(Array.from(bytes, byte => String.fromCharCode(byte)).join('')) })
  return skill
}
