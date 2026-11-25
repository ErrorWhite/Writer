<script setup>
import { computed, ref } from 'vue'
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { manuscriptMarkdown } from './scene-dividers.js'

const props = defineProps({
  source: { type: String, default: '' },
  manuscript: Boolean,
  outline: Boolean,
  dividerStyle: { type: String, default: 'ornament' },
  token: { type: Object, default: null },
  editableCode: Boolean,
})
const emit = defineEmits(['edit-code'])
const content = ref()
const logLevels = { TRACE: 'trace', DEBUG: 'trace', INFO: 'info', WARN: 'warning', WARNING: 'warning', ERROR: 'error', FATAL: 'error', OK: 'ok', SUCCESS: 'ok' }

function decorateCode(fragment) {
  for (const code of fragment.querySelectorAll('pre > code')) {
    const pre = code.parentElement
    const language = [...code.classList].find(name => name.startsWith('language-'))?.slice(9) || 'text'
    const plainText = ['text', 'txt', 'plaintext', 'log'].includes(language.toLowerCase())
    const figure = document.createElement('figure')
    figure.className = plainText ? 'markdown-code markdown-code-text' : 'markdown-code'
    const header = document.createElement('figcaption')
    const name = document.createElement('span')
    name.textContent = language
    const copy = document.createElement('button')
    copy.type = 'button'
    copy.className = 'markdown-copy-code'
    copy.textContent = '复制'
    copy.setAttribute('aria-label', '复制代码')
    const buttons = document.createElement('span')
    buttons.className = 'markdown-code-actions'
    if (props.editableCode) {
      const edit = document.createElement('button')
      edit.type = 'button'
      edit.className = 'markdown-edit-code'
      edit.textContent = '编辑'
      edit.setAttribute('aria-label', '编辑代码块')
      buttons.append(edit)
    }
    buttons.append(copy)
    header.append(name, buttons)
    pre.replaceWith(figure)
    figure.append(header, pre)
    if (!plainText) continue
    const plain = code.textContent
    code.replaceChildren()
    plain.split(/(?<=\n)/u).forEach(line => {
      const match = line.match(/^(\s*)(\[([A-Z]+)\])/u)
      const level = match && logLevels[match[3]]
      if (!level) { code.append(line); return }
      const badge = document.createElement('span')
      badge.className = `code-log-${level}`
      badge.textContent = match[2]
      code.append(match[1], badge, line.slice(match[0].length))
    })
  }
}

const rendered = computed(() => {
  const source = props.manuscript ? manuscriptMarkdown(props.source) : props.source
  const options = { async: false, gfm: true, breaks: props.manuscript }
  const html = props.token ? marked.Parser.parse([props.token], options) : marked.parse(source, options)
  const fragment = DOMPurify.sanitize(html, {
    RETURN_DOM_FRAGMENT: true,
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['img', 'picture', 'style', 'form', 'iframe', 'video', 'audio'],
    FORBID_ATTR: ['style', 'id', 'name', 'target'],
  })
  decorateCode(fragment)
  const headings = []
  if (props.outline) {
    fragment.querySelectorAll('h1,h2,h3,h4,h5,h6').forEach((heading, index) => {
      heading.dataset.markdownHeading = String(index)
      headings.push({ index, level: Number(heading.tagName.slice(1)), title: heading.textContent })
    })
  }
  const container = document.createElement('div')
  container.append(fragment)
  return { html: container.innerHTML, headings }
})

function jump(index) {
  content.value?.querySelector(`[data-markdown-heading="${index}"]`)?.scrollIntoView({ block: 'start' })
}

async function copyCode(event) {
  if (props.editableCode && event.target.closest('button.markdown-edit-code')) { emit('edit-code'); return }
  const button = event.target.closest('button.markdown-copy-code')
  const code = button?.closest('figure.markdown-code')?.querySelector('pre > code')
  if (!code) return
  try {
    await navigator.clipboard.writeText(code.textContent)
    button.textContent = '已复制'
  } catch {
    const selection = document.getSelection()
    const range = document.createRange()
    range.selectNodeContents(code)
    selection?.removeAllRanges()
    selection?.addRange(range)
    if (document.execCommand('copy')) {
      selection?.removeAllRanges()
      button.textContent = '已复制'
    } else {
      button.textContent = '已选中'
    }
  }
  setTimeout(() => { button.textContent = '复制' }, 1400)
}
</script>

<template>
  <nav v-if="rendered.headings.length" class="markdown-outline" aria-label="Markdown 文档目录">
    <button v-for="heading in rendered.headings" :key="heading.index" class="text-action"
      :style="{ paddingInlineStart: Math.max(0, heading.level - 2) * 9 + 'px' }" @click="jump(heading.index)">{{ heading.title }}</button>
  </nav>
  <p v-if="!source.trim()" class="resource-empty">暂无内容。</p>
  <div v-else ref="content" class="markdown-content" :class="[`markdown-divider-${dividerStyle}`, { 'markdown-manuscript': manuscript }]" v-html="rendered.html" @click="copyCode"></div>
</template>
