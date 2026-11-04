package top.niunaijun.blackbox.media;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.MatrixCursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import android.util.Base64;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.utils.BzFileUtils;
import top.niunaijun.blackbox.utils.Slog;

public final class BlackBoxMediaStore {
    private static final String TAG = "BlackBoxMediaStore";
    private static final String DATABASE_NAME = "media-index.db";
    private static final int DATABASE_VERSION = 2;

    private static final String SELECTED_DIR = ".mikan-selected";
    // ContentResolver callers supply SQL fragments. Only a balanced, single expression may
    // be appended to the gallery owner's predicate; subqueries could read another album.
    private static final Pattern SQL_TOKEN = Pattern.compile(
            "'(?:[^']|'')*'|\"(?:[^\"]|\"\")*\"|[a-zA-Z_][a-zA-Z_0-9]*|\\s+|[0-9]+|--|/\\*|[()<>!=?,.+*/%&|~-]");
    private static final Pattern SQL_WORD = Pattern.compile(
            "and|or|not|in|is|isnull|notnull|null|like|glob|escape|between|true|false|collate|nocase|binary|localized|"
            + "asc|desc|lower|upper|length|substr|coalesce|ifnull|strftime|date|datetime|cast|as|integer|text|"
            + "case|when|then|else|end|volume_name|is_favorite|is_trashed|is_download");

    private static final Object DB_LOCK = new Object();
    private static SQLiteDatabase sDatabase;

    private BlackBoxMediaStore() {
    }

    public static File getImagesRoot(int userId) {
        return new File(BEnvironment.getExternalUserDir(userId), BlackBoxMediaContract.ROOT_IMAGES);
    }

    public static File getVideosRoot(int userId) {
        return new File(BEnvironment.getExternalUserDir(userId), BlackBoxMediaContract.ROOT_VIDEOS);
    }

    /** Keep IDs referenced by already-installed guests while adopting their two legacy roots. */
    public static boolean adoptLegacyGallery(int oldUser, int album) {
        synchronized (DB_LOCK) {
            SQLiteDatabase db = getDatabaseLocked();
            db.beginTransaction();
            try {
                for (boolean video : new boolean[]{false, true}) {
                    File oldRoot = video ? getVideosRoot(oldUser) : getImagesRoot(oldUser);
                    File newRoot = video ? getVideosRoot(album) : getImagesRoot(album);
                    File target = new File(newRoot, "legacy-" + oldUser);
                    if (oldRoot.exists()) {
                        String[] files = oldRoot.list();
                        if (files == null) return false;
                        if (files.length != 0) {
                            if ((!newRoot.isDirectory() && !newRoot.mkdirs()) || !oldRoot.renameTo(target)) return false;
                        }
                    }
                    if (target.isDirectory()) {
                        // A retry after the rename finishes the same database transaction.
                        String old = oldRoot.getAbsolutePath();
                        String to = target.getAbsolutePath();
                        db.execSQL("UPDATE media_entries SET user_id=?, _data=? || substr(_data, ?), "
                                        + "canonical_path=? || substr(canonical_path, ?), root_path=? "
                                        + "WHERE user_id=? AND root_path=?",
                                new Object[]{album, to, old.length() + 1, to, old.length() + 1, newRoot.getAbsolutePath(), oldUser, old});
                    }
                }
                db.delete("scan_state", "user_id IN (?,?)", new String[]{String.valueOf(oldUser), String.valueOf(album)});
                db.setTransactionSuccessful();
                return true;
            } finally {
                db.endTransaction();
            }
        }
    }

    public static List<BlackBoxMediaEntry> listMediaEntries(int userId) {
        ensureFresh(userId, MediaStore.Files.FileColumns.MEDIA_TYPE_NONE);
        synchronized (DB_LOCK) {
            return queryEntriesLocked(getDatabaseLocked(),
                    BlackBoxMediaContract.COL_USER_ID + "=?",
                    new String[]{String.valueOf(userId)},
                    BlackBoxMediaContract.COL_DATE_MODIFIED + " DESC, " + BlackBoxMediaContract.COL_ID + " DESC");
        }
    }

    public static BlackBoxMediaEntry getEntry(int userId, long id) {
        ensureFresh(userId, MediaStore.Files.FileColumns.MEDIA_TYPE_NONE);
        synchronized (DB_LOCK) {
            List<BlackBoxMediaEntry> entries = queryEntriesLocked(
                    getDatabaseLocked(),
                    BlackBoxMediaContract.COL_USER_ID + "=? AND " + BlackBoxMediaContract.COL_ID + "=?",
                    new String[]{String.valueOf(userId), String.valueOf(id)},
                    null
            );
            return entries.isEmpty() ? null : entries.get(0);
        }
    }

    public static BlackBoxMediaEntry importMedia(Context context, int userId, Uri sourceUri, String mimeHint) throws IOException {
        return copyMedia(context, userId, sourceUri, mimeHint, false);
    }

    public static BlackBoxMediaEntry grantSelectedImage(Context context, int owner, Uri source) throws IOException {
        if (source == null || !"content".equals(source.getScheme())) throw new IOException("Invalid selected image");
        return copyMedia(context, owner, source, null, true);
    }

    public static boolean isSelectedImage(BlackBoxMediaEntry entry) {
        File parent = new File(entry.getFilePath()).getParentFile();
        return parent != null && new File(getImagesRoot(entry.getUserId()), SELECTED_DIR).equals(parent.getParentFile());
    }

    public static boolean revokeSelectedImage(int owner, long id) {
        synchronized (DB_LOCK) {
            BlackBoxMediaEntry entry = getEntry(owner, id);
            if (entry == null || !isSelectedImage(entry)) return false;
            File file = new File(entry.getFilePath());
            if (!file.delete() && file.exists()) return false;
            getDatabaseLocked().delete(BlackBoxMediaContract.TABLE_MEDIA, "user_id=? AND _id=?",
                    new String[]{String.valueOf(owner), String.valueOf(id)});
            file.getParentFile().delete();
            refreshStoredState(owner, entry.getMediaType());
            return true;
        }
    }

    private static BlackBoxMediaEntry copyMedia(Context context, int userId, Uri sourceUri, String mimeHint,
                                                boolean selected) throws IOException {
        if (sourceUri == null) {
            throw new IOException("Missing source URI");
        }
        ensureRoots(userId);
        String resolvedMime = context.getContentResolver().getType(sourceUri);
        if (TextUtils.isEmpty(resolvedMime)) {
            resolvedMime = mimeHint;
        }
        String displayName = queryDisplayName(context, sourceUri);
        if (TextUtils.isEmpty(displayName)) {
            displayName = "shared_" + System.currentTimeMillis();
        }
        if (TextUtils.isEmpty(resolvedMime)) {
            resolvedMime = guessMimeFromName(displayName);
        }
        int mediaType = BlackBoxMediaContract.mediaTypeForMime(resolvedMime);
        if (selected && mediaType != MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) throw new IOException("Select an image");
        if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_NONE) {
            throw new IOException("Only images and videos are supported");
        }

        File targetDir = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                ? getVideosRoot(userId)
                : getImagesRoot(userId);
        if (!targetDir.exists()) {
            BzFileUtils.mkdirs(targetDir);
        }

        String safeName = buildSafeDisplayName(displayName, resolvedMime, mediaType);
        // Publish only complete files. Concurrent shares can use the same display name.
        MessageDigest digest = null;
        if (selected) {
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException e) { throw new IOException(e); }
        }
        File staging = File.createTempFile(".share-", ".tmp", targetDir);
        try {
            try (InputStream in = context.getContentResolver().openInputStream(sourceUri);
                 FileOutputStream out = new FileOutputStream(staging)) {
                if (in == null) throw new IOException("Unable to open source URI");
                byte[] buffer = new byte[32768];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                    if (digest != null) digest.update(buffer, 0, count);
                }
            }
            synchronized (DB_LOCK) {
                File target = null;
                if (digest != null) {
                    String key = Base64.encodeToString(digest.digest(), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                    targetDir = new File(new File(targetDir, SELECTED_DIR), key);
                    if (!targetDir.isDirectory() && !targetDir.mkdirs()) throw new IOException("Cannot create selected album");
                    File[] existing = targetDir.listFiles(File::isFile);
                    if (existing != null && existing.length > 0) target = existing[0];
                }
                if (target == null) target = uniqueFile(targetDir, safeName);
                if (!staging.renameTo(target)) throw new IOException("Cannot finish media import");
                BlackBoxMediaEntry entry = upsertFile(userId, target, mediaType, resolvedMime);
                refreshStoredState(userId, entry.getMediaType());
                return entry;
            }
        } finally {
            staging.delete();
        }
    }

    public static void rescanUser(int userId) {
        ensureRoots(userId);
        synchronized (DB_LOCK) {
            SQLiteDatabase database = getDatabaseLocked();
            scanRootLocked(database, userId, MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE, getImagesRoot(userId));
            scanRootLocked(database, userId, MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO, getVideosRoot(userId));
        }
    }

    public static Cursor query(int userId, boolean systemGallery, Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        int mediaType = resolveMediaType(uri);
        ensureFresh(userId, mediaType);
        synchronized (DB_LOCK) {
            SQLiteDatabase database = getDatabaseLocked();
            if (systemGallery) systemGallery = SystemGallery.refresh(database);
            String finalSelection = buildSelection(uri, userId, mediaType, selection, systemGallery);
            String[] finalSelectionArgs = buildSelectionArgs(uri, userId, selectionArgs);
            String orderBy = TextUtils.isEmpty(sortOrder) || !safeFragment(sortOrder, true)
                    ? BlackBoxMediaContract.COL_DATE_MODIFIED + " DESC, " + BlackBoxMediaContract.COL_ID + " DESC"
                    : sortOrder;
            try {
                return database.query(
                        BlackBoxMediaContract.TABLE_MEDIA,
                        normalizeProjection(projection),
                        finalSelection,
                        finalSelectionArgs,
                        null,
                        null,
                        orderBy
                );
            } catch (Throwable firstError) {
                Slog.w(TAG, "Media query fallback for projection/selection: " + firstError.getMessage());
                try {
                    List<BlackBoxMediaEntry> entries = queryEntriesLocked(database, finalSelection, finalSelectionArgs, null);
                    return buildCompatCursor(projection, entries);
                } catch (Throwable secondError) {
                    Slog.w(TAG, "Media query broad fallback: " + secondError.getMessage());
                    String broadSelection = buildSelection(uri, userId, mediaType, null, systemGallery);
                    List<BlackBoxMediaEntry> entries = queryEntriesLocked(database, broadSelection, null, null);
                    return buildCompatCursor(projection, entries);
                }
            }
        }
    }

    private static String[] normalizeProjection(String[] projection) {
        if (projection == null || projection.length == 0) {
            return null;
        }
        String[] columns = projection.clone();
        for (int i = 0; i < columns.length; i++) {
            if (isStoredColumn(columns[i])) continue;
            String column = columns[i];
            if (column == null || !column.matches("[a-zA-Z_][a-zA-Z_0-9]*")) throw new IllegalArgumentException("Unsupported media projection");
            String value = "volume_name".equals(column) ? "'external'"
                    : ("is_favorite".equals(column) || "is_trashed".equals(column)) ? "0" : "NULL";
            columns[i] = value + " AS " + column;
        }
        return columns;
    }

    private static boolean isStoredColumn(String column) {
        if (TextUtils.isEmpty(column)) {
            return false;
        }
        return "orientation".equals(column)
                || BlackBoxMediaContract.COL_ID.equals(column)
                || BlackBoxMediaContract.COL_USER_ID.equals(column)
                || BlackBoxMediaContract.COL_MEDIA_TYPE.equals(column)
                || BlackBoxMediaContract.COL_DISPLAY_NAME.equals(column)
                || BlackBoxMediaContract.COL_TITLE.equals(column)
                || BlackBoxMediaContract.COL_MIME_TYPE.equals(column)
                || BlackBoxMediaContract.COL_SIZE.equals(column)
                || BlackBoxMediaContract.COL_DATE_ADDED.equals(column)
                || BlackBoxMediaContract.COL_DATE_MODIFIED.equals(column)
                || BlackBoxMediaContract.COL_DATE_TAKEN.equals(column)
                || BlackBoxMediaContract.COL_RELATIVE_PATH.equals(column)
                || BlackBoxMediaContract.COL_BUCKET_DISPLAY_NAME.equals(column)
                || BlackBoxMediaContract.COL_BUCKET_ID.equals(column)
                || BlackBoxMediaContract.COL_WIDTH.equals(column)
                || BlackBoxMediaContract.COL_HEIGHT.equals(column)
                || BlackBoxMediaContract.COL_DURATION.equals(column)
                || BlackBoxMediaContract.COL_IS_PENDING.equals(column)
                || BlackBoxMediaContract.COL_DATA.equals(column)
                || BlackBoxMediaContract.COL_CANONICAL_PATH.equals(column)
                || BlackBoxMediaContract.COL_ROOT_PATH.equals(column);
    }

    private static Cursor buildCompatCursor(String[] projection, List<BlackBoxMediaEntry> entries) {
        String[] columns = projection != null && projection.length > 0 ? projection : defaultProjection();
        MatrixCursor cursor = new MatrixCursor(columns);
        if (entries == null) {
            return cursor;
        }
        for (BlackBoxMediaEntry entry : entries) {
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                row[i] = valueForColumn(entry, columns[i]);
            }
            cursor.addRow(row);
        }
        return cursor;
    }

    private static String[] defaultProjection() {
        return new String[]{
                BlackBoxMediaContract.COL_ID,
                BlackBoxMediaContract.COL_MEDIA_TYPE,
                BlackBoxMediaContract.COL_DISPLAY_NAME,
                BlackBoxMediaContract.COL_MIME_TYPE,
                BlackBoxMediaContract.COL_SIZE,
                BlackBoxMediaContract.COL_DATE_MODIFIED,
                BlackBoxMediaContract.COL_DATA
        };
    }

    private static Object valueForColumn(BlackBoxMediaEntry entry, String column) {
        if (entry == null || column == null) return null;
        switch (column) {
            case BlackBoxMediaContract.COL_ID:
                return entry.getId();
            case BlackBoxMediaContract.COL_USER_ID:
                return entry.getUserId();
            case BlackBoxMediaContract.COL_MEDIA_TYPE:
                return entry.getMediaType();
            case BlackBoxMediaContract.COL_DISPLAY_NAME:
                return entry.getDisplayName();
            case BlackBoxMediaContract.COL_TITLE:
                return entry.getTitle();
            case BlackBoxMediaContract.COL_MIME_TYPE:
                return entry.getMimeType();
            case BlackBoxMediaContract.COL_SIZE:
                return entry.getSize();
            case BlackBoxMediaContract.COL_DATE_ADDED:
                return entry.getDateAdded();
            case BlackBoxMediaContract.COL_DATE_MODIFIED:
                return entry.getDateModified();
            case BlackBoxMediaContract.COL_DATE_TAKEN:
                return entry.getDateTaken();
            case BlackBoxMediaContract.COL_RELATIVE_PATH:
                return entry.getRelativePath();
            case BlackBoxMediaContract.COL_BUCKET_DISPLAY_NAME:
                return entry.getBucketDisplayName();
            case BlackBoxMediaContract.COL_BUCKET_ID:
                return entry.getBucketId();
            case BlackBoxMediaContract.COL_WIDTH:
                return entry.getWidth();
            case BlackBoxMediaContract.COL_HEIGHT:
                return entry.getHeight();
            case BlackBoxMediaContract.COL_DURATION:
                return entry.getDuration();
            case BlackBoxMediaContract.COL_IS_PENDING:
                return entry.getIsPending();
            case BlackBoxMediaContract.COL_DATA:
                return entry.getFilePath();
            case BlackBoxMediaContract.COL_CANONICAL_PATH:
                return entry.getCanonicalPath();
            case BlackBoxMediaContract.COL_ROOT_PATH:
                return entry.getRootPath();
            case "orientation":
            case "is_favorite":
            case "is_trashed":
                return 0;
            case "volume_name":
                return BlackBoxMediaContract.EXTERNAL_VOLUME;
            case "owner_package_name":
                return "";
            default:
                return null;
        }
    }

    static Uri systemSource(Uri uri) {
        long id = BlackBoxMediaContract.getIdFromUri(uri);
        if (id <= 0) return null;
        synchronized (DB_LOCK) {
            SQLiteDatabase db = getDatabaseLocked();
            if (!SystemGallery.refresh(db)) return null;
            try (Cursor c = db.query("media_entries", new String[]{"canonical_path"},
                    buildSelection(uri, SystemGallery.OWNER, resolveMediaType(uri), null, false), null, null, null, null)) {
                return c.moveToFirst() ? Uri.parse(c.getString(0)) : null;
            }
        }
    }

    public static Uri insert(int userId, Uri uri, ContentValues values) throws IOException {
        int mediaType = resolveMediaType(uri);
        if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_NONE) {
            mediaType = values.getAsInteger(BlackBoxMediaContract.COL_MEDIA_TYPE) != null
                    ? values.getAsInteger(BlackBoxMediaContract.COL_MEDIA_TYPE)
                    : BlackBoxMediaContract.mediaTypeForMime(values.getAsString(BlackBoxMediaContract.COL_MIME_TYPE));
        }
        if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_NONE) {
            throw new IOException("Unsupported media type");
        }
        ensureRoots(userId);

        String displayName = values.getAsString(BlackBoxMediaContract.COL_DISPLAY_NAME);
        if (TextUtils.isEmpty(displayName)) {
            displayName = "media_" + System.currentTimeMillis();
        }
        String mimeType = values.getAsString(BlackBoxMediaContract.COL_MIME_TYPE);
        if (TextUtils.isEmpty(mimeType)) {
            mimeType = guessMimeFromName(displayName);
        }
        String safeName = buildSafeDisplayName(displayName, mimeType, mediaType);
        String relativePath = normalizeRelativePath(values.getAsString(BlackBoxMediaContract.COL_RELATIVE_PATH), mediaType);
        File targetDir = resolveTargetDir(userId, relativePath, mediaType);
        BzFileUtils.mkdirs(targetDir);
        File targetFile = uniqueFile(targetDir, safeName);
        if (!targetFile.exists() && !targetFile.createNewFile()) {
            throw new IOException("Failed to create media file");
        }

        long nowSeconds = System.currentTimeMillis() / 1000L;
        ContentValues rowValues = new ContentValues();
        rowValues.put(BlackBoxMediaContract.COL_USER_ID, userId);
        rowValues.put(BlackBoxMediaContract.COL_MEDIA_TYPE, mediaType);
        rowValues.put(BlackBoxMediaContract.COL_DISPLAY_NAME, targetFile.getName());
        rowValues.put(BlackBoxMediaContract.COL_TITLE, titleFromDisplayName(targetFile.getName()));
        rowValues.put(BlackBoxMediaContract.COL_MIME_TYPE, mimeType);
        rowValues.put(BlackBoxMediaContract.COL_SIZE, targetFile.length());
        rowValues.put(BlackBoxMediaContract.COL_DATE_ADDED, values.getAsLong(BlackBoxMediaContract.COL_DATE_ADDED) != null
                ? values.getAsLong(BlackBoxMediaContract.COL_DATE_ADDED)
                : nowSeconds);
        rowValues.put(BlackBoxMediaContract.COL_DATE_MODIFIED, values.getAsLong(BlackBoxMediaContract.COL_DATE_MODIFIED) != null
                ? values.getAsLong(BlackBoxMediaContract.COL_DATE_MODIFIED)
                : nowSeconds);
        rowValues.put(BlackBoxMediaContract.COL_DATE_TAKEN, values.getAsLong(BlackBoxMediaContract.COL_DATE_TAKEN) != null
                ? values.getAsLong(BlackBoxMediaContract.COL_DATE_TAKEN)
                : targetFile.lastModified());
        rowValues.put(BlackBoxMediaContract.COL_RELATIVE_PATH, relativePath);
        rowValues.put(BlackBoxMediaContract.COL_BUCKET_DISPLAY_NAME, bucketDisplayName(relativePath));
        rowValues.put(BlackBoxMediaContract.COL_BUCKET_ID, bucketId(relativePath));
        rowValues.put(BlackBoxMediaContract.COL_WIDTH, values.getAsInteger(BlackBoxMediaContract.COL_WIDTH) != null
                ? values.getAsInteger(BlackBoxMediaContract.COL_WIDTH)
                : 0);
        rowValues.put(BlackBoxMediaContract.COL_HEIGHT, values.getAsInteger(BlackBoxMediaContract.COL_HEIGHT) != null
                ? values.getAsInteger(BlackBoxMediaContract.COL_HEIGHT)
                : 0);
        rowValues.put(BlackBoxMediaContract.COL_DURATION, values.getAsLong(BlackBoxMediaContract.COL_DURATION) != null
                ? values.getAsLong(BlackBoxMediaContract.COL_DURATION)
                : 0L);
        rowValues.put(BlackBoxMediaContract.COL_IS_PENDING, values.getAsInteger(BlackBoxMediaContract.COL_IS_PENDING) != null
                ? values.getAsInteger(BlackBoxMediaContract.COL_IS_PENDING)
                : 0);
        rowValues.put(BlackBoxMediaContract.COL_DATA, targetFile.getAbsolutePath());
        rowValues.put(BlackBoxMediaContract.COL_CANONICAL_PATH, canonicalPath(targetFile));
        rowValues.put(BlackBoxMediaContract.COL_ROOT_PATH, mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                ? getVideosRoot(userId).getAbsolutePath()
                : getImagesRoot(userId).getAbsolutePath());

        synchronized (DB_LOCK) {
            SQLiteDatabase database = getDatabaseLocked();
            long rowId = database.insertOrThrow(BlackBoxMediaContract.TABLE_MEDIA, null, rowValues);
            refreshStoredState(userId, mediaType);
            return BlackBoxMediaContract.buildPrivateItemUri(mediaType, rowId, userId);
        }
    }

    public static int update(int userId, Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        values = new ContentValues(values);
        for (String column : new String[]{"_id", "user_id", "_data", "canonical_path", "root_path"}) values.remove(column);
        if (values.size() == 0) return 0;
        for (String column : values.keySet()) {
            if (!isStoredColumn(column)) throw new IllegalArgumentException("Unsupported media update");
        }
        int mediaType = resolveMediaType(uri);
        synchronized (DB_LOCK) {
            SQLiteDatabase database = getDatabaseLocked();
            String finalSelection = writableSelection(uri, userId, mediaType, selection);
            String[] finalSelectionArgs = buildSelectionArgs(uri, userId, selectionArgs);
            int updated = database.update(BlackBoxMediaContract.TABLE_MEDIA, values, finalSelection, finalSelectionArgs);
            if (updated > 0) {
                refreshStoredState(userId, mediaType);
            }
            return updated;
        }
    }

    public static int delete(int userId, Uri uri, String selection, String[] selectionArgs) {
        int mediaType = resolveMediaType(uri);
        synchronized (DB_LOCK) {
            SQLiteDatabase database = getDatabaseLocked();
            String finalSelection = writableSelection(uri, userId, mediaType, selection);
            String[] finalSelectionArgs = buildSelectionArgs(uri, userId, selectionArgs);
            List<BlackBoxMediaEntry> entries = queryEntriesLocked(database, finalSelection, finalSelectionArgs, null);
            for (BlackBoxMediaEntry entry : entries) {
                BzFileUtils.deleteDir(entry.getFilePath());
            }
            int deleted = database.delete(BlackBoxMediaContract.TABLE_MEDIA, finalSelection, finalSelectionArgs);
            if (deleted > 0) {
                refreshStoredState(userId, mediaType);
            }
            return deleted;
        }
    }

    public static ParcelFileDescriptor openFile(int userId, Uri uri, String mode) throws IOException {
        BlackBoxMediaEntry entry = getEntry(userId, BlackBoxMediaContract.getIdFromUri(uri));
        if (entry == null) {
            throw new IOException("Media entry not found");
        }
        File file = new File(entry.getFilePath());
        if (!file.exists()) {
            throw new IOException("Media file is missing");
        }
        if (!"r".equals(mode) && isSelectedImage(entry)) throw new IOException("Selected photo is read-only");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    public static String getType(int userId, Uri uri) {
        BlackBoxMediaEntry entry = getEntry(userId, BlackBoxMediaContract.getIdFromUri(uri));
        if (entry == null) {
            int mediaType = resolveMediaType(uri);
            if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) {
                return "vnd.android.cursor.dir/image";
            }
            if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                return "vnd.android.cursor.dir/video";
            }
            return "vnd.android.cursor.dir/file";
        }
        return entry.getMimeType();
    }

    private static void ensureFresh(int userId, int mediaType) {
        synchronized (DB_LOCK) {
            SQLiteDatabase database = getDatabaseLocked();
            if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE || mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_NONE) {
                maybeRescanRootLocked(database, userId, MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE, getImagesRoot(userId));
            }
            if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO || mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_NONE) {
                maybeRescanRootLocked(database, userId, MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO, getVideosRoot(userId));
            }
        }
    }

    private static void maybeRescanRootLocked(SQLiteDatabase database, int userId, int mediaType, File root) {
        ensureRootExists(root);
        RootState currentState = RootState.compute(root);
        RootState storedState = loadStoredStateLocked(database, userId, root.getAbsolutePath());
        if (!currentState.sameAs(storedState)) {
            scanRootLocked(database, userId, mediaType, root);
        }
    }

    private static void scanRootLocked(SQLiteDatabase database, int userId, int mediaType, File root) {
        ensureRootExists(root);
        List<File> files = collectMediaFiles(root, mediaType);
        Set<String> seenCanonicalPaths = new HashSet<>();
        database.beginTransaction();
        try {
            for (File file : files) {
                BlackBoxMediaEntry entry = upsertFileLocked(database, userId, file, mediaType, null);
                seenCanonicalPaths.add(entry.getCanonicalPath());
            }
            cleanupMissingRowsLocked(database, userId, root.getAbsolutePath(), seenCanonicalPaths);
            saveRootStateLocked(database, userId, root.getAbsolutePath(), RootState.compute(root));
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    private static void cleanupMissingRowsLocked(SQLiteDatabase database, int userId, String rootPath, Set<String> seenCanonicalPaths) {
        try (Cursor cursor = database.query(
                BlackBoxMediaContract.TABLE_MEDIA,
                new String[]{BlackBoxMediaContract.COL_ID, BlackBoxMediaContract.COL_CANONICAL_PATH},
                BlackBoxMediaContract.COL_USER_ID + "=? AND " + BlackBoxMediaContract.COL_ROOT_PATH + "=?",
                new String[]{String.valueOf(userId), rootPath},
                null,
                null,
                null
        )) {
            while (cursor.moveToNext()) {
                long rowId = cursor.getLong(0);
                String canonicalPath = cursor.getString(1);
                if (!seenCanonicalPaths.contains(canonicalPath)) {
                    database.delete(
                            BlackBoxMediaContract.TABLE_MEDIA,
                            BlackBoxMediaContract.COL_ID + "=?",
                            new String[]{String.valueOf(rowId)}
                    );
                }
            }
        }
    }

    private static BlackBoxMediaEntry upsertFile(int userId, File file, int mediaType, String forcedMimeType) {
        synchronized (DB_LOCK) {
            return upsertFileLocked(getDatabaseLocked(), userId, file, mediaType, forcedMimeType);
        }
    }

    private static BlackBoxMediaEntry upsertFileLocked(SQLiteDatabase database, int userId, File file, int mediaType, String forcedMimeType) {
        ensureRootExists(file.getParentFile());
        String canonicalPath = canonicalPath(file);
        String relativePath = buildRelativePath(userId, file, mediaType);
        MediaMetadata metadata = readMetadata(file, mediaType, forcedMimeType);
        long nowSeconds = Math.max(1L, file.lastModified() / 1000L);

        ContentValues values = new ContentValues();
        values.put(BlackBoxMediaContract.COL_USER_ID, userId);
        values.put(BlackBoxMediaContract.COL_MEDIA_TYPE, mediaType);
        values.put(BlackBoxMediaContract.COL_DISPLAY_NAME, file.getName());
        values.put(BlackBoxMediaContract.COL_TITLE, titleFromDisplayName(file.getName()));
        values.put(BlackBoxMediaContract.COL_MIME_TYPE, metadata.mimeType);
        values.put(BlackBoxMediaContract.COL_SIZE, file.length());
        values.put(BlackBoxMediaContract.COL_DATE_ADDED, nowSeconds);
        values.put(BlackBoxMediaContract.COL_DATE_MODIFIED, nowSeconds);
        values.put(BlackBoxMediaContract.COL_DATE_TAKEN, file.lastModified());
        values.put(BlackBoxMediaContract.COL_RELATIVE_PATH, relativePath);
        values.put(BlackBoxMediaContract.COL_BUCKET_DISPLAY_NAME, bucketDisplayName(relativePath));
        values.put(BlackBoxMediaContract.COL_BUCKET_ID, bucketId(relativePath));
        values.put(BlackBoxMediaContract.COL_WIDTH, metadata.width);
        values.put(BlackBoxMediaContract.COL_HEIGHT, metadata.height);
        values.put(BlackBoxMediaContract.COL_DURATION, metadata.duration);
        values.put(BlackBoxMediaContract.COL_IS_PENDING, 0);
        values.put(BlackBoxMediaContract.COL_DATA, file.getAbsolutePath());
        values.put(BlackBoxMediaContract.COL_CANONICAL_PATH, canonicalPath);
        values.put(BlackBoxMediaContract.COL_ROOT_PATH, mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                ? getVideosRoot(userId).getAbsolutePath()
                : getImagesRoot(userId).getAbsolutePath());

        Long existingId = findRowIdByCanonicalPathLocked(database, userId, canonicalPath);
        long rowId;
        if (existingId != null) {
            database.update(
                    BlackBoxMediaContract.TABLE_MEDIA,
                    values,
                    BlackBoxMediaContract.COL_ID + "=?",
                    new String[]{String.valueOf(existingId)}
            );
            rowId = existingId;
        } else {
            rowId = database.insertOrThrow(BlackBoxMediaContract.TABLE_MEDIA, null, values);
        }
        return new BlackBoxMediaEntry(
                rowId,
                userId,
                mediaType,
                file.getName(),
                titleFromDisplayName(file.getName()),
                metadata.mimeType,
                file.length(),
                nowSeconds,
                nowSeconds,
                file.lastModified(),
                relativePath,
                bucketDisplayName(relativePath),
                bucketId(relativePath),
                metadata.width,
                metadata.height,
                metadata.duration,
                0,
                file.getAbsolutePath(),
                canonicalPath,
                values.getAsString(BlackBoxMediaContract.COL_ROOT_PATH)
        );
    }

    private static List<BlackBoxMediaEntry> queryEntriesLocked(SQLiteDatabase database, String selection, String[] selectionArgs, String sortOrder) {
        List<BlackBoxMediaEntry> entries = new ArrayList<>();
        try (Cursor cursor = database.query(
                BlackBoxMediaContract.TABLE_MEDIA,
                null,
                selection,
                selectionArgs,
                null,
                null,
                sortOrder
        )) {
            while (cursor.moveToNext()) {
                entries.add(readEntry(cursor));
            }
        }
        return entries;
    }

    private static BlackBoxMediaEntry readEntry(Cursor cursor) {
        return new BlackBoxMediaEntry(
                cursor.getLong(cursor.getColumnIndexOrThrow(BlackBoxMediaContract.COL_ID)),
                cursor.getInt(cursor.getColumnIndexOrThrow(BlackBoxMediaContract.COL_USER_ID)),
                cursor.getInt(cursor.getColumnIndexOrThrow(BlackBoxMediaContract.COL_MEDIA_TYPE)),
                cursor.getString(cursor.getColumnIndexOrThrow(BlackBoxMediaContract.COL_DISPLAY_NAME)),
                cursor.getString(cursor.getColumnIndexOrThrow(BlackBoxMediaContract.COL_TITLE)),
                cursor.getString(cursor.getColumnIndexOrThrow(BlackBoxMediaContract.COL_MIME_TYPE)),
                cursor.getLong(cursor.getColumnIndexOrThrow(BlackBoxMediaContract.COL_SIZE)),
                cursor.getLong(cursor.getColumnIndexOrThrow(BlackBoxMediaContract.COL_DATE_ADDED)),
                cursor.getLong(cursor.getColumnIndexOrThrow(BlackBoxMediaContract.COL_DATE_MODIFIED)),
                cursor.getLong(cursor.getColumnIndexOrThrow(BlackBoxMediaContract.COL_DATE_TAKEN)),
                cursor.getString(cursor.getColumnIndexOrThrow(BlackBoxMediaContract.COL_RELATIVE_PATH)),
                cursor.getString(cursor.getColumnIndexOrThrow(BlackBoxMediaContract.COL_BUCKET_DISPLAY_NAME)),
                cursor.getInt(cursor.getColumnIndexOrThrow(BlackBoxMediaContract.COL_BUCKET_ID)),
                cursor.getInt(cursor.getColumnIndexOrThrow(BlackBoxMediaContract.COL_WIDTH)),
                cursor.getInt(cursor.getColumnIndexOrThrow(BlackBoxMediaContract.COL_HEIGHT)),
                cursor.getLong(cursor.getColumnIndexOrThrow(BlackBoxMediaContract.COL_DURATION)),
                cursor.getInt(cursor.getColumnIndexOrThrow(BlackBoxMediaContract.COL_IS_PENDING)),
                cursor.getString(cursor.getColumnIndexOrThrow(BlackBoxMediaContract.COL_DATA)),
                cursor.getString(cursor.getColumnIndexOrThrow(BlackBoxMediaContract.COL_CANONICAL_PATH)),
                cursor.getString(cursor.getColumnIndexOrThrow(BlackBoxMediaContract.COL_ROOT_PATH))
        );
    }

    private static boolean safeFragment(String sql, boolean sorting) {
        if (TextUtils.isEmpty(sql)) return true;
        Matcher tokens = SQL_TOKEN.matcher(sql);
        int end = 0, depth = 0;
        boolean expectList = false;
        while (tokens.find()) {
            if (tokens.start() != end) return false;
            end = tokens.end();
            String token = tokens.group();
            char c = token.charAt(0);
            if (Character.isWhitespace(c)) continue;
            if (expectList && c != '(') return false;
            expectList = false;
            if (c == '(') depth++;
            else if (c == ')' && --depth < 0) return false;
            else if (token.equals("--") || token.equals("/*")) return false;
            else if (Character.isLetter(c) || c == '_') {
                String word = token.toLowerCase(Locale.ROOT);
                if (!isStoredColumn(word) && !SQL_WORD.matcher(word).matches()
                        && !(sorting && (word.equals("limit") || word.equals("offset")))) return false;
                expectList = word.equals("in");
            }
        }
        return end == sql.length() && depth == 0 && !expectList;
    }

    private static String writableSelection(Uri uri, int userId, int mediaType, String selection) {
        String grants = new File(getImagesRoot(userId), SELECTED_DIR).getAbsolutePath() + File.separator;
        return buildSelection(uri, userId, mediaType, selection, false)
                + " AND instr(_data, " + DatabaseUtils.sqlEscapeString(grants) + ") != 1";
    }

    private static String buildSelection(Uri uri, int userId, int mediaType, String selection, boolean systemGallery) {
        if (!safeFragment(selection, false)) throw new IllegalArgumentException("Unsupported media selection");
        StringBuilder builder = new StringBuilder();
        if (systemGallery) builder.append("user_id IN (").append(userId).append(",").append(SystemGallery.OWNER).append(")");
        else builder.append("user_id=").append(userId);
        if (mediaType != MediaStore.Files.FileColumns.MEDIA_TYPE_NONE) {
            builder.append(" AND ").append(BlackBoxMediaContract.COL_MEDIA_TYPE).append("=").append(mediaType);
        }
        long rowId = BlackBoxMediaContract.getIdFromUri(uri);
        if (rowId > 0) {
            builder.append(" AND ").append(BlackBoxMediaContract.COL_ID).append("=").append(rowId);
        }
        if (!TextUtils.isEmpty(selection)) {
            builder.append(" AND (").append(selection).append(")");
        }
        return builder.toString();
    }

    private static String[] buildSelectionArgs(Uri uri, int userId, String[] selectionArgs) {
        return selectionArgs;
    }

    private static int resolveMediaType(Uri uri) {
        if (BlackBoxMediaContract.isImagesUri(uri)) {
            return MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;
        }
        if (BlackBoxMediaContract.isVideosUri(uri)) {
            return MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
        }
        return MediaStore.Files.FileColumns.MEDIA_TYPE_NONE;
    }

    private static Long findRowIdByCanonicalPathLocked(SQLiteDatabase database, int userId, String canonicalPath) {
        try (Cursor cursor = database.query(
                BlackBoxMediaContract.TABLE_MEDIA,
                new String[]{BlackBoxMediaContract.COL_ID},
                BlackBoxMediaContract.COL_USER_ID + "=? AND " + BlackBoxMediaContract.COL_CANONICAL_PATH + "=?",
                new String[]{String.valueOf(userId), canonicalPath},
                null,
                null,
                null
        )) {
            if (cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        }
        return null;
    }

    private static RootState loadStoredStateLocked(SQLiteDatabase database, int userId, String rootPath) {
        try (Cursor cursor = database.query(
                BlackBoxMediaContract.TABLE_SCAN_STATE,
                null,
                BlackBoxMediaContract.STATE_COL_USER_ID + "=? AND " + BlackBoxMediaContract.STATE_COL_ROOT_PATH + "=?",
                new String[]{String.valueOf(userId), rootPath},
                null,
                null,
                null
        )) {
            if (cursor.moveToFirst()) {
                return new RootState(
                        cursor.getLong(cursor.getColumnIndexOrThrow(BlackBoxMediaContract.STATE_COL_ROOT_LAST_MODIFIED)),
                        cursor.getInt(cursor.getColumnIndexOrThrow(BlackBoxMediaContract.STATE_COL_FILE_COUNT)),
                        cursor.getLong(cursor.getColumnIndexOrThrow(BlackBoxMediaContract.STATE_COL_MAX_MODIFIED))
                );
            }
        }
        return RootState.EMPTY;
    }

    private static void saveRootStateLocked(SQLiteDatabase database, int userId, String rootPath, RootState state) {
        ContentValues values = new ContentValues();
        values.put(BlackBoxMediaContract.STATE_COL_USER_ID, userId);
        values.put(BlackBoxMediaContract.STATE_COL_ROOT_PATH, rootPath);
        values.put(BlackBoxMediaContract.STATE_COL_ROOT_LAST_MODIFIED, state.rootLastModified);
        values.put(BlackBoxMediaContract.STATE_COL_FILE_COUNT, state.fileCount);
        values.put(BlackBoxMediaContract.STATE_COL_MAX_MODIFIED, state.maxModified);
        int updated = database.update(
                BlackBoxMediaContract.TABLE_SCAN_STATE,
                values,
                BlackBoxMediaContract.STATE_COL_USER_ID + "=? AND " + BlackBoxMediaContract.STATE_COL_ROOT_PATH + "=?",
                new String[]{String.valueOf(userId), rootPath}
        );
        if (updated == 0) {
            database.insert(BlackBoxMediaContract.TABLE_SCAN_STATE, null, values);
        }
    }

    private static void refreshStoredState(int userId, int mediaType) {
        synchronized (DB_LOCK) {
            SQLiteDatabase database = getDatabaseLocked();
            if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE || mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_NONE) {
                saveRootStateLocked(database, userId, getImagesRoot(userId).getAbsolutePath(), RootState.compute(getImagesRoot(userId)));
            }
            if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO || mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_NONE) {
                saveRootStateLocked(database, userId, getVideosRoot(userId).getAbsolutePath(), RootState.compute(getVideosRoot(userId)));
            }
        }
    }

    private static SQLiteDatabase getDatabaseLocked() {
        if (sDatabase != null && sDatabase.isOpen()) {
            return sDatabase;
        }
        BEnvironment.load();
        File databaseFile = new File(BEnvironment.getSystemDir(), DATABASE_NAME);
        sDatabase = SQLiteDatabase.openOrCreateDatabase(databaseFile, null);
        ensureSchemaLocked(sDatabase);
        return sDatabase;
    }

    private static void ensureSchemaLocked(SQLiteDatabase database) {
        if (database.getVersion() == DATABASE_VERSION) {
            return;
        }
        database.beginTransaction();
        try {
            createMediaSchema(database);
            database.execSQL("ALTER TABLE media_entries ADD COLUMN orientation INTEGER NOT NULL DEFAULT 0");
            database.setVersion(DATABASE_VERSION);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    private static void createMediaSchema(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE IF NOT EXISTS " + BlackBoxMediaContract.TABLE_MEDIA + " ("
                        + BlackBoxMediaContract.COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + BlackBoxMediaContract.COL_USER_ID + " INTEGER NOT NULL, "
                        + BlackBoxMediaContract.COL_MEDIA_TYPE + " INTEGER NOT NULL, "
                        + BlackBoxMediaContract.COL_DISPLAY_NAME + " TEXT NOT NULL, "
                        + BlackBoxMediaContract.COL_TITLE + " TEXT, "
                        + BlackBoxMediaContract.COL_MIME_TYPE + " TEXT, "
                        + BlackBoxMediaContract.COL_SIZE + " INTEGER NOT NULL DEFAULT 0, "
                        + BlackBoxMediaContract.COL_DATE_ADDED + " INTEGER NOT NULL DEFAULT 0, "
                        + BlackBoxMediaContract.COL_DATE_MODIFIED + " INTEGER NOT NULL DEFAULT 0, "
                        + BlackBoxMediaContract.COL_DATE_TAKEN + " INTEGER NOT NULL DEFAULT 0, "
                        + BlackBoxMediaContract.COL_RELATIVE_PATH + " TEXT NOT NULL, "
                        + BlackBoxMediaContract.COL_BUCKET_DISPLAY_NAME + " TEXT, "
                        + BlackBoxMediaContract.COL_BUCKET_ID + " INTEGER NOT NULL DEFAULT 0, "
                        + BlackBoxMediaContract.COL_WIDTH + " INTEGER NOT NULL DEFAULT 0, "
                        + BlackBoxMediaContract.COL_HEIGHT + " INTEGER NOT NULL DEFAULT 0, "
                        + BlackBoxMediaContract.COL_DURATION + " INTEGER NOT NULL DEFAULT 0, "
                        + BlackBoxMediaContract.COL_IS_PENDING + " INTEGER NOT NULL DEFAULT 0, "
                        + BlackBoxMediaContract.COL_DATA + " TEXT NOT NULL, "
                        + BlackBoxMediaContract.COL_CANONICAL_PATH + " TEXT NOT NULL, "
                        + BlackBoxMediaContract.COL_ROOT_PATH + " TEXT NOT NULL"
                        + ")"
        );
        database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_media_user_path ON "
                        + BlackBoxMediaContract.TABLE_MEDIA + "("
                        + BlackBoxMediaContract.COL_USER_ID + ", "
                        + BlackBoxMediaContract.COL_CANONICAL_PATH + ")"
        );
        database.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_media_user_type ON "
                        + BlackBoxMediaContract.TABLE_MEDIA + "("
                        + BlackBoxMediaContract.COL_USER_ID + ", "
                        + BlackBoxMediaContract.COL_MEDIA_TYPE + ")"
        );
        database.execSQL(
                "CREATE TABLE IF NOT EXISTS " + BlackBoxMediaContract.TABLE_SCAN_STATE + " ("
                        + BlackBoxMediaContract.STATE_COL_USER_ID + " INTEGER NOT NULL, "
                        + BlackBoxMediaContract.STATE_COL_ROOT_PATH + " TEXT NOT NULL, "
                        + BlackBoxMediaContract.STATE_COL_ROOT_LAST_MODIFIED + " INTEGER NOT NULL DEFAULT 0, "
                        + BlackBoxMediaContract.STATE_COL_FILE_COUNT + " INTEGER NOT NULL DEFAULT 0, "
                        + BlackBoxMediaContract.STATE_COL_MAX_MODIFIED + " INTEGER NOT NULL DEFAULT 0, "
                        + "PRIMARY KEY (" + BlackBoxMediaContract.STATE_COL_USER_ID + ", " + BlackBoxMediaContract.STATE_COL_ROOT_PATH + ")"
                        + ")"
        );
    }

    private static void ensureRoots(int userId) {
        ensureRootExists(getImagesRoot(userId));
        ensureRootExists(getVideosRoot(userId));
    }

    private static void ensureRootExists(File root) {
        if (root != null && !root.exists()) {
            BzFileUtils.mkdirs(root);
        }
    }

    private static String canonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    private static List<File> collectMediaFiles(File root, int mediaType) {
        List<File> files = new ArrayList<>();
        if (root == null || !root.exists()) {
            return files;
        }
        ArrayDeque<File> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            File current = stack.pop();
            File[] children = current.listFiles();
            if (children == null) {
                continue;
            }
            for (File child : children) {
                if (child.isDirectory()) {
                    stack.push(child);
                } else if (child.isFile()) {
                    String mimeType = BlackBoxMediaContract.inferMimeType(child);
                    if (BlackBoxMediaContract.mediaTypeForMime(mimeType) == mediaType) {
                        files.add(child);
                    }
                }
            }
        }
        return files;
    }

    private static String queryDisplayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to query display name: " + e.getMessage());
        }
        String lastSegment = uri.getLastPathSegment();
        if (TextUtils.isEmpty(lastSegment)) {
            return null;
        }
        int slash = lastSegment.lastIndexOf('/');
        return slash >= 0 ? lastSegment.substring(slash + 1) : lastSegment;
    }

    private static String guessMimeFromName(String displayName) {
        if (TextUtils.isEmpty(displayName)) {
            return null;
        }
        String extension = MimeTypeMap.getFileExtensionFromUrl(displayName);
        if (TextUtils.isEmpty(extension)) {
            int dotIndex = displayName.lastIndexOf('.');
            if (dotIndex >= 0 && dotIndex < displayName.length() - 1) {
                extension = displayName.substring(dotIndex + 1);
            }
        }
        if (TextUtils.isEmpty(extension)) {
            return null;
        }
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(Locale.ROOT));
    }

    private static String buildSafeDisplayName(String displayName, String mimeType, int mediaType) {
        String cleaned = TextUtils.isEmpty(displayName) ? "media_" + System.currentTimeMillis() : displayName.trim();
        cleaned = cleaned.replaceAll("[\\\\/:*?\"<>|]+", "_");
        if (TextUtils.isEmpty(cleaned)) {
            cleaned = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    ? "video_" + System.currentTimeMillis()
                    : "image_" + System.currentTimeMillis();
        }
        if (!cleaned.contains(".") && !TextUtils.isEmpty(mimeType)) {
            String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
            if (!TextUtils.isEmpty(extension)) {
                cleaned = cleaned + "." + extension;
            }
        }
        return cleaned;
    }

    private static File uniqueFile(File directory, String displayName) {
        File candidate = new File(directory, displayName);
        if (!candidate.exists()) {
            return candidate;
        }
        String baseName = displayName;
        String extension = "";
        int dotIndex = displayName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = displayName.substring(0, dotIndex);
            extension = displayName.substring(dotIndex);
        }
        int suffix = 1;
        while (candidate.exists()) {
            candidate = new File(directory, baseName + "_" + suffix + extension);
            suffix++;
        }
        return candidate;
    }

    private static String titleFromDisplayName(String displayName) {
        if (TextUtils.isEmpty(displayName)) {
            return "";
        }
        int dotIndex = displayName.lastIndexOf('.');
        return dotIndex > 0 ? displayName.substring(0, dotIndex) : displayName;
    }

    private static String normalizeRelativePath(String relativePath, int mediaType) {
        String fallback = BlackBoxMediaContract.defaultRelativePathForMediaType(mediaType);
        if (TextUtils.isEmpty(relativePath)) {
            return fallback;
        }
        String normalized = relativePath.replace('\\', '/');
        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }
        List<String> parts = java.util.Arrays.asList(normalized.split("/"));
        if (parts.contains("..") || parts.contains(SELECTED_DIR)) return fallback;
        if (!normalized.startsWith(BlackBoxMediaContract.ROOT_IMAGES)
                && !normalized.startsWith(BlackBoxMediaContract.ROOT_VIDEOS)) {
            return fallback;
        }
        return normalized;
    }

    private static File resolveTargetDir(int userId, String relativePath, int mediaType) {
        String normalized = normalizeRelativePath(relativePath, mediaType);
        return new File(BEnvironment.getExternalUserDir(userId), normalized);
    }

    private static String buildRelativePath(int userId, File file, int mediaType) {
        File root = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ? getVideosRoot(userId) : getImagesRoot(userId);
        File parent = file.getParentFile();
        if (parent != null && new File(root, SELECTED_DIR).equals(parent.getParentFile())) {
            return BlackBoxMediaContract.defaultRelativePathForMediaType(mediaType);
        }
        String rootPath = root.getAbsolutePath();
        String filePath = file.getParentFile() != null ? file.getParentFile().getAbsolutePath() : rootPath;
        String suffix = filePath.startsWith(rootPath) ? filePath.substring(rootPath.length()) : "";
        if (suffix.startsWith(File.separator)) {
            suffix = suffix.substring(1);
        }
        String base = BlackBoxMediaContract.defaultRelativePathForMediaType(mediaType);
        if (TextUtils.isEmpty(suffix)) {
            return base;
        }
        return base + suffix.replace(File.separatorChar, '/') + "/";
    }

    private static String bucketDisplayName(String relativePath) {
        if (TextUtils.isEmpty(relativePath)) {
            return "BlackBoxGallery";
        }
        String normalized = relativePath;
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static int bucketId(String relativePath) {
        return bucketDisplayName(relativePath).toLowerCase(Locale.ROOT).hashCode();
    }

    private static MediaMetadata readMetadata(File file, int mediaType, String forcedMimeType) {
        MediaMetadata metadata = new MediaMetadata();
        metadata.mimeType = !TextUtils.isEmpty(forcedMimeType) ? forcedMimeType : BlackBoxMediaContract.inferMimeType(file);
        if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            metadata.width = Math.max(options.outWidth, 0);
            metadata.height = Math.max(options.outHeight, 0);
            return metadata;
        }

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            metadata.width = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            metadata.height = parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            metadata.duration = parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (Exception e) {
            Slog.w(TAG, "Failed to read video metadata for " + file.getName() + ": " + e.getMessage());
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        return metadata;
    }

    private static int parseInt(String value) {
        if (TextUtils.isEmpty(value)) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static long parseLong(String value) {
        if (TextUtils.isEmpty(value)) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static final class MediaMetadata {
        private String mimeType;
        private int width;
        private int height;
        private long duration;
    }

    private static final class RootState {
        private static final RootState EMPTY = new RootState(0L, 0, 0L);

        private final long rootLastModified;
        private final int fileCount;
        private final long maxModified;

        private RootState(long rootLastModified, int fileCount, long maxModified) {
            this.rootLastModified = rootLastModified;
            this.fileCount = fileCount;
            this.maxModified = maxModified;
        }

        private boolean sameAs(RootState other) {
            return other != null
                    && rootLastModified == other.rootLastModified
                    && fileCount == other.fileCount
                    && maxModified == other.maxModified;
        }

        private static RootState compute(File root) {
            if (root == null || !root.exists()) {
                return EMPTY;
            }
            long maxModified = root.lastModified();
            int fileCount = 0;
            ArrayDeque<File> stack = new ArrayDeque<>();
            stack.push(root);
            while (!stack.isEmpty()) {
                File current = stack.pop();
                File[] children = current.listFiles();
                if (children == null) {
                    continue;
                }
                for (File child : children) {
                    if (child.isDirectory()) {
                        stack.push(child);
                    } else if (child.isFile()) {
                        String mimeType = BlackBoxMediaContract.inferMimeType(child);
                        if (BlackBoxMediaContract.isSupportedMime(mimeType)) {
                            fileCount++;
                            maxModified = Math.max(maxModified, child.lastModified());
                        }
                    }
                }
            }
            return new RootState(root.lastModified(), fileCount, maxModified);
        }
    }
}
