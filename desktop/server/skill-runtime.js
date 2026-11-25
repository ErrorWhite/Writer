import variant from '@jitl/quickjs-singlefile-cjs-release-sync'
import { newQuickJSWASMModuleFromVariant, shouldInterruptAfterDeadline } from 'quickjs-emscripten-core'

let modulePromise
const text = (value, limit) => typeof value === 'string' ? value.slice(0, limit) : ''
const toolsSource = {
  wordCount: "value => Array.from(String(value).replace(/\\s/gu, '')).length",
  find: '(value, term) => String(term) ? String(value).indexOf(String(term)) : -1',
  readReference: 'path => Object.hasOwn(references, path) ? references[path] : null',
}

export async function runSkillScript(skill, input) {
  if (!skill || typeof skill.source !== 'string' || !skill.source.trim() || skill.source.length > 32000)
    throw new Error('技能入口需要一个 32000 字符以内的 JavaScript 文件。')
  const references = Object.fromEntries((Array.isArray(skill.references) ? skill.references : []).slice(0, 8)
    .filter(file => typeof file?.path === 'string').map(file => [text(file.path, 200), text(file.text, 2400)]))
  const tools = (Array.isArray(skill.tools) ? skill.tools : []).filter(name => typeof name === 'string' && Object.hasOwn(toolsSource, name))
  const toolEntries = [...new Set(tools)].map(name => `${name}: ${toolsSource[name]}`)
  const material = { text: text(input?.text, 16000), instruction: text(input?.instruction, 1800), task: text(input?.task, 80) }
  modulePromise ||= newQuickJSWASMModuleFromVariant(variant.default || variant)
  const wasm = await modulePromise
  const runtime = wasm.newRuntime()
  try {
    runtime.setMemoryLimit(8 * 1024 * 1024)
    runtime.setMaxStackSize(256 * 1024)
    runtime.setInterruptHandler(shouldInterruptAfterDeadline(Date.now() + 100))
    const context = runtime.newContext()
    try {
      const result = context.evalCode(`(() => {
        "use strict";
        const references = JSON.parse(${JSON.stringify(JSON.stringify(references))});
        const input = Object.freeze(${JSON.stringify(material)});
        const tools = Object.freeze({ ${toolEntries.join(', ')} });
        const runSkill = (function () {
          ${skill.source}
          return run;
        })();
        const result = runSkill(input, tools);
        if (result instanceof Promise) throw new Error("run 必须同步返回");
        return JSON.stringify(result);
      })()`, 'skill.js')
      if (result.error) {
        try { throw new Error('技能脚本执行失败或超过资源限制。') } finally { result.error.dispose() }
      }
      try {
        if (context.typeof(result.value) !== 'string') throw new Error('技能脚本必须返回可转换为 JSON 的结果。')
        const output = context.getString(result.value)
        if (output.length > 2500) throw new Error('技能脚本输出超过 2500 字符。')
        return JSON.parse(output)
      } finally { result.value.dispose() }
    } finally { context.dispose() }
  } finally { runtime.dispose() }
}

export async function skillFeedback(skills, material, intent, signal) {
  if (!Array.isArray(skills)) return []
  const feedback = []
  const input = {
    text: material.目标段落 || material.初稿 || material.章节摘要 ||
      (Array.isArray(material.审阅原文) ? material.审阅原文.slice(0, 25).map(part => part.text).join('\n') : ''),
    instruction: material.逐段提示 || material.任务, task: intent,
  }
  for (const skill of skills.slice(0, 4)) {
    if (signal?.aborted) break
    const name = text(skill?.name, 60)
    if (!name) continue
    try { feedback.push({ 技能: name, 结果: await runSkillScript(skill, input) }) }
    catch (error) { throw new Error(`「${name}」${error.message}`) }
  }
  return feedback
}
