package com.example.myskills;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.widget.EditText;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.myskills.fragments.HanziWriterFragment2;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 字典信息面板的端到端验证。
 *
 * 用 instrumentation 而不是 adb 驱动 UI：汉字无法通过 {@code adb shell input text}
 * 注入（KeyCharacterMap 对汉字返回 null，命令直接抛 NPE），设备上也没有中文输入法。
 *
 * 运行：./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4.class)
public class DictionaryPanelTest {

    /** 首次释放数据库要复制 24MB，超时给宽一点 */
    private static final long TIMEOUT_MS = 90_000L;
    private static final long POLL_INTERVAL_MS = 100L;

    private ActivityScenario<MainActivity> scenario;

    @Before
    public void setUp() {
        scenario = ActivityScenario.launch(MainActivity.class);
        showWriterFragment();
    }

    @After
    public void tearDown() {
        if (scenario != null) {
            scenario.close();
            scenario = null;
        }
    }

    /** 初始字「我」应当显示完整条目 */
    @Test
    public void showsEntryForInitialChar() {
        String text = awaitContains("部首：戈");
        assertTrue(text, text.contains("我"));
        // pinyin 带调号，优先于无调号的 py
        assertTrue(text, text.contains("wǒ"));
        assertTrue(text, text.contains("笔画：7"));
        assertTrue(text, text.contains("五笔：trnt"));
        assertTrue(text, text.contains("笔顺：3121534"));
        assertTrue("释义正文应当出现在面板里", text.contains("释义"));
    }

    /**
     * 回归：释义来自本地库，与笔画数据的 CDN 下载完全无关。
     *
     * 「鑫」不在 assets/hanzi 里，笔画要联网下载（断网即失败），
     * 但释义必须照样立刻显示 —— 这正是查询链路要解耦的原因。
     */
    @Test
    public void showsEntryForCharWithoutLocalStrokeData() {
        type("鑫");
        String text = awaitContains("部首：金");
        assertTrue(text, text.contains("笔画：24"));
        assertTrue(text, text.contains("五笔：qqqf"));
    }

    /**
     * 源数据缺字时的提示。
     *
     * 「分」是上游 SQL 本身就缺失的 137 个常用字之一（见 tools/xinhuazidian/README.md），
     * 拿它来验证未收录分支最直观。
     */
    @Test
    public void showsNotCollectedForMissingChar() {
        type("分");
        String text = awaitContains("字典中未收录");
        assertTrue(text, text.contains("「分」"));
    }

    /** 回归：快速连打时，先发起的查询不能覆盖后发起的结果 */
    @Test
    public void staleResultDoesNotOverwriteNewerOne() throws InterruptedException {
        type("鑫");
        type("我");
        awaitContains("部首：戈");
        // 等一拍，让「鑫」那次查询的迟到回调有机会执行
        Thread.sleep(1_000L);
        String text = panelText();
        assertTrue("面板被过期结果覆盖了:\n" + text, text.contains("部首：戈"));
        assertFalse(text, text.contains("部首：金"));
    }

    /** 回归：切走再切回来，数据库连接不能被关闭（HanziDictionary 不提供 close） */
    @Test
    public void worksAfterFragmentRecreation() {
        awaitContains("部首：戈");
        scenario.onActivity(activity -> activity.getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.content_frame, new Fragment())
                .commitNow());
        showWriterFragment();
        awaitContains("部首：戈");
    }

    // ============================================================
    //                        辅助方法
    // ============================================================

    private void showWriterFragment() {
        scenario.onActivity(activity -> activity.getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.content_frame, new HanziWriterFragment2())
                .commitNow());
    }

    /** 往输入框塞字，触发 TextWatcher 里的 requestCharacter */
    private void type(String text) {
        scenario.onActivity(activity -> {
            EditText editText = activity.findViewById(R.id.editText2);
            editText.setText(text);
        });
    }

    /** 轮询等待面板出现指定内容，超时则失败并打印实际内容 */
    private String awaitContains(String needle) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        String text = "";
        while (System.currentTimeMillis() < deadline) {
            text = panelText();
            if (text.contains(needle)) return text;
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("等待 \"" + needle + "\" 超时，面板实际内容:\n" + text);
    }

    private String panelText() {
        final String[] holder = new String[1];
        scenario.onActivity(activity -> {
            TextView tv = activity.findViewById(R.id.tvDictInfo);
            holder[0] = tv == null ? null : tv.getText().toString();
        });
        return holder[0] == null ? "" : holder[0];
    }
}
