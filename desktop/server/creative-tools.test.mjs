import { strict as assert } from 'node:assert'
import { createServer } from 'node:http'
import { mkdtemp } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { createApiHandler } from './local-api.js'
import { chapterTextDiff, creativeMaterial, newCreativeTools, validateCreativeResult } from '../src/creative-tools.js'
import { ensureContextBudget, newContextOptions, withContext } from '../src/context-options.js'
import { importManuscript, loadManuscript } from '../src/manuscript.js'
import { contentSegments, generatedManuscript, manuscriptMarkdown } from '../src/scene-dividers.js'
import { acceptBatchReview, syncParagraphs, updateParagraph } from '../src/revisions.js'
import { markupBlocks } from '../src/manuscript-markup.js'
import { sameProse, preservesMarkup } from '../src/ai-review.js'
import { importWorldBook, worldMatches } from '../src/world-book.js'
import { storyChronology, syncStoryArchive, undoStoryArchive } from '../src/story-handoff.js'
import { runSkillScript, skillFeedback } from './skill-runtime.js'

const anchor = { chapterId: 'one', paragraphId: 'paragraph-one' }
const chapter = { id: 'one', title: '起雾', text: '安鹭走进码头。', paragraphs: [{ id: anchor.paragraphId, text: '安鹭走进码头。' }] }
const oldBook = { id: 'book-test', title: '雾港', activeId: chapter.id, volumes: [{ id: 'v', title: '卷一', chapters: [chapter] }] }

test('Markdown 改稿保留清单状态、表格列、强调位置和场景分隔', () => {
  assert(!preservesMarkup('- [x] 值守', '- [ ] 巡逻'))
  assert(!preservesMarkup('前句。\n\n**灯塔**起火。', '**灯塔**仍在。\n\n后句。'))
  assert(!preservesMarkup('**灯塔**起火。', '起火的是**灯塔**。'))
  assert(!preservesMarkup('| A | B |\n| --- | --- |\n| 1 | 2 |', '| A |\n| --- |\n| 3 |'))
  assert(preservesMarkup('初稿\n\n---\n\n**灯塔**熄灭。', '前章\n\n＊　＊　＊\n\n**灯塔**亮起。'))
  assert(sameProse('前章\n\n---\n\n**灯塔** 亮起。', '前章\n\n＊　＊　＊\n\n__灯塔__ 亮起。'))
})

test('批量选段审批保留其余候选，重新加载后仍能接受', () => {
  let draft = { text: '　**第一段**旧句。\n不改第二段。\n第三段旧句。' }
  syncParagraphs(draft)
  const [first, , third] = draft.paragraphs
  draft.batchReview = [
    { id: first.id, before: first.text, after: '**第一段**新句。' },
    { id: third.id, before: third.text, after: '第三段新句。' },
  ]
  assert(acceptBatchReview(draft, [draft.batchReview[0]]))
  assert.equal(draft.text, '　**第一段**新句。\n不改第二段。\n第三段旧句。')
  assert.equal(draft.batchReview.length, 1)
  draft = JSON.parse(JSON.stringify(draft))
  assert(acceptBatchReview(draft, draft.batchReview))
  assert.equal(draft.text, '　**第一段**新句。\n不改第二段。\n第三段新句。')
  assert.equal(draft.paragraphs[2].id, third.id)
})

test('定稿归档遵循原文证据、旧事件、作者确认与撤销', () => {
  const book = loadManuscript(oldBook)
  const first = { id: 'one', text: '安鹭收信。' }
  const second = { id: 'two', text: '因为那封信，安鹭登船。' }
  const quote = second.text
  const handoff = { threads: [{ title: '寄信人', quote: first.text, note: '姓名未明' }],
    events: [{ title: '收信', quote: first.text, note: '收到信' }] }
  syncStoryArchive(book, first, handoff)
  assert.deepEqual(syncStoryArchive(book, first, handoff), [])
  const previous = book.research.events.at(-1).id
  const next = { events: [{ title: '登船', quote, note: '沿线索登船' }, { title: '假事件', quote: '票不在船上', note: '不显示' }],
    links: [{ from: previous, to: '登船', quote, label: '促使登船' }] }
  syncStoryArchive(book, second, next)
  assert.equal(book.research.links.length, 0)
  const [edge] = storyChronology(book.research.events, [{ chapter: first }, { chapter: second }])
  assert.equal(edge.chronology, true)
  const delta = syncStoryArchive(book, second, { ...next, links: next.links.map(link => ({ ...link, confirmed: true })) })
  assert.equal(book.research.links.length, 1)
  assert(!book.research.events.some(item => item.title === '假事件'))
  const inverse = undoStoryArchive(book.research, delta)
  assert.equal(book.research.links.length, 0)
  undoStoryArchive(book.research, inverse)
  assert.equal(book.research.links[0].quote, quote)
  assert.equal(book.research.foreshadows[0].anchors[0].anchor.chapterId, first.id)
})

test('世界书递归遵守注入预算、禁止联动和停止联动', () => {
  const entries = importWorldBook({ entries: [
    { comment: '甲', key: ['甲'], content: '乙、隔离' },
    { comment: '乙', key: ['乙'], content: '丙', preventRecursion: true },
    { comment: '丙', key: ['丙'], content: '甲' },
    { comment: '隔离', key: ['隔离'], content: '秘密', excludeRecursion: true },
  ] })
  const scan = worldMatches({ entries, recursion: 3 }, '甲')
  assert.deepEqual(scan.chosen.map(entry => entry.title), ['甲', '乙'])
  assert.equal(scan.discovered.get(entries[1].id), 1)
  assert.equal(worldMatches({ entries, recursion: 3 }, '甲', 20).chosen.length, 0)
})

test('Skill 脚本在隔离环境中使用声明工具，限时并将结果交给模型', async () => {
  const script = { name: '句长', tools: ['wordCount', 'readReference'],
    source: 'function run(input, tools) { return { count: tools.wordCount(input.text), note: tools.readReference("rules.md"), node: typeof process, network: typeof fetch } }',
    references: [{ path: 'rules.md', text: '少用套话' }] }
  assert.deepEqual(await runSkillScript(script, { text: '句子　节奏' }), { count: 4, note: '少用套话', node: 'undefined', network: 'undefined' })
  await assert.rejects(runSkillScript({ source: 'function run() { while(true) {} }' }, {}), /超过资源限制/)
  assert.equal((await skillFeedback([script], { 目标段落: '夜里行船。' }, 'paragraph'))[0].结果.count, 5)
  assert.deepEqual(validateCreativeResult('paragraph-polish', '{"decision":"revise","text":"终稿","note":"调整节奏"}', {}),
    { decision: 'revise', text: '终稿', note: '调整节奏' })
})

test('旧书初始化资料集合，全书 JSON 往返保留新资料与锚点', () => {
  const book = loadManuscript(oldBook)
  assert.deepEqual(book.creative, newCreativeTools())
  book.creative.cards.push({ id: 'hero', kind: 'character', title: '阿鹭', body: '码头守夜人', aliases: ['安鹭'], ...anchor })
  book.creative.references.push({ id: 'ref', structure: '先建立规律，再打破规律。', enabled: true })
  book.creative.templates.chapter = '注意视角。'
  book.creative.reviews.push({ id: 'review', ...anchor, role: 'style', state: 'dismissed' })
  book.creative.changes.push({ id: 'state', ...anchor, kind: 'character', targetId: 'hero' })
  const imported = importManuscript(JSON.stringify(book))
  assert.notEqual(imported.id, book.id)
  assert.deepEqual(imported.creative, book.creative)
  assert.equal(imported.volumes[0].chapters[0].paragraphs[0].id, anchor.paragraphId)
})

test('text 围栏保留日志和分隔符，接受多行候选后保留段落锚点', () => {
  const fenced = '```text\n[WARN] Visual consistency check failed.\n---\n```'
  assert.equal(generatedManuscript(fenced), fenced)
  assert.equal(manuscriptMarkdown(fenced), fenced)
  assert.equal(contentSegments(fenced).some(line => line.divider), false)
  const draft = { text: '控制台输出。\n后续正文。' }
  syncParagraphs(draft)
  const [first, following] = draft.paragraphs
  assert(updateParagraph(draft, first.id, fenced))
  assert.equal(draft.text, fenced + '\n后续正文。')
  assert.equal(draft.paragraphs[0].id, first.id)
  assert.equal(draft.paragraphs.at(-1).id, following.id)
})

test('正文自动识别了 Markdown 块并保留 CRLF 源码定位', () => {
  const sections = ['普通正文', '# 标题', '**强调**和[链接][ref]', '> 引用', '- [x] 任务',
    '| A | B |\r\n| --- | --- |\r\n| 1 | 2 |', '```text\r\n[WARN] check\r\n```', '[ref]: https://example.com']
  const source = sections.join('\r\n\r\n')
  const blocks = markupBlocks(source)
  assert.deepEqual(blocks.map(item => item.type), ['heading', 'paragraph', 'blockquote', 'list', 'table', 'code'])
  assert.deepEqual(blocks.map(({ start, end }) => source.slice(start, end)), sections.slice(1, -1))
  assert.equal(blocks[1].token.tokens.at(-1).href, 'https://example.com')
  assert.deepEqual(markupBlocks('```text\nstill typing'), [])
})

test('酒馆世界书导入、次级词门控和字符预算；排版变化不计为正文改动', () => {
  const entries = importWorldBook({ entries: {
    0: { comment: '港区地理', constant: true, content: '北侧是旧码头。', order: 9 },
    1: { comment: '守塔人', key: ['灯塔'], keysecondary: ['退潮'], selectiveLogic: 3, content: '守塔人会记录每次退潮。' },
    2: { comment: '已停用', constant: true, disable: true, content: '不可见' },
  } })
  assert.deepEqual(worldMatches({ entries, budget: 1000 }, '灯塔').chosen.map(item => item.title), ['港区地理'])
  assert.deepEqual(worldMatches({ entries, budget: 1000 }, '灯塔退潮').chosen.map(item => item.title), ['港区地理', '守塔人'])
  assert.equal(worldMatches({ entries, budget: 20 }, '灯塔退潮').chosen.length, 0)
  assert(sameProse('**灯塔**熄灭。', '灯塔熄灭。'))
  assert(!sameProse('**灯塔**熄灭。', '**灯塔**亮起。'))
  assert(!preservesMarkup('**灯塔**熄灭。', '灯塔亮起。'))
  assert(preservesMarkup('**灯塔**熄灭。', '**灯塔**亮起。'))
})

test('只选已确认资料，固定条目优先，场景限定在前三章，参考卡仅接入规划', () => {
  const book = loadManuscript(oldBook)
  const entries = ['a', 'b', 'c', 'd', 'one'].map(id => ({ chapter: { id, title: id } }))
  const make = (id, kind, data) => ({ id, kind, confirmed: true, title: id, body: '说明', ...data })
  book.creative.cards = [
    make('hero', 'character', { aliases: ['安鹭'] }), make('fixed', 'rule', { pinned: true }),
    make('draft', 'rule', { pinned: true, confirmed: false }), make('too-early', 'scene', { chapterId: 'a' }),
    make('recent', 'scene', { chapterId: 'd' }), make('middle', 'scene', { chapterId: 'b' }),
  ]
  book.creative.references = [{ id: 'ref', title: '节奏', enabled: true, rhythm: '慢进快出' }]
  const material = creativeMaterial(book, entries, chapter, 'chapter', chapter.text)
  assert.deepEqual(material.固定资料.map(card => card.id), ['fixed'])
  assert.deepEqual(material.命中资料.map(card => card.id), ['hero'])
  assert.deepEqual(material.前三章场景.map(card => card.id), ['recent', 'middle'])
  assert.equal(material.参考卡[0].节奏, '慢进快出')
  assert.deepEqual(creativeMaterial(book, entries, chapter, 'paragraph').参考卡, [])
})

test('超预算不裁剪审阅引用，差异保留中间未修改行', () => {
  const book = { context: { ...newContextOptions(), budget: 35 } }
  const material = { 审阅原文: [{ ...anchor, text: chapter.text }] }
  assert.deepEqual(withContext(book, material), material)
  assert.throws(() => ensureContextBudget(book, material), /缩小原文范围/)
  const [left, right] = chapterTextDiff('旧首\n共同段\n旧尾', '新首\n共同段\n新尾')
  assert.deepEqual(left.map(row => row.changed), [true, false, true])
  assert.deepEqual(right.map(row => row.changed), [true, false, true])
})

test('接口校验模型引用与状态目标，章节版本彼此隔离且可恢复', async (t) => {
  const directory = await mkdtemp(join(tmpdir(), 'qingjian-creative-test-'))
  const realFetch = globalThis.fetch
  t.after(() => { globalThis.fetch = realFetch })
  let answer
  globalThis.fetch = async () => Response.json({ output: [{ type: 'message', content: [{ type: 'output_text', text: JSON.stringify(answer) }] }] })
  const handler = createApiHandler({ QINGJIAN_DATA_DIR: directory, OPENAI_API_KEY: 'fixture-secret' })
  const server = createServer((request, response) => handler(request, response, () => response.end()))
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  t.after(() => server.close())
  const call = (path, method = 'GET', body) => realFetch('http://127.0.0.1:' + server.address().port + path, {
    method, ...(body && { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }),
  })
  const request = (intent, material) => call('/api/ai/rewrite', 'POST', { intent, material, provider: 'openai' })
  const material = { 审阅原文: [{ ...anchor, text: chapter.text }], 可提案条目: [{ id: 'hero', kind: 'character' }] }
  answer = { items: [{ role: 'continuity', paragraphId: anchor.paragraphId, quote: '走进码头', note: '核对守夜时间' }] }
  assert.equal((await (await request('chapter-review', material)).json()).data[0].chapterId, chapter.id)
  answer.items[0].quote = '不存在的句子'
  assert.equal((await request('chapter-review', material)).status, 400)
  answer = { items: [{ kind: 'character', targetId: 'unknown', paragraphId: anchor.paragraphId, quote: '走进码头', note: '确认位置变化', value: '码头' }] }
  assert.equal((await request('state-proposal', material)).status, 400)
  answer.items[0].targetId = 'hero'
  assert.equal((await (await request('state-proposal', material)).json()).data[0].value, '码头')

  answer = { items: [{ kind: 'event', title: '抵达码头', body: '安鹭进入码头', paragraphId: anchor.paragraphId, quote: '走进码头' }] }
  const library = { 审阅原文: material.审阅原文, 资料类型: ['event'] }
  const { data: extracted } = await (await request('library-extract', library)).json()
  assert.equal(extracted[0].chapterId, chapter.id)
  assert.equal((await request('library-extract', { ...library, 资料类型: ['scene'] })).status, 400)
  answer.items[0].quote = '不存在的句子'
  assert.equal((await request('library-extract', library)).status, 400)

  answer = { title: '雾中来信', chapterTarget: 2400, unknown: '不接收' }
  const { data: suggested } = await (await request('profile-suggest', { 作者构思: '雾港里的来信。' })).json()
  assert.deepEqual(suggested, { title: '雾中来信', chapterTarget: 2400 })
  answer.chapterTarget = 100000
  assert.equal((await request('profile-suggest', { 作者构思: '雾港里的来信。' })).status, 400)

  answer = { recap: '安鹭抵达码头。', bridge: '让下一章承接安鹭在码头看到的信。', directions: [
    { title: '追查寄件人', detail: '从字迹找到线索。' }, { title: '留在码头', detail: '守候第二封信。' },
  ], lessons: [{ kind: 'keep', text: '让行动揭示动机。' }], threads: [{ title: '来信', quote: '伪造引文', note: '未解' }] }
  const { data: handoff } = await (await request('chapter-close', { 目标段落: chapter.text })).json()
  assert.equal(handoff.bridge, answer.bridge)
  assert.deepEqual(handoff.threads, [])
  answer.bridge = ''
  assert.equal((await request('chapter-close', { 目标段落: chapter.text })).status, 400)

  answer = { structure: '限制信息范围', rhythm: '动作后留停顿', viewpoint: '贴近主角', conflict: '目标与阻碍', hook: '动作带来新疑问' }
  assert.deepEqual((await (await request('reference-analysis', { 样本: '长街转角只有一盏灯。' })).json()).data, answer)
  const longQuote = '月色随风从窗棂漏下来又跌落在潮汐深处产生一条正在睡觉的蜿蜒手臂'
  answer.structure = longQuote
  assert.equal((await request('reference-analysis', { 样本: longQuote })).status, 400)

  await call('/api/book', 'PUT', oldBook)
  const path = '/api/book/versions?id=book-test&chapter=one'
  const { versions } = await (await call(path, 'POST', chapter)).json()
  assert.equal(versions.length, 1)
  const restored = await (await call(path + '&version=' + versions[0].id)).json()
  assert.deepEqual(restored, chapter)
  assert.deepEqual(await (await call('/api/book/versions?id=book-test')).json(), { versions: [] })
  assert.equal((await call(path.replace('chapter=one', 'chapter=unknown') + '&version=' + versions[0].id)).status, 404)
})
