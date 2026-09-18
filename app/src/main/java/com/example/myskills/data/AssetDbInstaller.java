package com.example.myskills.data;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.StatFs;

import com.example.myskills.ui.util.LogUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;

/**
 * 把打包在 assets 里的字典数据库释放到应用私有目录，供 SQLite 打开。
 *
 * assets 里的文件在 APK 中是 zip 条目，不是真实文件，SQLite 无法直接打开，
 * 所以首次使用时复制一份到 /data/data/<包名>/databases/。
 *
 * 只做一件事：确保目标文件是一个长度正确的完整副本，然后返回它。
 */
public final class AssetDbInstaller {

    /**
     * 打包进 assets 的数据库文件名。
     * 由 tools/sql_to_sqlite.py 生成，重新生成请使用该脚本。
     */
    private static final String ASSET_DIR = "xinhuazidian";
    private static final String ASSET_NAME = "xinhuazidian.db";

    /**
     * 数据库 schema 版本，需与 tools/sql_to_sqlite.py 的 DB_VERSION 保持一致。
     *
     * 版本号写进文件名而不是单独的标记文件：标记文件与 db 是两个事实源，
     * 中间存在「标记写了但 db 没就位」的不一致窗口。文件名带版本则只有一个事实源，
     * 升级时也能靠文件名前缀识别并清理旧版本。
     */
    private static final int DB_VERSION = 1;
    private static final String DB_BASE = "xinhuazidian";

    /** 释放到 databases/ 下的文件名 */
    public static final String DB_NAME = DB_BASE + "_v" + DB_VERSION + ".db";

    /**
     * 数据库完整副本的字节数，与 tools/sql_to_sqlite.py 输出的「字节数」一致。
     *
     * 这是判断本地副本是否完整的唯一依据：压缩过的 asset 拿不到原始长度
     * （openFd() 对压缩 asset 直接抛异常），所以只能在生成时把长度固化下来。
     * 重新生成数据库后，必须同步修改这里的值。
     */
    public static final long DB_SIZE_BYTES = 24334336L;

    /** 复制前的最低可用空间要求，留出余量避免写到一半 ENOSPC */
    private static final long REQUIRED_SPACE_BYTES = 32L * 1024 * 1024;

    private static final String TMP_SUFFIX = ".tmp";
    private static final int BUFFER_SIZE = 64 * 1024;

    private AssetDbInstaller() {
    }

    /**
     * 确保数据库文件已就绪。
     *
     * 耗时操作（首次约 10-20MB 的复制），必须在后台线程调用。
     *
     * @return 可交给 SQLiteDatabase.openDatabase 的文件
     * @throws IOException 目录不可用、空间不足、复制失败等情况
     */
    public static File install(Context context) throws IOException {
        File dbFile = context.getDatabasePath(DB_NAME);
        File dir = dbFile.getParentFile();
        // getDatabasePath() 的父目录不保证存在，而 FileOutputStream 和
        // SQLiteDatabase.openDatabase() 都不会替你创建，缺目录会直接抛
        // SQLiteCantOpenDatabaseException (code 14)
        if (dir != null && !dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw new IOException("无法创建数据库目录: " + dir);
        }

        deleteTempFiles(dir);

        // 快速路径：只有「存在」且「长度正确」才算就绪。
        // 只看 exists() 会让上次复制失败留下的残缺文件被永久当成好文件。
        if (dbFile.exists() && dbFile.length() == DB_SIZE_BYTES) {
            return dbFile;
        }
        if (dbFile.exists()) {
            LogUtil.w("本地字典副本长度异常(" + dbFile.length() + ")，重新释放");
            if (!dbFile.delete()) {
                throw new IOException("无法删除损坏的字典副本: " + dbFile);
            }
        }

        checkFreeSpace(dir);
        copyFromAssets(context, dbFile);
        deleteStaleVersions(dir, dbFile);
        LogUtil.i("字典数据库已释放到 " + dbFile.getAbsolutePath());
        return dbFile;
    }

    /**
     * 删除升级前遗留的旧版本数据库，避免每升一次多占一份空间。
     */
    private static void deleteStaleVersions(File dir, File keep) {
        if (dir == null) return;
        File[] stale = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                return name.startsWith(DB_BASE + "_v")
                        && name.endsWith(".db")
                        && !name.equals(DB_NAME);
            }
        });
        if (stale == null) return;
        for (File file : stale) {
            if (file.delete()) {
                LogUtil.i("已清理旧版本字典: " + file.getName());
            }
        }
    }

    /**
     * 清理上次复制过程中被中断留下的临时文件。
     */
    private static void deleteTempFiles(File dir) {
        if (dir == null) return;
        File[] temps = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File d, String name) {
                return name.startsWith(DB_BASE) && name.endsWith(TMP_SUFFIX);
            }
        });
        if (temps == null) return;
        for (File file : temps) {
            if (file.delete()) {
                LogUtil.i("已清理残留的临时文件: " + file.getName());
            }
        }
    }

    private static void checkFreeSpace(File dir) throws IOException {
        if (dir == null) return;
        try {
            long available = new StatFs(dir.getAbsolutePath()).getAvailableBytes();
            if (available < REQUIRED_SPACE_BYTES) {
                throw new IOException("存储空间不足，需要 " + REQUIRED_SPACE_BYTES
                        + " 字节，当前可用 " + available + " 字节");
            }
        } catch (IllegalArgumentException e) {
            // 路径不可用时 StatFs 会抛异常，此时跳过预检，让后续复制去暴露真实错误
            LogUtil.w("无法读取可用空间，跳过预检: " + e.getMessage());
        }
    }

    /**
     * 先写到临时文件再原子重命名。
     *
     * 同目录内的 rename 是原子的：进程即使在中途被杀，也只会留下一个临时文件，
     * 目标文件名要么不存在、要么就是完整内容，不会被读到半截数据库。
     */
    private static void copyFromAssets(Context context, File dbFile) throws IOException {
        File tmp = new File(dbFile.getParentFile(), DB_NAME + TMP_SUFFIX);
        long copied = 0;

        try (InputStream is = context.getAssets().open(ASSET_DIR + "/" + ASSET_NAME,
                AssetManager.ACCESS_STREAMING);
             FileOutputStream fos = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int len;
            while ((len = is.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
                copied += len;
            }
            fos.flush();
            // 防止进程被杀后 rename 先于数据落盘，重启后读到长度对但内容为空的文件
            fos.getFD().sync();
        } catch (IOException e) {
            tmp.delete();
            throw new IOException("释放字典数据库失败: " + e.getMessage(), e);
        }

        if (copied != DB_SIZE_BYTES) {
            tmp.delete();
            throw new IOException("字典数据不完整，读到 " + copied + " 字节，期望 " + DB_SIZE_BYTES);
        }
        // renameTo 失败只返回 false，不抛异常；此时保留 tmp 便于排查
        if (!tmp.renameTo(dbFile)) {
            throw new IOException("字典数据库重命名失败，临时文件保留在 " + tmp.getAbsolutePath());
        }
    }
}
