export async function readCharacterCard(file) {
  if (file.size > 12 * 1024 * 1024) throw new Error('角色卡不能超过 12 MB。')
  let json
  if (!/\.png$/iu.test(file.name)) json = await file.text()
  else {
    const bytes = new Uint8Array(await file.arrayBuffer())
    const view = new DataView(bytes.buffer)
    if (bytes.length < 8 || view.getUint32(0) !== 0x89504e47 || view.getUint32(4) !== 0x0d0a1a0a) throw new Error('角色卡 PNG 格式无效。')
    const cards = new Map()
    const decode = new TextDecoder()
    for (let at = 8; at + 12 <= bytes.length;) {
      const length = view.getUint32(at)
      if (length > bytes.length - at - 12) break
      const type = decode.decode(bytes.subarray(at + 4, at + 8))
      if (type === 'tEXt' && length < 1024 * 1024) {
        const data = decode.decode(bytes.subarray(at + 8, at + 8 + length))
        const separator = data.indexOf('\0')
        const key = data.slice(0, separator)
        if (['chara', 'ccv3'].includes(key)) cards.set(key, data.slice(separator + 1))
      }
      if (type === 'IEND') break
      at += length + 12
    }
    const encoded = cards.get('ccv3') || cards.get('chara')
    if (!encoded) throw new Error('图片内没有可读取的酒馆角色卡。')
    json = decode.decode(Uint8Array.from(atob(encoded), char => char.charCodeAt(0)))
  }
  const parsed = JSON.parse(json)
  const source = parsed?.data || parsed
  const text = (key, limit) => typeof source?.[key] === 'string' ? source[key].trim().slice(0, limit) : ''
  const name = text('name', 60)
  if (!name) throw new Error('角色卡缺少名称。')
  return { id: crypto.randomUUID(), name,
    description: [text('description', 3500), text('personality', 1200)].filter(Boolean).join('\n'),
    scene: text('scenario', 1800), greeting: text('first_mes', 3000) }
}
