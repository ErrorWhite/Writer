import { reactive } from 'vue'
import { localRequest } from './local-service.js'

const defaults = { provider: 'auto', accent: '#3d62df', shelfLayout: 'cards', images: false }
export const preferences = reactive({ ...defaults })
const key = 'qingjian.studio-preferences'
try { Object.assign(preferences, JSON.parse(localStorage.getItem(key)) || {}) } catch { /* Use defaults. */ }

let writes = Promise.resolve()
let revision = 0
export async function loadPreferences() {
  const version = revision
  const saved = await localRequest('/api/preferences')
  if (revision === version) Object.assign(preferences, saved)
}

export function savePreferences(changes) {
  Object.assign(preferences, changes)
  revision++
  try { localStorage.setItem(key, JSON.stringify(preferences)) } catch { /* The service persists settings too. */ }
  const save = writes.then(() => localRequest('/api/preferences', { method: 'PUT', body: changes }))
  writes = save.catch(() => {})
  return save
}

export function applyAccent(accent) {
  if (!/^#[0-9a-f]{6}$/iu.test(accent)) return
  const root = document.documentElement
  const [r, g, b] = [1, 3, 5].map(index => parseInt(accent.slice(index, index + 2), 16))
  const max = Math.max(r, g, b)
  const spread = max - Math.min(r, g, b)
  const hue = !spread ? 222 : 60 * (max === r ? (g - b) / spread : max === g ? 2 + (b - r) / spread : 4 + (r - g) / spread)
  root.style.setProperty('--icon-rotation', `${hue - 227}deg`)
  root.style.setProperty('--icon-saturation', `${Math.min(1.35, spread / 160)}`)
  root.style.setProperty('--accent-hue', String((hue + 360) % 360))
  root.style.setProperty('--accent', accent)
  const linear = channel => {
    const s = channel / 255
    return s <= .04045 ? s / 12.92 : ((s + .055) / 1.055) ** 2.4
  }
  const luminance = linear(r) * .2126 + linear(g) * .7152 + linear(b) * .0722
  root.style.setProperty('--accent-on', luminance > .18 ? '#0b1019' : '#fff')
}
