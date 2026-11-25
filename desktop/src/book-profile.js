export function newBookProfile() {
  return {
    configured: false, mainCharacter: '', pointOfView: '第三人称有限', premise: '', theme: '',
    setting: '', genre: '', wordTarget: 80000, chapterTarget: 1500, extra: '', readerPromise: '', storyStages: '',
    referenceType: 'chapter', referenceChapterId: '', referenceText: '', referenceSnapshot: '',
    writingStyle: '',
    styleCorrection: '保留作者的视角、叙述节奏和对话习惯。收紧空泛评价与重复解释，避免套用套路化转折。',
  }
}

export function referenceFor(profile, entries) {
  if (profile.referenceType === 'paste') return profile.referenceText.trim()
  return entries.find(({ chapter }) => chapter.id === profile.referenceChapterId)?.chapter.text.trim()
    || profile.referenceSnapshot.trim()
}

export function excerpt(text, max) {
  if (text.length <= max) return text
  const part = Math.floor(max / 3)
  const middle = Math.floor((text.length - part) / 2)
  return [text.slice(0, part), text.slice(middle, middle + part), text.slice(-part)].join('\n…\n')
}

export function bookMaterial(book, entries, referenceMode = 'excerpt') {
  const profile = book.aiSetup
  const reference = referenceFor(profile, entries)
  return {
    书名: book.title,
    类别: profile.genre,
    全书目标字数: profile.wordTarget,
    单章目标字数: profile.chapterTarget,
    主角: profile.mainCharacter,
    叙事视角: profile.pointOfView,
    故事内核: profile.premise,
    主题: profile.theme,
    背景设定: profile.setting,
    写作文风: profile.writingStyle,
    文风纠偏: profile.styleCorrection,
    其他要求: profile.extra,
    读者期待: profile.readerPromise,
    阶段推进: profile.storyStages,
    参考章: referenceMode === 'chapter' ? excerpt(reference, 2400) : profile.writingStyle.trim() ? '' : excerpt(reference, 720),
  }
}
export const profileFields = {
  title: { label: '书名', max: 60 },
  genre: { label: '类别', max: 80 },
  wordTarget: { label: '全书目标字数', min: 1, max: 10000000 },
  chapterTarget: { label: '单章目标字数', min: 100, max: 12000 },
  mainCharacter: { label: '主角', max: 200 },
  pointOfView: { label: '叙事视角', max: 40 },
  premise: { label: '故事内核', max: 500 },
  theme: { label: '主题', max: 300 },
  setting: { label: '时代与世界背景', max: 550 },
  extra: { label: '其他创作要求', max: 500 },
  readerPromise: { label: '读者期待', max: 350 },
  storyStages: { label: '阶段推进', max: 550 },
}
