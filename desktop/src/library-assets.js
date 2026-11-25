export const assetTypes = { image: '图片', markdown: '文档' }
export const defaultAssetGroups = ['人物', '场景', '道具', '世界设定']
export const assetLimit = 640 * 1024
export const libraryLimit = 2 * 1024 * 1024
const imagePattern = /^data:image\/(?:png|jpeg|webp|gif);base64,[A-Za-z0-9+/]+=*$/u

export const assetSize = data => new TextEncoder().encode(data).length
export function validAssetData(type, data) {
  return typeof data === 'string' && data.length <= assetLimit &&
    (type === 'image' ? imagePattern.test(data) : type === 'markdown' && assetSize(data) <= assetLimit)
}

// The name/group/search/sort flow follows Orison Space's AssetsPanel.
export function groupLibraryAssets(assets, query, group, sort) {
  const needle = query.trim().toLowerCase()
  const grouped = new Map()
  const ordered = [...assets].sort(sort === 'time'
    ? (a, b) => b.updatedAt - a.updatedAt : (a, b) => a.title.localeCompare(b.title, 'zh-CN'))
  for (const asset of ordered) {
    if (group && asset.group !== group) continue
    if (needle && ![asset.title, asset.group, asset.summary].some(value => value.toLowerCase().includes(needle))) continue
    const records = grouped.get(asset.group) || []
    records.push(asset)
    grouped.set(asset.group, records)
  }
  return [...grouped].sort(([left], [right]) => left && right ? left.localeCompare(right, 'zh-CN') : Number(!left) - Number(!right))
}
