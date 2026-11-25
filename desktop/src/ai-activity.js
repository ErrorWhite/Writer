import { reactive } from 'vue'

export const aiActivity = reactive({ id: 0, intent: '', stage: '', preview: '', summary: '', pending: false })
export const aiIntentLabels = {
  roleplay: '文本扮演',
  paragraph:'逐段改稿', 'paragraph-polish':'逐段复审', batch:'批量改稿', 'chapter-rewrite':'整章改稿', chapter:'生成初稿', summary:'生成摘要',
  volume:'规划下一卷', outline:'生成大纲', settings:'生成设定', 'profile-suggest':'补全作品信息',
  'library-extract':'整理知识库', style:'提炼文风', context:'压缩上下文', image:'生成配图', 'chapter-close':'整理定稿交接',
}
