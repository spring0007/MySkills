package com.example.myskills.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.example.myskills.ui.util.LogUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 新华字典查询入口（单例）。
 *
 * 线程模型：所有数据库操作都在内部单线程 executor 上串行执行，
 * <b>回调也在该后台线程上触发</b>，更新 UI 请自行 post 回主线程。
 * 这样调用方（Fragment）可以用自己的 Handler 投递结果，
 * 避免回调打到一个已经销毁的 View 上。
 *
 * 生命周期：数据库连接跟随进程，<b>不提供 close()</b>。
 * MainActivity 用 replace() 切换 Fragment，若在 onDestroyView 里关库，
 * 切回来会拿到缓存中的已关闭对象并抛
 * IllegalStateException: attempt to re-open an already-closed object。
 */
public final class HanziDictionary {

    /** 数据库可用状态，界面据此决定提示文案 */
    public enum State {
        /** 尚未开始初始化 */
        UNINITIALIZED,
        /** 正在释放/打开数据库 */
        PREPARING,
        /** 就绪 */
        READY,
        /** 初始化失败，本进程内不再重试 */
        FAILED
    }

    /** 后台线程回调，实现里不要直接碰 UI */
    public interface Callback<T> {
        void onResult(T result);
    }

    private static final String TABLE = "xhzd_surnfu";
    private static final String[] PROJECTION = {
            "id", "zi", "py", "wubi", "bushou", "bihua", "pinyin", "bishun", "jijie", "xiangjie"
    };
    private static final String[] COLUMNS = {
            "id", "zi", "py", "wubi", "bushou", "bihua", "pinyin", "bishun", "jijie", "xiangjie"
    };
    private static final String SELECT_ALL =
            "SELECT " + join(PROJECTION) + " FROM " + TABLE;

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private static volatile HanziDictionary instance;

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /** 只允许在 executor 线程上写，UI 线程只读，故用 volatile */
    private volatile State state = State.UNINITIALIZED;
    private volatile String lastError;
    private SQLiteDatabase db;

    private HanziDictionary(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static HanziDictionary get(Context context) {
        if (instance == null) {
            synchronized (HanziDictionary.class) {
                if (instance == null) {
                    instance = new HanziDictionary(context);
                }
            }
        }
        return instance;
    }

    public State getState() {
        return state;
    }

    public String getLastError() {
        return lastError;
    }

    // ============================================================
    //                        初始化
    // ============================================================

    /**
     * 提前触发数据库释放与打开，避免用户第一次输入时才开始复制十几 MB。
     */
    public void warmUp(final Callback<State> callback) {
        executor.execute(() -> {
            openIfNeeded();
            if (callback != null) callback.onResult(state);
        });
    }

    /**
     * 只在 executor 线程调用。
     *
     * @return 数据库是否可用
     */
    private boolean openIfNeeded() {
        if (state == State.READY && db != null && db.isOpen()) {
            return true;
        }
        // 已经失败过就不再重试：低端机上反复触发十几 MB 拷贝很难受
        if (state == State.FAILED) {
            return false;
        }
        state = State.PREPARING;
        try {
            File file = AssetDbInstaller.install(appContext);
            // 不用 SQLiteOpenHelper：它会以读写方式打开，并拿 user_version 与自己的
            // 版本号比对，而本库是脚本预先建好的，会被误判为「需要 onCreate」
            db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            state = State.READY;
            return true;
        } catch (Exception e) {
            lastError = e.getMessage();
            state = State.FAILED;
            LogUtil.e("打开新华字典数据库失败: " + e.getMessage(), e);
            return false;
        }
    }

    // ============================================================
    //                        查询接口
    // ============================================================

    /**
     * 按汉字精确查询。
     *
     * @param callback 回调在后台线程执行；未收录时 result 为 null
     */
    public void queryByZi(final String zi, final Callback<HanziEntry> callback) {
        if (zi == null || zi.isEmpty()) {
            if (callback != null) callback.onResult(null);
            return;
        }
        executor.execute(() -> {
            HanziEntry entry = null;
            if (openIfNeeded()) {
                // zi 全表唯一，仍加 LIMIT 1 让意图明确
                try (Cursor cursor = db.rawQuery(
                        SELECT_ALL + " WHERE zi = ? LIMIT 1", new String[]{zi})) {
                    if (cursor.moveToFirst()) {
                        entry = readEntry(cursor);
                    }
                }
            }
            if (callback != null) callback.onResult(entry);
        });
    }

    /**
     * 按无调号拼音模糊查询，如 zhong 可匹配 zhòng/zhōng 等多音字。
     */
    public void searchByPinyin(final String keyword, final int limit,
                               final Callback<List<HanziEntry>> callback) {
        queryList("py LIKE ? OR pinyin LIKE ?",
                new String[]{like(keyword), like(keyword)}, keyword, limit, callback);
    }

    /**
     * 按部首查询。
     */
    public void searchByBushou(final String bushou, final int limit,
                               final Callback<List<HanziEntry>> callback) {
        queryList("bushou = ?", new String[]{bushou}, bushou, limit, callback);
    }

    /**
     * 按笔画数查询。
     */
    public void searchByBihua(final int bihua, final int limit,
                              final Callback<List<HanziEntry>> callback) {
        String safe = String.valueOf(bihua);
        queryList("bihua = ?", new String[]{safe}, safe, limit, callback);
    }

    /**
     * 在释义/详解正文里做关键字检索。
     */
    public void searchByText(final String keyword, final int limit,
                             final Callback<List<HanziEntry>> callback) {
        queryList("jijie LIKE ? OR xiangjie LIKE ?",
                new String[]{like(keyword), like(keyword)}, keyword, limit, callback);
    }

    /**
     * 查询列表的公共实现。
     *
     * @param where      不含 WHERE 关键字的条件片段，只能用 ? 占位
     * @param args       条件参数
     * @param keyword    用于判断空输入
     * @param limit      期望条数
     * @param callback   回调在后台线程执行
     */
    private void queryList(final String where, final String[] args, final String keyword,
                           final int limit, final Callback<List<HanziEntry>> callback) {
        if (keyword == null || keyword.trim().isEmpty()) {
            if (callback != null) callback.onResult(new ArrayList<>());
            return;
        }
        final int size = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        executor.execute(() -> {
            List<HanziEntry> result = new ArrayList<>();
            if (openIfNeeded()) {
                String sql = SELECT_ALL + " WHERE " + where + " ORDER BY bihua, id LIMIT ?";
                String[] bindArgs = new String[args.length + 1];
                System.arraycopy(args, 0, bindArgs, 0, args.length);
                bindArgs[args.length] = String.valueOf(size);
                try (Cursor cursor = db.rawQuery(sql, bindArgs)) {
                    while (cursor.moveToNext()) {
                        result.add(readEntry(cursor));
                    }
                }
            }
            if (callback != null) callback.onResult(result);
        });
    }

    private static String like(String keyword) {
        return "%" + keyword.trim() + "%";
    }

    // ============================================================
    //                        工具方法
    // ============================================================

    private static HanziEntry readEntry(Cursor cursor) {
        HanziEntry entry = new HanziEntry();
        entry.setId(getLong(cursor, COLUMNS[0]));
        entry.setZi(getString(cursor, COLUMNS[1]));
        entry.setPy(getString(cursor, COLUMNS[2]));
        entry.setWubi(getString(cursor, COLUMNS[3]));
        entry.setBushou(getString(cursor, COLUMNS[4]));
        entry.setBihua((int) getLong(cursor, COLUMNS[5]));
        entry.setPinyin(getString(cursor, COLUMNS[6]));
        entry.setBishun(getString(cursor, COLUMNS[7]));
        entry.setJijie(getString(cursor, COLUMNS[8]));
        entry.setXiangjie(getString(cursor, COLUMNS[9]));
        return entry;
    }

    private static String getString(Cursor cursor, String column) {
        return cursor.getString(cursor.getColumnIndexOrThrow(column));
    }

    private static long getLong(Cursor cursor, String column) {
        return cursor.getLong(cursor.getColumnIndexOrThrow(column));
    }

    private static String join(String[] columns) {
        StringBuilder sb = new StringBuilder();
        for (String column : columns) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(column);
        }
        return sb.toString();
    }
}
