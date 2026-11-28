package xyz.qingjian.writer;

import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.LinearLayout;
import java.util.function.Consumer;

/** Runs in the test APK's own process, which does not contain the app's Kotlin runtime. */
public final class WritingTestIme extends InputMethodService {
    @Override
    public boolean onEvaluateInputViewShown() {
        return true;
    }

    @Override
    public View onCreateInputView() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(key("测试组字", connection -> connection.setComposingText("qing", 1)));
        layout.addView(key("提交测试汉字", connection -> connection.commitText("青", 1)));
        layout.addView(key("输入测试长章", connection -> {
            connection.beginBatchEdit();
            try {
                connection.setSelection(0, "第一段。".length());
                connection.commitText(longChapterFixture(), 1);
            } finally {
                connection.endBatchEdit();
            }
        }));
        return layout;
    }

    private Button key(String label, Consumer<InputConnection> action) {
        Button button = new Button(this);
        button.setText(label);
        button.setFocusable(false);
        button.setOnClickListener(view -> {
            InputConnection connection = getCurrentInputConnection();
            if (connection != null) action.accept(connection);
        });
        return button;
    }

    public static String longChapterFixture() {
        StringBuilder text = new StringBuilder("她穿过薄雾。\n\n## 次日\n\n");
        for (int index = 1; index <= 400; index++) {
            text.append("第 ").append(index).append(" 段。渡船靠岸，远处的石堤浮在潮水上。\n\n");
        }
        return text.append("最后一段。").toString();
    }
}
