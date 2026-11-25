// Git log traversal and parent-tree comparison adapted from Orison Space
// apps/desktop/client/shell/main/ipc/gitIpc.ts (Apache-2.0).
import fs from 'node:fs'
import { createHash } from 'node:crypto'
import { mkdir } from 'node:fs/promises'
import git from 'isomorphic-git'

const oidPattern = /^[a-f0-9]{40}$/u
const branchPattern = /^[a-zA-Z0-9][a-zA-Z0-9_-]{0,47}$/u
const refFor = (branch) => {
  if (typeof branch !== 'string' || !branchPattern.test(branch) || branch.toUpperCase() === 'HEAD') {
    throw new Error('分支名须以字母或数字开头，可使用字母、数字、连字符和下划线。')
  }
  return 'refs/heads/' + branch
}
const key = value => createHash('sha256').update(String(value)).digest('hex').slice(0, 24)
const snapshotFile = 'book.json'

export function bookGitHistory(gitdir) {
  const repository = { fs, gitdir }
  const branches = () => git.listBranches(repository)
  const readCommit = (oid) => {
    if (typeof oid !== 'string' || !oidPattern.test(oid)) throw new Error('提交编号无效。')
    return git.readCommit({ ...repository, oid })
  }

  async function list() {
    if (!fs.existsSync(gitdir)) return { branches: [], commits: [] }
    const names = await branches()
    const entries = new Map()
    const heads = {}
    for (const branch of names) {
      const ref = refFor(branch)
      heads[branch] = await git.resolveRef({ ...repository, ref })
      for (const { oid, commit } of await git.log({ ...repository, ref, depth: 60 })) {
        entries.set(oid, { oid, message: commit.message.trim(), parents: commit.parent, at: commit.author.timestamp * 1000 })
      }
    }
    return { branches: names, heads, commits: [...entries.values()].sort((a, b) => b.at - a.at) }
  }

  async function create(book, { branch = 'main', message = '', expected } = {}) {
    const ref = refFor(branch)
    if (typeof message !== 'string' || !message.trim() || message.length > 180) throw new Error('请输入不超过 180 字的提交说明。')
    if (!fs.existsSync(gitdir)) {
      await mkdir(gitdir, { recursive: true })
      await git.init({ ...repository, bare: true, defaultBranch: 'main' })
    }
    const names = await branches()
    const hasParent = names.includes(branch)
    if (!hasParent && (names.length || branch !== 'main')) throw new Error('请先创建此分支。')
    const parent = hasParent ? await git.resolveRef({ ...repository, ref }) : null
    if (parent !== expected) throw new Error('分支已更新，请刷新时间线后再提交。')
    // The existing Qingjian JSON stays the working copy. Write Git objects
    // directly so a branch or a failed commit cannot change the live manuscript.
    const files = snapshotEntries(book)
    const root = []
    const chapters = []
    for (const [path, contents] of files) {
      const directory = path.startsWith('chapters/') ? chapters : root
      const oid = await git.writeBlob({ ...repository, blob: Buffer.from(contents) })
      directory.push({ mode: '100644', path: path.split('/').at(-1), oid, type: 'blob' })
    }
    root.push({ mode: '040000', path: 'chapters', type: 'tree', oid: await git.writeTree({ ...repository, tree: chapters }) })
    const tree = await git.writeTree({ ...repository, tree: root })
    if (parent && (await readCommit(parent)).commit.tree === tree) throw new Error('书稿内容与该分支的最新提交一致。')
    const now = new Date()
    const author = { name: '青简', email: 'qingjian@localhost', timestamp: Math.floor(now.getTime() / 1000), timezoneOffset: now.getTimezoneOffset() }
    const oid = await git.writeCommit({ ...repository, commit: { tree, parent: parent ? [parent] : [], author, committer: author, message: message.trim() } })
    await git.writeRef({ ...repository, ref, value: oid, force: hasParent })
    return list()
  }

  async function createBranch(name, oid) {
    const ref = refFor(name)
    await readCommit(oid)
    const names = await branches()
    if (names.some(branch => branch.toLowerCase() === name.toLowerCase())) throw new Error('已有同名分支。')
    await git.writeRef({ ...repository, ref, value: oid })
    return list()
  }

  async function blobAt(oid, filepath) {
    try {
      return Buffer.from((await git.readBlob({ ...repository, oid, filepath })).blob).toString('utf8')
    } catch (error) {
      if (error.code === 'NotFoundError') return ''
      throw error
    }
  }
  async function snapshot(oid) {
    await readCommit(oid)
    return JSON.parse(await blobAt(oid, snapshotFile))
  }

  async function compare(oid, filepath) {
    const { commit } = await readCommit(oid)
    const parent = commit.parent[0]
    const currentTree = git.TREE({ ref: oid })
    const trees = parent ? [git.TREE({ ref: parent }), currentTree] : [currentTree]
    const changes = []
    await git.walk({
      ...repository, trees,
      map: async (path, entries) => {
        if (path === '.' || path === snapshotFile || !entries) return
        const [before, after] = parent ? entries : [null, entries[0]]
        const beforeId = before && await before.type() === 'blob' ? await before.oid() : null
        const afterId = after && await after.type() === 'blob' ? await after.oid() : null
        if (beforeId === afterId) return
        changes.push({ path, status: !beforeId ? 'added' : !afterId ? 'deleted' : 'modified' })
      },
    })
    if (filepath) {
      if (!changes.some(change => change.path === filepath)) throw new Error('此文件不在提交差异中。')
      const [before, after] = await Promise.all([parent ? blobAt(parent, filepath) : '', blobAt(oid, filepath)])
      return { before: before.slice(0, 120000), after: after.slice(0, 120000), truncated: before.length > 120000 || after.length > 120000 }
    }
    const titles = new Map()
    for (const book of [parent && await snapshot(parent), await snapshot(oid)]) {
      for (const volume of book?.volumes || []) {
        for (const chapter of volume.chapters) titles.set(chapterPath(volume, chapter), chapter.title)
      }
    }
    return changes.map(change => ({ ...change, title: titles.get(change.path) || fileTitles[change.path] || change.path }))
  }
  return { list, create, createBranch, snapshot, compare }
}

const fileTitles = { 'outline.md': '全书大纲', 'notes.md': '人物与世界设定', 'project.json': '创作设定、资料与书稿信息' }
const chapterPath = (volume, chapter) => `chapters/${key(volume.id)}-${key(chapter.id)}.md`
function snapshotEntries(book) {
  const { volumes, outline = '', notes = '', creative, ...details } = book
  const files = volumes.flatMap(volume => volume.chapters.map(chapter => [
    chapterPath(volume, chapter), `# ${chapter.title}\n\n${chapter.text}`,
  ]))
  const metadata = { ...details, cover: details.cover ? key(details.cover) : undefined,
    creative: creative && { ...creative, assets: creative.assets?.map(({ data, ...asset }) => ({ ...asset, contentId: key(data) })) },
    volumes: volumes.map(({ chapters, ...volume }) => ({ ...volume, chapters: chapters.map(({ text, paragraphs, ...chapter }) => ({
      ...chapter, paragraphs: paragraphs?.map(({ text, original, ...paragraph }) => paragraph),
    })) })),
  }
  return [...files, [snapshotFile, JSON.stringify(book)], ['project.json', JSON.stringify(metadata, null, 2)], ['outline.md', outline], ['notes.md', notes]]
}
