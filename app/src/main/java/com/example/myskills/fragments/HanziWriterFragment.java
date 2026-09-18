package com.example.myskills.fragments;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.bihe0832.android.lib.aaf.tools.AAFException;
import com.bihe0832.android.lib.pinyin.PinYinWithTone;
import com.bihe0832.android.lib.pinyin.PinyinFormat;
import com.example.myskills.R;
import com.example.myskills.data.HanziStrokeSource;
import com.example.myskills.ui.util.LogUtil;
import com.example.myskills.ui.view.StrokeBreakdownView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class HanziWriterFragment extends Fragment {

    // ========== 成员变量 ==========
    private WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String currentChar = "我";
    private final OkHttpClient httpClient = new OkHttpClient();
    private AssetManager assetManager;
    // 数据缓存目录：/data/data/包名/files/hanzi/
    private File cacheDir;
    private EditText editText;
    private TextView pinyinTextView;
    // 供 JS 桥在工作线程读数据用，不能依赖 requireContext()（此时可能已 detach）
    private Context appContext;

    // ========== 笔画拆解 ==========
    private HorizontalScrollView strokeBreakdownScroll;
    private StrokeBreakdownView strokeBreakdown;

    // ========== 生命周期：创建视图 ==========
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_hanzi_writer, container, false);
    }

    // ========== 生命周期：视图创建完成 ==========
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // 初始化缓存目录与 assets
        cacheDir = new File(requireContext().getFilesDir(), "hanzi");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        assetManager = requireContext().getAssets();
        appContext = requireContext().getApplicationContext();
        PinYinWithTone.init(requireContext());
        initWebView(view);
        initButtons(view);

        strokeBreakdownScroll = view.findViewById(R.id.strokeBreakdownScroll);
        strokeBreakdown = view.findViewById(R.id.strokeBreakdown);
        refreshStrokeBreakdown(currentChar);
    }

    // ========== 生命周期：销毁视图（关键：清理 WebView 防止内存泄漏） ==========
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (webView != null) {
            // 1. 从父布局移除
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }
            // 2. 清空加载状态
            webView.loadUrl("about:blank");
            webView.clearHistory();
            // 3. 销毁 WebView
            webView.destroy();
            webView = null;
        }
        strokeBreakdownScroll = null;
        strokeBreakdown = null;
        // 移除 Handler 中的未执行任务，防止内存泄漏
        mainHandler.removeCallbacksAndMessages(null);
    }

    private void initWebView(View rootView) {
        editText = rootView.findViewById(R.id.editText);
        webView = rootView.findViewById(R.id.hanziWebView);
        pinyinTextView = rootView.findViewById(R.id.pinyin);

        // 启用 JavaScript
        webView.getSettings().setJavaScriptEnabled(true);
        // 允许从 file:// 协议加载 assets 资源
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowFileAccessFromFileURLs(true);
        webView.getSettings().setAllowUniversalAccessFromFileURLs(true);
        webView.getSettings().setDomStorageEnabled(true);

        // 注入 JavaScript 接口：数据加载职责由 Java 端承担
        webView.addJavascriptInterface(new AndroidJSInterface(), "androidInterface");

        // 设置客户端
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 页面加载完成后初始化 Hanzi Writer
                initHanziWriter(currentChar);
                setPinYin(currentChar);

            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        // 加载本地 HTML
        webView.loadUrl("file:///android_asset/writer.html");
        initEditText();
    }
    private void setPinYin(String hanzi)  {
        String pinyin = "";
        try {
            pinyin = PinYinWithTone.toPinYin(hanzi, "", true);
        } catch (AAFException e) {
            throw new RuntimeException(e);
        }
        if(!TextUtils.isEmpty(pinyin)) {
            pinyinTextView.setVisibility(View.VISIBLE);
            pinyinTextView.setText("拼音 : "+pinyin);
            LogUtil.i("pinyin: " + pinyin);
        } else {
            pinyinTextView.setVisibility(View.GONE);
        }
    }

    /**
     * 输入框：输入汉字后切换显示（自动过滤拼音组合态等非汉字输入）
     */
    private void initEditText() {
        editText.setOnEditorActionListener((v, actionId, event) -> {
            requestCharacter(v.getText().toString());
            // 回车/完成键确认后，即使没有可切换的汉字也收起键盘
            hideKeyboard();
            LogUtil.i("setOnEditorActionListener str: " + v.getText().toString());
            return true;
        });
        editText.setShowSoftInputOnFocus(true);
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                requestCharacter(s.toString());
                LogUtil.i("onTextChanged str: " + s.toString());
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        // 焦点监听：获取焦点时弹出软键盘，失去焦点时收起软键盘
        editText.setOnFocusChangeListener((v, hasFocus) -> {
            InputMethodManager imm = (InputMethodManager) requireContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return;
            if (hasFocus) {
                // 延迟 100ms：等待焦点切换动画完成，避免与系统默认弹出行为竞争导致键盘不显示
                v.postDelayed(() -> imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT), 100);
            } else {
                // 失去焦点：无条件收起软键盘
                imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            }
        });
    }
    /**
     * 收起软键盘并清除输入框焦点（输入完成后调用）
     * 注意顺序：先 hide 再 clearFocus；clearFocus 会再次触发焦点监听的收起逻辑，无副作用
     */
    private void hideKeyboard() {
        if (editText == null) return;
        InputMethodManager imm = (InputMethodManager) requireContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(editText.getWindowToken(), 0);
        }
        editText.clearFocus();
    }

    private void initButtons(View rootView) {
        Button btnAnimate = rootView.findViewById(R.id.btnAnimate);
        Button btnLoop = rootView.findViewById(R.id.btnLoop);
        Button btnStop = rootView.findViewById(R.id.btnStop);
        Button btnQuiz = rootView.findViewById(R.id.btnQuiz);

        btnAnimate.setOnClickListener(v -> animateCharacter());
        btnLoop.setOnClickListener(v -> loopCharacter());
        btnStop.setOnClickListener(v -> stopAnimation());
        btnQuiz.setOnClickListener(v -> startQuiz());
    }


    private void initHanziWriter(String charStr) {
        if (webView == null) return;
        String js = String.format(
                "initWriter('%s', %d, %d, %d, { strokeColor: '#333333', showOutline: true });",
                charStr, 200, 200, 20
        );
        webView.evaluateJavascript(js, null);
    }

    /**
     * 输入框回调：过滤非汉字输入后请求切换
     */
    private void requestCharacter(String text) {
        String hanzi = extractHanzi(text);
        if (hanzi == null) return;
        // 汉字已上屏，视为输入完成：收起键盘
        hideKeyboard();
        if (hasLocalData(hanzi)) {
            switchCharacter(hanzi);
        } else {
            // 本地无数据：先联网预下载，成功后切换
            preloadCharData(hanzi, () -> {
                if (hasLocalData(hanzi)) {
                    switchCharacter(hanzi);
                }
            });
        }
    }

    /**
     * 提取输入中的第一个汉字，过滤空串、拼音字母等无效输入
     */
    private static String extractHanzi(String text) {
        if (text == null) return null;
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) {
                return String.valueOf(text.charAt(i));
            }
        }
        return null;
    }

    /**
     * 判断 assets 或缓存目录中是否已有该字数据
     */
    private boolean hasLocalData(String charStr) {
        if (assetManager != null) {
            try (InputStream is = assetManager.open("hanzi/" + charStr + ".json")) {
                return true;
            } catch (IOException ignored) {
            }
        }
        return cacheDir != null && new File(cacheDir, charStr + ".json").exists();
    }

    /**
     * 对外公开：切换汉字（非汉字输入会被忽略）
     */
    public void switchCharacter(String charStr) {
        String hanzi = extractHanzi(charStr);
        if (hanzi == null || webView == null) return;
        this.currentChar = hanzi;
        String js = String.format("setCharacter('%s', { strokeColor: '#333333' });", hanzi);
        webView.evaluateJavascript(js, null);
        setPinYin(hanzi);
        refreshStrokeBreakdown(hanzi);
    }

    /**
     * 播放动画
     */
    public void animateCharacter() {
        if (webView == null) return;
        webView.evaluateJavascript("animateCharacter({ duration: 800 });", null);
    }

    /**
     * 循环播放动画
     */
    public void loopCharacter() {
        if (webView == null) return;
        webView.evaluateJavascript("loopCharacterAnimation({ delayBetweenLoops: 1000 });", null);
    }

    /**
     * 停止动画（JS 侧会取消动画并回到静态显示）
     */
    public void stopAnimation() {
        if (webView == null) return;
        webView.evaluateJavascript("stopAnimation();", null);
    }

    /**
     * 开始测验模式
     */
    public void startQuiz() {
        if (webView == null) return;
        webView.evaluateJavascript("startQuiz({ showHintAfterMisses: 3 });", null);
    }

    /**
     * 获取当前状态（示例：通过 Toast 展示）
     */
    public void getStatus() {
        if (webView == null) return;
        webView.evaluateJavascript("getStatus();", value -> {
            try {
                JSONObject status = new JSONObject(value);
                boolean isReady = status.getBoolean("isReady");
                String current = status.getString("currentChar");
                Toast.makeText(requireContext(), "就绪: " + isReady + ", 汉字: " + current, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    // ============================================================
    //          Android → JS 数据提供接口（核心）
    // ============================================================

    /**
     * JavaScript 接口，供 HTML 调用获取汉字 JSON 数据
     */
    private class AndroidJSInterface {

        @JavascriptInterface
        public String getCharData(String charStr) {
            // 查找顺序（assets 优先，其次下载缓存）收敛在 HanziStrokeSource 里，
            // 与笔画拆解共用同一份数据来源。返回 null 表示本地无数据，JS 侧提示加载失败。
            return HanziStrokeSource.readCharJson(appContext, charStr);
        }
    }

    // ============================================================
    //              下载与缓存管理（预下载）
    // ============================================================

    /**
     * 预下载指定汉字的 JSON 数据（异步），下载完成后回调
     * 本地已有数据时直接回调
     */
    public void preloadCharData(String charStr, Runnable onComplete) {
        File cacheFile = new File(cacheDir, charStr + ".json");
        if (hasLocalData(charStr)) {
            if (onComplete != null) onComplete.run();
            return;
        }

        String url = "https://cdn.jsdelivr.net/npm/hanzi-writer-data@latest/" + charStr + ".json";
        Request request = new Request.Builder().url(url).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                e.printStackTrace();
                // 下载失败，在主线程回调
                mainHandler.post(() -> {
                    if (isAdded()) {
                        Toast.makeText(requireContext(), "下载 " + charStr + " 失败", Toast.LENGTH_SHORT).show();
                    }
                    if (onComplete != null) onComplete.run();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    try (InputStream is = response.body().byteStream();
                         FileOutputStream fos = new FileOutputStream(cacheFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = is.read(buffer)) != -1) {
                            fos.write(buffer, 0, len);
                        }
                        mainHandler.post(() -> {
                            if (onComplete != null) onComplete.run();
                        });
                    } catch (Exception e) {
                        e.printStackTrace();
                        mainHandler.post(() -> {
                            if (onComplete != null) onComplete.run();
                        });
                    }
                } else {
                    mainHandler.post(() -> {
                        if (isAdded()) {
                            Toast.makeText(requireContext(), "HTTP 错误: " + response.code(), Toast.LENGTH_SHORT).show();
                        }
                        if (onComplete != null) onComplete.run();
                    });
                }
            }
        });
    }

    // ============================================================
    //              工具方法
    // ============================================================

    /**
     * 刷新笔画拆解行。本字笔画的 JSON 不在本地就整行隐藏
     * —— 与笔顺动画失败是同一个原因（要联网下载数据），不额外引入新的失败模式。
     */
    private void refreshStrokeBreakdown(String hanzi) {
        if (strokeBreakdownScroll == null || strokeBreakdown == null) return;
        String[] strokes = HanziStrokeSource.loadStrokes(requireContext(), hanzi);
        if (strokes == null || strokes.length == 0) {
            strokeBreakdown.setStrokes(null);
            strokeBreakdownScroll.setVisibility(View.GONE);
            return;
        }
        strokeBreakdown.setStrokes(strokes);
        strokeBreakdownScroll.setVisibility(View.VISIBLE);
    }
}