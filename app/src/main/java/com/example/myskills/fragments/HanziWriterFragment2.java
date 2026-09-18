package com.example.myskills.fragments;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
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
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import com.example.myskills.R;
import com.example.myskills.data.HanziDictionary;
import com.example.myskills.data.HanziEntry;
import com.example.myskills.data.HanziStrokeSource;
import com.example.myskills.ui.util.LogUtil;
import com.example.myskills.ui.view.StrokeBreakdownView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class HanziWriterFragment2 extends Fragment {

    // ========== 成员变量 ==========
    private WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private String currentChar = "我";
    private final OkHttpClient httpClient = new OkHttpClient();
    private AssetManager assetManager;
    // 数据缓存目录：/data/data/包名/files/hanzi/
    private File cacheDir;
    private EditText editText;
    // 供 JS 桥在工作线程读数据用，不能依赖 requireContext()（此时可能已 detach）
    private Context appContext;

    // ========== 笔画拆解 ==========
    private HorizontalScrollView strokeBreakdownScroll;
    private StrokeBreakdownView strokeBreakdown;

    // ========== 字典信息面板 ==========
    private TextView tvDictInfo;
    private NestedScrollView dictScroll;
    private HanziDictionary dictionary;
    /**
     * 面板当前「属于」哪个字。
     * 不能用 currentChar 代替：currentChar 只在笔画数据到位后才更新，
     * 断网时它永远停在旧字上，会把新字的释义结果误判为过期而丢弃。
     */
    private String dictChar;
    /** onDestroyView 之后置位，用于丢弃已投递到主线程的迟到回调 */
    private boolean viewDestroyed;

    // ========== 生命周期：创建视图 ==========
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        return inflater.inflate(R.layout.fragment_hanzi_writer2, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 视图可能被重建，标志位要复位
        viewDestroyed = false;

        // 初始化缓存目录
        cacheDir = new File(requireContext().getFilesDir(), "hanzi");
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        assetManager = requireContext().getAssets();
        appContext = requireContext().getApplicationContext();
        initWebView(view);
        initButtons(view);

        tvDictInfo = view.findViewById(R.id.tvDictInfo);
        dictScroll = view.findViewById(R.id.dictScroll);
        strokeBreakdownScroll = view.findViewById(R.id.strokeBreakdownScroll);
        strokeBreakdown = view.findViewById(R.id.strokeBreakdown);
        // 预热：把十几 MB 的首次复制提前到用户还没输入时做
        dictionary = HanziDictionary.get(requireContext());
        dictionary.warmUp(null);
        lookupDictionary(currentChar);
        refreshStrokeBreakdown(currentChar);
    }

    // ========== 生命周期：销毁视图 ==========
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 先置位再清 Handler，中间投递进来的字典回调会被直接丢掉
        viewDestroyed = true;
        tvDictInfo = null;
        dictScroll = null;
        strokeBreakdownScroll = null;
        strokeBreakdown = null;
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.destroy();
            webView = null;
        }
        mainHandler.removeCallbacksAndMessages(null);
    }

    // ============================================================
    //                    初始化方法
    // ============================================================

    private void initWebView(View rootView) {
        editText = rootView.findViewById(R.id.editText2);
        webView = rootView.findViewById(R.id.hanziWebView);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowFileAccessFromFileURLs(true);
        webView.getSettings().setAllowUniversalAccessFromFileURLs(true);
        webView.getSettings().setDomStorageEnabled(true);

        // 注入 JavaScript 接口
        webView.addJavascriptInterface(new AndroidJSInterface(), "androidInterface");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 页面加载完成后初始化 Hanzi Writer
                initHanziWriter(currentChar);
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        webView.loadUrl("file:///android_asset/writer.html");
        editText.setOnEditorActionListener((v, actionId, event) -> {
            requestCharacter(v.getText().toString());
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
                LogUtil.i("beforeTextChanged str: " + s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
                LogUtil.i("afterTextChanged str: " + s.toString());
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
		 hideKeyboard();
        // 释义来自本地库，与笔画的 CDN 下载完全无关，所以单独发起、
        // 不等 preloadCharData 的结果。否则离线时绝大多数汉字的释义都会空白。
        lookupDictionary(hanzi);
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

    // ============================================================
    //              字典信息面板（新华字典本地库）
    // ============================================================

    /**
     * 查询并在面板显示释义。
     *
     * 必须由主线程调用（设置 dictChar 与状态文案）。
     */
    private void lookupDictionary(String hanzi) {
        if (dictionary == null) return;
        dictChar = hanzi;

        HanziDictionary.State state = dictionary.getState();
        if (state == HanziDictionary.State.FAILED) {
            setDictText("字典不可用");
            return;
        }
        if (state != HanziDictionary.State.READY) {
            // 首次释放数据库要复制十几 MB，期间用户可能已经输入了字
            setDictText("字典初始化中…");
        }

        dictionary.queryByZi(hanzi, entry -> mainHandler.post(() -> {
            // 双重防护：viewDestroyed/tvDictInfo 挡「对象已死」，
            // dictChar 挡「内容过期」（快速输入时旧结果晚于新结果返回）
            if (viewDestroyed || tvDictInfo == null) return;
            if (!hanzi.equals(dictChar)) return;
            showDictionary(hanzi, entry);
        }));
    }

    private void showDictionary(String hanzi, HanziEntry entry) {
        if (entry == null) {
            // 源数据含大量生僻字，未收录属正常情况
            setDictText("字典中未收录「" + hanzi + "」");
            return;
        }
        setDictText(entry.toDisplayText());
        // 换字后回到顶部，否则会停在上一字的滚动位置
        if (dictScroll != null) dictScroll.scrollTo(0, 0);
    }

    private void setDictText(String text) {
        if (viewDestroyed || tvDictInfo == null) return;
        tvDictInfo.setText(text);
    }

    /**
     * 切换汉字（非汉字输入会被忽略）
     */
    public void switchCharacter(String charStr) {
        String hanzi = extractHanzi(charStr);
        if (hanzi == null || webView == null) return;
        this.currentChar = hanzi;
        String js = String.format("setCharacter('%s', { strokeColor: '#333333' });", hanzi);
        webView.evaluateJavascript(js, null);
        refreshStrokeBreakdown(hanzi);
    }

    public void animateCharacter() {
        if (webView == null) return;
        webView.evaluateJavascript("animateCharacter({ duration: 800 });", null);
    }

    public void loopCharacter() {
        if (webView == null) return;
        webView.evaluateJavascript("loopCharacterAnimation({ delayBetweenLoops: 1000 });", null);
    }

    public void stopAnimation() {
        if (webView == null) return;
        webView.evaluateJavascript("stopAnimation();", null);
    }

    public void startQuiz() {
        if (webView == null) return;
        webView.evaluateJavascript("startQuiz({ showHintAfterMisses: 3 });", null);
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
     * 可在切换汉字前调用，确保缓存存在
     */
    public void preloadCharData(String charStr, Runnable onComplete) {
        File cacheFile = new File(cacheDir, charStr + ".json");
        if (cacheFile.exists()) {
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
                    Toast.makeText(requireContext(), "下载 " + charStr + " 失败", Toast.LENGTH_SHORT).show();
                    if (onComplete != null) onComplete.run();
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    try (InputStream is = response.body().byteStream()) {
                        // 保存到缓存文件
                        FileOutputStream fos = new FileOutputStream(cacheFile);
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = is.read(buffer)) != -1) {
                            fos.write(buffer, 0, len);
                        }
                        fos.close();
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
                        Toast.makeText(requireContext(), "HTTP 错误: " + response.code(), Toast.LENGTH_SHORT).show();
                        if (onComplete != null) onComplete.run();
                    });
                }
            }
        });
    }

    /**
     * 切换汉字时，先预下载，完成后再通知 JS 切换
     */
    public void switchCharacterWithPreload(String charStr) {
        preloadCharData(charStr, () -> {
            // 下载完成后切换
            switchCharacter(charStr);
        });
    }

    // ============================================================
    //              笔画拆解
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

    // ============================================================
    //              可选：为方便使用，重写 onViewCreated 预加载初始汉字
    // ============================================================
    // 可在 onViewCreated 中调用 preloadCharData(currentChar, null) 提前下载

}
