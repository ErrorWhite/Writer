import DOMPurify from 'dompurify'
import { strToU8, zipSync } from 'fflate'
import { marked } from 'marked'
import { chapterLabel, downloadBookBackup, downloadFile, downloadText } from './manuscript.js'
import { manuscriptMarkdown } from './scene-dividers.js'
import { bookCover } from './shelf-store.js'

const escape = value => String(value ?? '').replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/gu, '')
  .replace(/[<>&"']/g, char => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&apos;' })[char])
const documentXml = (title, body) => `<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="zh-CN"><head><title>${escape(title)}</title><link rel="stylesheet" href="book.css"/></head><body>${body}</body></html>`

function chapterHtml(text) {
  const fragment = DOMPurify.sanitize(marked.parse(manuscriptMarkdown(text), { breaks: true, gfm: true }), {
    RETURN_DOM_FRAGMENT: true, USE_PROFILES: { html: true },
    FORBID_TAGS: ['form', 'style', 'img', 'iframe', 'video', 'audio', 'input'],
  })
  const serializer = new XMLSerializer()
  return [...fragment.childNodes].map(node => serializer.serializeToString(node)).join('')
}

export function ebook(book) {
  const files = { mimetype: [strToU8('application/epub+zip'), { level: 0 }] }
  const add = (name, value) => { files[name] = typeof value === 'string' ? strToU8(value) : value }
  add('META-INF/container.xml', '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
  add('EPUB/book.css', 'body{max-width:38em;margin:1.6em auto;padding:0 5%;font-family:serif;line-height:1.85}h1{margin:1.2em 0 1.7em;font-size:1.55em}p{margin:.7em 0}blockquote{border-left:.17em solid #9cae9f;padding-left:1em}hr{border:0;border-top:1px solid #9cae9f;width:22%;margin:2em auto}pre{white-space:pre-wrap;padding:1em;background:#edf0ed}img{display:block;max-width:100%;max-height:93vh;margin:auto}table{border-collapse:collapse}td,th{border:1px solid #aaa;padding:.3em}.book-title{text-align:center}')
  const manifest = ['<item id="toc" href="toc.xhtml" media-type="application/xhtml+xml" properties="nav"/>', '<item id="css" href="book.css" media-type="text/css"/>']
  const spine = []
  const page = (id, title, body) => {
    add(`EPUB/${id}.xhtml`, documentXml(title, body))
    manifest.push(`<item id="${id}" href="${id}.xhtml" media-type="application/xhtml+xml"/>`)
    spine.push(`<itemref idref="${id}"/>`)
  }
  const cover = bookCover(book)
  if (cover) {
    const [, mime, b64] = cover.match(/^data:(image\/(?:png|jpeg|webp));base64,(.+)$/u)
    const file = 'cover.' + mime.split('/')[1]
    add('EPUB/' + file, Uint8Array.from(atob(b64), char => char.charCodeAt(0)))
    manifest.push(`<item id="image" href="${file}" media-type="${mime}" properties="cover-image"/>`)
    page('cover', book.title, `<img src="${file}" alt="${escape(book.title)}"/>`)
  }
  page('title', book.title, `<h1 class="book-title">${escape(book.title)}</h1>`)
  let order = 0
  const nav = book.volumes.map(volume => {
    const chapters = volume.chapters.map(chapter => {
      const id = 'chapter-' + (++order)
      const title = `${chapterLabel(order)} ${chapter.title}`
      page(id, title, `<h1>${escape(title)}</h1>${chapterHtml(chapter.text)}`)
      return `<li><a href="${id}.xhtml">${escape(title)}</a></li>`
    }).join('')
    return chapters ? `<li><span>${escape(volume.title)}</span><ol>${chapters}</ol></li>` : ''
  }).join('')
  add('EPUB/toc.xhtml', documentXml('目录', `<nav epub:type="toc" id="toc"><h1>目录</h1><ol>${nav}</ol></nav>`))
  add('EPUB/book.opf', `<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="id" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">${escape('urn:qingjian:' + book.id)}</dc:identifier><dc:title>${escape(book.title)}</dc:title><dc:language>zh-CN</dc:language><meta property="dcterms:modified">${new Date().toISOString().replace(/\.\d{3}Z$/u,'Z')}</meta></metadata><manifest>${manifest.join('')}</manifest><spine>${spine.join('')}</spine></package>`)
  return zipSync(files)
}

export function exportBook(book, format) {
  if (format === 'json') return downloadBookBackup(book)
  if (format === 'epub') return downloadFile(book.title, new Blob([ebook(book)], { type: 'application/epub+zip' }), 'epub')
  const markdown = format === 'md'
  let index = 0
  const body = book.volumes.flatMap(volume => [`${markdown ? '## ' : ''}${volume.title}`, ...volume.chapters.map(chapter =>
    `${markdown ? '### ' : ''}${chapterLabel(++index)} ${chapter.title}\n\n${markdown ? manuscriptMarkdown(chapter.text) : chapter.text}`)])
  return downloadText(book.title, [`${markdown ? '# ' : ''}${book.title}`, ...body].join('\n\n'), markdown ? 'md' : 'txt')
}
