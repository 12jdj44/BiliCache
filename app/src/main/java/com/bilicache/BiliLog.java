package com.bilicache;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/**
 * 模块日志：Hook 运行在 B 站进程里，所有事件实时写入：
 *   1. XposedBridge.log（LSPosed 管理器日志可查看）
 *   2. B 站外部文件目录 files/BiliCache.log（adb pull 可拿）
 *   3. 开启“导出日志”开关后，同步导出到公共 Download/BiliCache.log
 */
public final class BiliLog {

    private static final int MAX_LINES = 3000;
    private static final StringBuilder BUFFER = new StringBuilder();
    private static volatile Context sContext;
    private static volatile String sLatestExport;
    private static volatile String sLastExportedContent = "";
    private static volatile String sLastToast;

    private BiliLog() {
    }

    public static void init(Context context) {
        sContext = context.getApplicationContext();
    }

    public static synchronized void log(String message) {
        String time = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = time + " " + message;
        BUFFER.append(line).append('\n');
        int count = 0;
        int idx;
        for (int i = 0; i < BUFFER.length(); i++) {
            if (BUFFER.charAt(i) == '\n') {
                count++;
            }
        }
        if (count > MAX_LINES) {
            idx = BUFFER.indexOf("\n");
            if (idx >= 0) {
                BUFFER.delete(0, idx + 1);
            }
        }
        XposedBridge.log("[BiliCache] " + message);
        appendToLocalFile(line);
    }

    public static synchronized String dump() {
        return BUFFER.toString();
    }

    /** 导出当前日志到公共 Download 目录（多级回退），返回文件路径（失败返回 null）。 */
    public static synchronized String exportToDownloads() {
        Context ctx = sContext;
        if (ctx == null) {
            return null;
        }
        String content = dump();
        if (content.equals(sLastExportedContent) && sLatestExport != null) {
            return sLatestExport;
        }
        // 1) Android 11+ / MediaStore
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, "BiliCache.log");
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = ctx.getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    log("export: MediaStore insert returned null");
                } else {
                    OutputStream os = ctx.getContentResolver().openOutputStream(uri);
                    if (os != null) {
                        os.write(content.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        os.close();
                        sLastExportedContent = content;
                        sLatestExport = "Download/BiliCache.log";
                        return sLatestExport;
                    }
                    log("export: MediaStore openOutputStream null");
                }
            }
        } catch (Throwable t) {
            log("export: MediaStore failed: " + t);
        }
        // 2) 旧版公共 Download 路径（API < 29，或 API 29 需 WRITE_EXTERNAL_STORAGE）
        try {
            File dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (dir == null || !dir.exists() && !dir.mkdirs()) {
                log("export: public Download dir unavailable");
            } else {
                File file = new File(dir, "BiliCache.log");
                writeFile(file, content);
                sLastExportedContent = content;
                sLatestExport = file.getAbsolutePath();
                return sLatestExport;
            }
        } catch (Throwable t) {
            log("export: public Download dir failed: " + t);
        }
        // 3) B 站外部 files/Download（Android/data/tv.danmaku.bili/files/Download）
        try {
            File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null || !dir.exists() && !dir.mkdirs()) {
                log("export: app Download dir unavailable");
            } else {
                File file = new File(dir, "BiliCache.log");
                writeFile(file, content);
                sLastExportedContent = content;
                sLatestExport = file.getAbsolutePath();
                return sLatestExport;
            }
        } catch (Throwable t) {
            log("export: app Download dir failed: " + t);
        }
        return null;
    }

    /** 导出结果 Toast（状态变化时提示一次）。 */
    public static synchronized void toastExportResult(String result) {
        if (result == null || result.equals(sLastToast)) {
            return;
        }
        sLastToast = result;
        toast(result);
    }

    public static void toast(final String message) {
        final Context ctx = sContext;
        if (ctx == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(ctx, message, Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static void appendToLocalFile(String line) {
        try {
            File file = localLogFile();
            if (file == null) {
                return;
            }
            FileOutputStream fos = new FileOutputStream(file, true);
            OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            writer.write(line);
            writer.write('\n');
            writer.flush();
            writer.close();
        } catch (Throwable ignored) {
        }
    }

    private static File localLogFile() {
        Context ctx = sContext;
        if (ctx == null) {
            return null;
        }
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) {
            dir = ctx.getFilesDir();
        }
        return new File(dir, "BiliCache.log");
    }

    private static void writeFile(File file, String content) throws Exception {
        FileOutputStream fos = new FileOutputStream(file, false);
        try {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        } finally {
            fos.close();
        }
    }

    public static String latestExport() {
        return sLatestExport;
    }
}
