package com.example.myskills.data;

import android.content.Context;
import android.content.res.AssetManager;

import com.example.myskills.ui.util.LogUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 汉字笔画数据的本地来源。
 *
 * 数据落在两处，查找顺序与 WebView 侧的 getCharData 保持一致：
 * 1. assets/hanzi/&lt;字&gt;.json —— 随包内置（目前只有「我」）
 * 2. files/hanzi/&lt;字&gt;.json —— 联网预下载的缓存
 *
 * 纯静态、无状态、不持有 Context，供笔顺 WebView 与笔画拆解共用。
 */
public final class HanziStrokeSource {

    private HanziStrokeSource() {
    }

    /**
     * 读取原始 JSON：先 assets 再 files，都没有返回 null
     */
    public static String readCharJson(Context context, String zi) {
        if (context == null || zi == null || zi.isEmpty()) return null;

        // 1. assets 内置数据。注意内置 entry 是压缩的，只能走 open()，不能用 openFd()
        AssetManager assets = context.getAssets();
        try (InputStream is = assets.open("hanzi/" + zi + ".json")) {
            return readStream(is);
        } catch (IOException e) {
            // assets 里没有这个字，继续查下载缓存
        }

        // 2. 预下载缓存
        File cacheFile = new File(new File(context.getFilesDir(), "hanzi"), zi + ".json");
        if (!cacheFile.exists()) return null;
        try (InputStream is = new FileInputStream(cacheFile)) {
            return readStream(is);
        } catch (IOException e) {
            LogUtil.e("读取笔画缓存失败: " + cacheFile + " ", e);
            return null;
        }
    }

    /**
     * 解析出逐笔 SVG path；数据缺失或格式异常返回 null
     */
    public static String[] loadStrokes(Context context, String zi) {
        String json = readCharJson(context, zi);
        if (json == null) return null;
        try {
            JSONArray arr = new JSONObject(json).optJSONArray("strokes");
            if (arr == null || arr.length() == 0) return null;
            String[] strokes = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                strokes[i] = arr.optString(i, null);
            }
            return strokes;
        } catch (Exception e) {
            LogUtil.e("解析笔画数据失败: " + zi + " ", e);
            return null;
        }
    }

    private static String readStream(InputStream is) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            return bos.toString("UTF-8");
        }
    }
}
