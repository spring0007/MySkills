package com.example.myskills;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.myskills.data.HanziStrokeSource;
import com.example.myskills.fragments.HanziWriterFragment;
import com.example.myskills.fragments.HanziWriterFragment2;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 笔画拆解行的端到端验证：N 笔就该有 N 个田字格，且格子总宽按内容算（超出可视宽度即可横向滚动）。
 *
 * 用 instrumentation 驱动而不是 adb：汉字注入不了 {@code adb shell input text}
 * （KeyCharacterMap 对汉字返回 null），设备上也没有中文输入法。同 DictionaryPanelTest。
 *
 * 运行：./gradlew connectedDebugAndroidTest
 *
 * 注意：AGP 在 connectedAndroidTest 跑完后会卸载应用，files/hanzi 下载缓存与字典库都会丢。
 */
@RunWith(AndroidJUnit4.class)
public class StrokeBreakdownTest {

    /** 「鑫」要联网下载笔画数据，超时给宽一点 */
    private static final long TIMEOUT_MS = 90_000L;
    private static final long POLL_INTERVAL_MS = 100L;

    private ActivityScenario<MainActivity> scenario;

    @Before
    public void setUp() {
        scenario = ActivityScenario.launch(MainActivity.class);
        showFragment(new HanziWriterFragment2());
    }

    @After
    public void tearDown() {
        if (scenario != null) {
            scenario.close();
            scenario = null;
        }
    }

    /** 内置字「我」：7 笔，离线可用 */
    @Test
    public void showsOneCellPerStroke() {
        type("我");
        awaitCellCount(7);
        assertVisible(true);
    }

    /**
     * 非内置字走的是 files/hanzi 那条查找分支（上一个用例只覆盖了 assets 分支）。
     * 装完就测的话本地还没有，会先下载再落盘，正好把「下载 → 写缓存 → 读缓存」整条链都过一遍。
     */
    @Test
    public void showsOneCellPerStrokeForCachedChar() {
        type("发");
        awaitCellCount(awaitStrokeCount("发"));
        assertVisible(true);
    }

    /** 笔画多的字：格子数正确，且总宽超过可视宽度 —— 这正是 HorizontalScrollView 存在的理由 */
    @Test
    public void wideRowScrollsHorizontally() {
        type("鑫");
        // 鑫不在本地，要走 CDN 下载，笔数只能等下载落地后再读
        int strokes = awaitStrokeCount("鑫");
        assertEquals("「鑫」的笔画数据笔数与字典不一致", 24, strokes);
        awaitCellCount(strokes);
        assertVisible(true);
        scenario.onActivity(activity -> {
            HorizontalScrollView scroll = activity.findViewById(R.id.strokeBreakdownScroll);
            View row = activity.findViewById(R.id.strokeBreakdown);
            assertTrue("24 格应当超出可视宽度，否则横向滚动没有意义",
                    row.getWidth() > scroll.getWidth());
        });
        // 24 格是「格子够多时仍然排得下」的唯一一次真实取证，留两张图：行首 + 行尾
        captureScreenshot("stroke_24_head.png");
        // 滚到行尾，末格（第 24 格）应当就是整字，与 WebView 里的静态字一致
        scenario.onActivity(activity -> ((HorizontalScrollView)
                activity.findViewById(R.id.strokeBreakdownScroll)).fullScroll(View.FOCUS_RIGHT));
        sleep(500);   // 等一次布局 + 绘制
        captureScreenshot("stroke_24_tail.png");
    }

    /** 回归：快速换字时，旧字的格子数不能残留（24 格显示成 7 格就是串字了） */
    @Test
    public void staleCharDoesNotLeakIntoBreakdown() {
        type("鑫");
        awaitCellCount(awaitStrokeCount("鑫"));
        type("我");
        awaitCellCount(7);
    }

    /** 回归：切走再切回，自绘 View 要能重建 */
    @Test
    public void keepsWorkingAfterFragmentRecreation() {
        type("我");
        awaitCellCount(7);
        scenario.onActivity(activity -> activity.getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.content_frame, new Fragment())
                .commitNow());
        showFragment(new HanziWriterFragment2());
        type("我");
        awaitCellCount(7);
    }

    /** 另一个页面（无释义面板的那个）同样要有拆解行 */
    @Test
    public void showsBreakdownOnFirstWriterPage() {
        showFragment(new HanziWriterFragment());
        type("我");
        awaitCellCount(7);
        assertVisible(true);
    }

    /**
     * 本地没有该字数据时返回 null，不抛异常。
     * 「龘」既不在 assets 也不在下载缓存里，结果与网络无关，是确定性的。
     */
    @Test
    public void loadStrokesReturnsNullWhenNoLocalData() {
        Context context = ApplicationProvider.getApplicationContext();
        assertNull(HanziStrokeSource.loadStrokes(context, "龘"));
        assertNotNull(HanziStrokeSource.loadStrokes(context, "我"));
    }

    /**
     * 断网 + 输入本地没有数据的字：不崩，释义照常（释义来自本地库，与笔画下载无关），
     * 拆解行原样停在上一个字。
     *
     * 为什么是「停住」而不是「整行隐藏」：{@code switchCharacter} 只在数据到位后才被调用，
     * 下载失败时它压根不会被调到，所以 {@code refreshStrokeBreakdown} 不会执行 —— 行保持不动。
     * 这与 WebView 的表现一致（它同样切不过去），两边不会一个显示新字一个显示旧字。
     *
     * 需要设备真断网，联网跑会被 Assume 跳过：
     *   adb shell svc wifi disable && adb shell svc data disable
     */
    @Test
    public void offlineUncachedCharKeepsRowAndDictionaryInSync() {
        assumeTrue("本用例需要断网：adb shell svc wifi disable && adb shell svc data disable",
                !isOnline());

        type("我");
        awaitCellCount(7);
        awaitDictContains("部首：戈");

        type("湖");                 // 本地没有 湖.json，断网时下载必然失败
        awaitDictContains("湖");     // 释义与下载解耦，应当照常更新
        sleep(5_000);               // 等过「下载失败」那个时点，确认之后什么也没发生

        int expected = expectedWidth(7);
        scenario.onActivity(activity -> {
            HorizontalScrollView scroll = activity.findViewById(R.id.strokeBreakdownScroll);
            View row = activity.findViewById(R.id.strokeBreakdown);
            assertEquals("断网不该把已经画好的拆解行藏起来", View.VISIBLE, scroll.getVisibility());
            assertEquals("断网后拆解行应当原样停在「我」（7 格），既不是 湖 的笔数也不是空的",
                    expected, row.getWidth());
        });
        // 留一张图：释义已经是「湖」，拆解行还是「我」——这个状态用文字描述不如看图
        captureScreenshot("offline_uncached.png");
    }

    // ============================================================
    //                        辅助方法
    // ============================================================

    private void showFragment(Fragment fragment) {
        scenario.onActivity(activity -> activity.getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.content_frame, fragment)
                .commitNow());
    }

    /** 往输入框塞字，触发 TextWatcher 里的 requestCharacter */
    private void type(String text) {
        scenario.onActivity(activity -> {
            EditText editText = activity.findViewById(R.id.editText2) != null
                    ? activity.findViewById(R.id.editText2)
                    : activity.findViewById(R.id.editText);
            editText.setText(text);
        });
    }

    /**
     * 等笔画数据落到本地再读笔数。
     * 非内置字要联网下载，而下载是异步的 —— 输完字就立刻读会读到 null，
     * 且会随用例执行顺序时好时坏（前一个用例下载过就恰好能读到）。这里轮询掉这个不确定性。
     */
    private int awaitStrokeCount(String zi) {
        Context context = ApplicationProvider.getApplicationContext();
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        String[] strokes = null;
        while (System.currentTimeMillis() < deadline) {
            strokes = HanziStrokeSource.loadStrokes(context, zi);
            if (strokes != null && strokes.length > 0) return strokes.length;
            sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("等待「" + zi + "」的笔画数据超时，数据没下载到本地");
    }

    /** 把整屏截图写到外部私有目录，供 adb pull 出来人工看（沿用 adb 直跑测试以免卸载清目录） */
    private void captureScreenshot(String name) {
        Bitmap bitmap = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().takeScreenshot();
        File dir = new File(ApplicationProvider.getApplicationContext()
                .getExternalFilesDir(null), "shots");
        if (!dir.exists() && !dir.mkdirs()) return;
        try (FileOutputStream fos = new FileOutputStream(new File(dir, name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
        } catch (IOException e) {
            throw new RuntimeException("截图写入失败: " + name, e);
        }
    }

    /** 轮询等待格子数就位：格子数由 View 的实测宽度反推（n*格边长 + (n-1)*格间距） */
    private void awaitCellCount(int n) {
        int expected = expectedWidth(n);
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        int actual = -1;
        while (System.currentTimeMillis() < deadline) {
            actual = rowWidth();
            if (actual == expected) return;
            sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("等待 " + n + " 格超时：期望宽度 " + expected + "px，实际 " + actual + "px");
    }

    private int expectedWidth(int n) {
        return n * dimen(R.dimen.stroke_cell_size) + (n - 1) * dimen(R.dimen.stroke_cell_gap);
    }

    /** 等释义面板出现指定文字（释义走本地库，与笔画下载解耦） */
    private void awaitDictContains(String needle) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        String text = null;
        while (System.currentTimeMillis() < deadline) {
            final String[] holder = new String[1];
            scenario.onActivity(activity -> {
                TextView tv = activity.findViewById(R.id.tvDictInfo);
                holder[0] = tv == null ? null : tv.getText().toString();
            });
            text = holder[0];
            if (text != null && text.contains(needle)) return;
            sleep(POLL_INTERVAL_MS);
        }
        throw new AssertionError("等待释义包含「" + needle + "」超时，当前内容:\n" + text);
    }

    /**
     * 断网用例的前置判断：CDN 域名能不能解析。
     * 不去用 ConnectivityManager —— 那要 ACCESS_NETWORK_STATE 权限，App 本身不检查网络，
     * 没必要为测试往生产 manifest 里塞权限。而断网时下载真正挂掉的那一步就是 DNS 解析。
     */
    private boolean isOnline() {
        try {
            return InetAddress.getAllByName("cdn.jsdelivr.net").length > 0;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private int rowWidth() {
        final int[] holder = new int[1];
        scenario.onActivity(activity -> {
            View row = activity.findViewById(R.id.strokeBreakdown);
            holder[0] = row == null ? -1 : row.getWidth();
        });
        return holder[0];
    }

    private void assertVisible(boolean visible) {
        scenario.onActivity(activity -> {
            View scroll = activity.findViewById(R.id.strokeBreakdownScroll);
            assertEquals("笔画拆解行的可见性与预期不符",
                    visible ? View.VISIBLE : View.GONE, scroll.getVisibility());
        });
    }

    private int dimen(int resId) {
        Resources res = ApplicationProvider.getApplicationContext().getResources();
        return res.getDimensionPixelSize(resId);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待被中断", e);
        }
    }
}
