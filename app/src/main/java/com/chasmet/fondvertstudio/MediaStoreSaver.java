package com.chasmet.fondvertstudio;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class MediaStoreSaver {
    private static final String ALBUM = "FondVertStudio";

    private MediaStoreSaver() {
    }

    public static Uri savePng(Context context, Bitmap bitmap, String displayName)
            throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + ALBUM);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("Impossible de créer l’image");
            }
            try (OutputStream stream = resolver.openOutputStream(uri)) {
                if (stream == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw new IOException("Échec de l’écriture PNG");
                }
            } catch (IOException error) {
                resolver.delete(uri, null, null);
                throw error;
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return uri;
        }

        File directory = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), ALBUM);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Impossible de créer le dossier Photos");
        }
        File output = new File(directory, displayName);
        try (OutputStream stream = new FileOutputStream(output)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw new IOException("Échec de l’écriture PNG");
            }
        }
        MediaScannerConnection.scanFile(context,
                new String[]{output.getAbsolutePath()}, new String[]{"image/png"}, null);
        return Uri.fromFile(output);
    }

    public static Uri saveVideo(Context context, File source, String displayName)
            throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/" + ALBUM);
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("Impossible de créer la vidéo");
            }
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = resolver.openOutputStream(uri)) {
                if (output == null) {
                    throw new IOException("Stockage vidéo inaccessible");
                }
                copy(input, output);
            } catch (IOException error) {
                resolver.delete(uri, null, null);
                throw error;
            }
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return uri;
        }

        File directory = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES), ALBUM);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Impossible de créer le dossier Vidéos");
        }
        File output = new File(directory, displayName);
        try (InputStream input = new FileInputStream(source);
             OutputStream stream = new FileOutputStream(output)) {
            copy(input, stream);
        }
        MediaScannerConnection.scanFile(context,
                new String[]{output.getAbsolutePath()}, new String[]{"video/mp4"}, null);
        return Uri.fromFile(output);
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
    }
}
