package top.niunaijun.blackbox.media;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import top.niunaijun.blackbox.BlackBoxCore;

/** Index system metadata only after consent. File contents stay in the device's MediaStore. */
public final class SystemGallery {
    static final int OWNER = Integer.MIN_VALUE;
    private static final String KEY = "system_gallery";
    private static final AtomicBoolean dirty = new AtomicBoolean(true);
    private static boolean observing;
    private static final ContentObserver observer = new ContentObserver(null) {
        @Override public void onChange(boolean selfChange) {
            dirty.set(true);
            BlackBoxMediaProvider.notifyGalleries(BlackBoxCore.getContext());
        }
    };

    private SystemGallery() {}

    public static String[] permissions(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && context.getApplicationInfo().targetSdkVersion >= 33) {
            return new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    public static boolean hasPermission(Context context) {
        // Modern Android can grant only selected items. Both media categories must be granted for "all".
        String[] required = Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO} : permissions(context);
        for (String permission : required) {
            if (context.getPackageManager().checkPermission(permission, context.getPackageName()) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("gallery_access", Context.MODE_PRIVATE);
    }

    public static synchronized boolean isEnabled(Context context) {
        if (!prefs(context).getBoolean(KEY, false)) return false;
        if (hasPermission(context)) return true;
        setEnabled(context, false);
        return false;
    }

    public static synchronized void setEnabled(Context context, boolean enable) {
        boolean allowed = enable && hasPermission(context);
        prefs(context).edit().putBoolean(KEY, allowed).apply();
        if (!allowed) {
            // Revoke previously published file paths, including paths cached by a running clone.
            gate(context).delete();
            if (observing) {
                context.getContentResolver().unregisterContentObserver(observer);
                observing = false;
            }
        }
        dirty.set(true);
        BlackBoxMediaProvider.notifyGalleries(context);
    }

    private static File gate(Context context) {
        return new File(context.getCacheDir(), "blackbox/system-gallery");
    }

    /** Called under the media index lock. Local and system rows share the same stable ID sequence. */
    static synchronized boolean refresh(SQLiteDatabase db) {
        Context ctx = BlackBoxCore.getContext();
        if (!isEnabled(ctx)) return false;
        ContentResolver resolver = ctx.getContentResolver();
        if (!observing) {
            resolver.registerContentObserver(Uri.parse("content://media"), true, observer);
            observing = true;
        }
        boolean pathsExist = gate(ctx).exists();
        if (!dirty.getAndSet(false) && pathsExist) return true;
        File links = new File(gate(ctx).getParentFile(), "gallery-files");
        links.mkdirs();
        try {
            if (!gate(ctx).exists()) Os.symlink(links.getAbsolutePath(), gate(ctx).getAbsolutePath());
        } catch (ErrnoException e) {
            if (e.errno != OsConstants.EEXIST) Log.w("SystemGallery", "File aliases unavailable", e);
        }
        db.beginTransaction();
        try {
            db.execSQL("CREATE TEMP TABLE IF NOT EXISTS gallery_seen (source TEXT PRIMARY KEY)");
            db.execSQL("DELETE FROM gallery_seen");
            HashMap<String, File> buckets = new HashMap<>();
            // The merged collection contains mounted volumes even when an OEM also advertises an unmounted one.
            indexCollection(ctx, db, links, buckets, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false);
            indexCollection(ctx, db, links, buckets, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true);
            String removed = "user_id=" + OWNER + " AND canonical_path NOT IN (SELECT source FROM gallery_seen)";
            // Deleting and recreating a photo can give the same path a new MediaStore ID.
            // Keep its file alias while any current row still uses it.
            String unusedAlias = removed + " AND _data NOT IN (SELECT _data FROM media_entries WHERE user_id="
                    + OWNER + " AND _data IS NOT NULL AND canonical_path IN (SELECT source FROM gallery_seen))";
            try (Cursor c = db.query("media_entries", new String[]{"_data"}, unusedAlias, null, null, null, null)) {
                while (c.moveToNext()) {
                    File alias = new File(c.getString(0));
                    if (alias.getPath().startsWith(gate(ctx).getPath() + File.separator)) alias.delete();
                }
            }
            db.delete("media_entries", removed, null);
            db.setTransactionSuccessful();
            return true;
        } catch (RuntimeException e) {
            Log.w("SystemGallery", "Cannot read system gallery metadata", e);
            dirty.set(true);
            return false;
        } finally {
            db.endTransaction();
        }
    }

    private static void indexCollection(Context ctx, SQLiteDatabase db, File links, HashMap<String, File> buckets,
                                        Uri collection, boolean video) {
        ArrayList<String> columns = new ArrayList<>(Arrays.asList("_id", "_data", "_display_name", "title", "mime_type",
                "_size", "date_added", "date_modified", "datetaken", "bucket_display_name", "bucket_id", "width", "height"));
        if (video) columns.add("duration");
        if (!video) columns.add("orientation");
        if (Build.VERSION.SDK_INT >= 29) {
            columns.add("relative_path");
            columns.add("volume_name");
        }
        try (Cursor c = ctx.getContentResolver().query(collection, columns.toArray(new String[0]), null, null, null)) {
            if (c == null) throw new IllegalStateException("Unavailable media collection");
            while (c.moveToNext()) {
                Uri volumeCollection = collection;
                int volumeColumn = c.getColumnIndex("volume_name");
                if (volumeColumn >= 0 && !c.isNull(volumeColumn)) {
                    String volume = c.getString(volumeColumn);
                    volumeCollection = video ? MediaStore.Video.Media.getContentUri(volume) : MediaStore.Images.Media.getContentUri(volume);
                }
                String source = ContentUris.withAppendedId(volumeCollection, c.getLong(0)).toString();
                ContentValues row = new ContentValues();
                row.put("user_id", OWNER);
                row.put("media_type", video ? MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO : MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE);
                row.put("canonical_path", source);
                row.put("root_path", collection.toString());
                row.put("_data", aliasFor(ctx, links, buckets, c.getString(1)));
                row.put("duration", 0);
                row.put("relative_path", "");
                for (int i = 2; i < columns.size(); i++) {
                    String col = columns.get(i);
                    if ("volume_name".equals(col)) continue;
                    if (!c.isNull(i)) row.put(col, c.getString(i));
                    else if ("relative_path".equals(col) || "_display_name".equals(col)) row.put(col, "");
                    else if (!"title".equals(col) && !"mime_type".equals(col) && !"bucket_display_name".equals(col)) row.put(col, 0);
                }
                if (db.update("media_entries", row, "user_id=? AND canonical_path=?", new String[]{String.valueOf(OWNER), source}) == 0) {
                    db.insertOrThrow("media_entries", null, row);
                }
                db.execSQL("INSERT INTO gallery_seen VALUES (?)", new Object[]{source});
            }
        }
    }

    /** Some clients open _data directly. Expose only indexed files, with their original filenames. */
    private static String aliasFor(Context ctx, File links, HashMap<String, File> buckets, String path) {
        if (path == null || !path.startsWith("/")) return "";
        File file = new File(path);
        String parent = file.getParent();
        if (parent == null) return "";
        File bucket = buckets.get(parent);
        if (bucket == null) {
            bucket = new File(links, UUID.nameUUIDFromBytes(parent.getBytes(StandardCharsets.UTF_8)).toString());
            bucket.mkdirs();
            buckets.put(parent, bucket);
        }
        File alias = new File(bucket, file.getName());
        try {
            if (!alias.exists()) Os.symlink(path, alias.getAbsolutePath());
        } catch (ErrnoException e) {
            if (e.errno != OsConstants.EEXIST) return "";
        }
        return new File(new File(gate(ctx), bucket.getName()), file.getName()).getAbsolutePath();
    }
}
