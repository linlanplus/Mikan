package top.niunaijun.blackbox.media;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.FileNotFoundException;
import java.util.List;

import top.niunaijun.blackbox.BlackBoxCore;

public class BlackBoxMediaProvider extends ContentProvider {
    public static final String METHOD_RESCAN = "rescan";
    public static final String EXTRA_USER_ID = "user_id";

    @Override
    public boolean onCreate() {
        return true;
    }

    private int owner(Uri uri) {
        int user = BlackBoxMediaContract.getUserId(uri);
        if (user < 0) throw new SecurityException("Invalid guest user");
        return BlackBoxCore.get().getGalleryOwner(user, uri.getQueryParameter(BlackBoxMediaContract.GUEST_PACKAGE));
    }

    private boolean showsSystemGallery(Uri uri) {
        return (BlackBoxMediaContract.isImagesUri(uri) || BlackBoxMediaContract.isVideosUri(uri) || BlackBoxMediaContract.isFilesUri(uri))
                && SystemGallery.isEnabled(getContext());
    }

    private boolean isPickerUri(Uri uri) {
        Uri publicUri = BlackBoxMediaContract.toPublicUri(uri);
        List<String> parts = publicUri.getPathSegments();
        return BlackBoxMediaContract.MEDIA_DOCUMENTS.equals(publicUri.getAuthority())
                || (!parts.isEmpty() && ("picker".equals(parts.get(0)) || "picker_get_content".equals(parts.get(0))));
    }

    private Uri systemSource(Uri uri) {
        if (!isPickerUri(uri)) return showsSystemGallery(uri) ? BlackBoxMediaStore.systemSource(uri) : null;
        Context ctx = getContext();
        if (!SystemGallery.isEnabled(ctx)) return null;
        Uri selected = BlackBoxMediaContract.toPublicUri(uri);
        List<String> parts = selected.getPathSegments();
        if (BlackBoxMediaContract.MEDIA_DOCUMENTS.equals(selected.getAuthority()) &&
                (parts.size() != 2 || !"document".equals(parts.get(0)) ||
                        !(parts.get(1).startsWith("image:") || parts.get(1).startsWith("video:")))) return null;
        return ctx.checkUriPermission(selected, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                == PackageManager.PERMISSION_GRANTED ? selected : null;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri,
                        @Nullable String[] projection,
                        @Nullable String selection,
                        @Nullable String[] selectionArgs,
                        @Nullable String sortOrder) {
        if (isPickerUri(uri)) {
            Uri selected = systemSource(uri);
            return selected == null ? new MatrixCursor(projection == null ? new String[]{"_id"} : projection)
                    : getContext().getContentResolver().query(selected, projection, selection, selectionArgs, sortOrder);
        }
        Cursor result = BlackBoxMediaStore.query(owner(uri), showsSystemGallery(uri), uri, projection, selection, selectionArgs, sortOrder);
        result.setNotificationUri(getContext().getContentResolver(), uri);
        return result;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        Uri source = systemSource(uri);
        if (source == null && isPickerUri(uri)) return null;
        return source == null ? BlackBoxMediaStore.getType(owner(uri), uri) : getContext().getContentResolver().getType(source);
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        if (isPickerUri(uri)) return null;
        try {
            Uri insertedUri = BlackBoxMediaStore.insert(owner(uri), uri, values != null ? values : new ContentValues());
            notifyMediaChange(uri);
            return insertedUri;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        if (isPickerUri(uri)) return 0;
        int deleted = BlackBoxMediaStore.delete(owner(uri), uri, selection, selectionArgs);
        if (deleted > 0) {
            notifyMediaChange(uri);
        }
        return deleted;
    }

    @Override
    public int update(@NonNull Uri uri,
                      @Nullable ContentValues values,
                      @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        if (isPickerUri(uri)) return 0;
        int updated = BlackBoxMediaStore.update(owner(uri), uri, values != null ? values : new ContentValues(), selection, selectionArgs);
        if (updated > 0) {
            notifyMediaChange(uri);
        }
        return updated;
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        Uri source = systemSource(uri);
        if (source != null) {
            if (!"r".equals(mode)) throw new FileNotFoundException("System gallery is read-only");
            return getContext().getContentResolver().openFileDescriptor(source, "r");
        }
        if (isPickerUri(uri)) throw new FileNotFoundException("No access to selected media");
        try {
            return BlackBoxMediaStore.openFile(owner(uri), uri, mode);
        } catch (java.io.IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override
    public AssetFileDescriptor openTypedAssetFile(@NonNull Uri uri, @NonNull String mime, Bundle options,
                                                  CancellationSignal signal) throws FileNotFoundException {
        Uri source = systemSource(uri);
        return source == null ? super.openTypedAssetFile(uri, mime, options, signal)
                : getContext().getContentResolver().openTypedAssetFileDescriptor(source, mime, options, signal);
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if (METHOD_RESCAN.equals(method)) {
            int userId = extras != null ? extras.getInt(EXTRA_USER_ID, 0) : 0;
            if (userId < 0) throw new IllegalArgumentException("Invalid guest user");
            BlackBoxMediaStore.rescanUser(BlackBoxCore.get().getGalleryOwner(userId, null));
            Bundle result = new Bundle();
            result.putBoolean("success", true);
            return result;
        }
        return super.call(method, arg, extras);
    }

    private void notifyMediaChange(Uri privateUri) {
        if (getContext() == null) {
            return;
        }
        getContext().getContentResolver().notifyChange(privateUri, null);
        notifyGalleries(getContext());
    }

    public static void notifyGalleries(Context context) {
        for (Uri collection : new Uri[]{MediaStore.Files.getContentUri("external"), MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI}) {
            context.getContentResolver().notifyChange(collection.buildUpon().authority(BlackBoxMediaContract.getPrivateAuthority()).build(), null);
        }
    }
}
