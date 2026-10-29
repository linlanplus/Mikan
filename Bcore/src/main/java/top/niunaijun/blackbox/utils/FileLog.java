package top.niunaijun.blackbox.utils;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Mirrors Slog output into one file per process under the host's files dir.
 * Some ROMs (e.g. MagicOS) drop app logs below ERROR from logcat, which hides
 * most engine diagnostics. Must never call Slog itself.
 */
public final class FileLog {
    public static final String DIR_NAME = "bb_logs";
    private static final long MAX_BYTES = 1024 * 1024;
    private static final String LEVELS = "VDIWEA";

    private static final LinkedBlockingQueue<String> sQueue = new LinkedBlockingQueue<>(20000);
    private static final SimpleDateFormat sTimeFormat = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static volatile boolean sWriterStarted;

    private FileLog() {
    }

    public static void append(int priority, String tag, String msg) {
        String level = priority >= 2 && priority <= 7 ? String.valueOf(LEVELS.charAt(priority - 2)) : "?";
        String time;
        synchronized (sTimeFormat) {
            time = sTimeFormat.format(new Date());
        }
        // Drops the line if the queue is full rather than blocking the caller.
        sQueue.offer(time + " " + Process.myPid() + " " + Process.myTid() + " " + level + " " + tag + ": " + msg + "\n");
    }

    public static File getLogDir(Context context) {
        return new File(context.getFilesDir(), DIR_NAME);
    }

    /** Starts writing queued and future lines; call once the host context is known. */
    public static synchronized void init(Context context) {
        if (sWriterStarted || context == null) {
            return;
        }
        File dir = getLogDir(context);
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        File file = new File(dir, currentProcessName().replace(':', '_') + ".log");
        sWriterStarted = true;
        Thread writer = new Thread(() -> writeLoop(file), "FileLog");
        writer.setDaemon(true);
        writer.start();
    }

    private static void writeLoop(File file) {
        Writer out = null;
        try {
            out = open(file);
            while (true) {
                String line = sQueue.take();
                out.write(line);
                if (sQueue.isEmpty()) {
                    out.flush();
                    if (file.length() > MAX_BYTES) {
                        out.close();
                        File old = new File(file.getPath() + ".old");
                        old.delete();
                        file.renameTo(old);
                        out = open(file);
                    }
                }
            }
        } catch (Throwable ignored) {
            // Logging must never take the process down.
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static Writer open(File file) throws Exception {
        return new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8);
    }

    private static String currentProcessName() {
        if (Build.VERSION.SDK_INT >= 28) {
            String name = Application.getProcessName();
            if (name != null && !name.isEmpty()) {
                return name;
            }
        }
        return "pid" + Process.myPid();
    }
}
