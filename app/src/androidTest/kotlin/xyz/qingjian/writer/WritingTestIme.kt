package xyz.qingjian.writer

import android.inputmethodservice.InputMethodService
import android.view.View
import android.widget.Button
import android.widget.LinearLayout

class WritingTestIme : InputMethodService() {
    override fun onEvaluateInputViewShown(): Boolean = true

    override fun onCreateInputView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(key("测试组字") { currentInputConnection?.setComposingText("qing", 1) })
        addView(key("提交测试汉字") { currentInputConnection?.commitText("青", 1) })
        addView(key("输入测试长章") {
            val connection = currentInputConnection ?: return@key
            connection.beginBatchEdit()
            try {
                connection.setSelection(0, "第一段。".length)
                connection.commitText(longChapterFixture(), 1)
            } finally {
                connection.endBatchEdit()
            }
        })
    }

    private fun key(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isFocusable = false
        setOnClickListener { action() }
    }
}

internal fun longChapterFixture() = buildString {
    append("她穿过薄雾。\n\n## 次日\n\n")
    repeat(400) { index -> append("第 ${index + 1} 段。渡船靠岸，远处的石堤浮在潮水上。\n\n") }
    append("最后一段。")
}
