package com.chasmet.fondvertstudio;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Convertit une portion audio non directement compatible avec un MP4 Android
 * (notamment WAV/PCM) vers AAC/M4A avant le muxage avec la vidéo finale.
 */
@UnstableApi
final class AudioCompatibilityTranscoder {
    private AudioCompatibilityTranscoder() {
    }

    static void transcodeToAac(Context context, Uri inputUri, File outputFile,
                               long startUs, long durationUs) throws IOException {
        if (outputFile.exists() && !outputFile.delete()) {
            throw new IOException("Impossible de préparer la conversion audio");
        }
        File parent = outputFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Dossier audio temporaire inaccessible");
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Transformer> activeTransformer = new AtomicReference<>();
        Handler mainHandler = new Handler(Looper.getMainLooper());
        Context appContext = context.getApplicationContext();

        mainHandler.post(() -> {
            try {
                long startMs = Math.max(0L, startUs / 1000L);
                long requestedDurationMs = Math.max(1L, durationUs / 1000L);
                long endMs;
                try {
                    endMs = Math.addExact(startMs, requestedDurationMs + 50L);
                } catch (ArithmeticException ignored) {
                    endMs = Long.MAX_VALUE / 2L;
                }

                MediaItem mediaItem = new MediaItem.Builder()
                        .setUri(inputUri)
                        .setClippingConfiguration(
                                new MediaItem.ClippingConfiguration.Builder()
                                        .setStartPositionMs(startMs)
                                        .setEndPositionMs(endMs)
                                        .build())
                        .build();

                EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem)
                        .setRemoveVideo(true)
                        .build();

                Transformer transformer = new Transformer.Builder(appContext)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(new Transformer.Listener() {
                            @Override
                            public void onCompleted(Composition composition,
                                                    ExportResult exportResult) {
                                activeTransformer.set(null);
                                latch.countDown();
                            }

                            @Override
                            public void onError(Composition composition,
                                                ExportResult exportResult,
                                                ExportException exportException) {
                                failure.set(exportException);
                                activeTransformer.set(null);
                                latch.countDown();
                            }
                        })
                        .build();
                activeTransformer.set(transformer);
                transformer.start(editedMediaItem, outputFile.getAbsolutePath());
            } catch (Throwable error) {
                failure.set(error);
                activeTransformer.set(null);
                latch.countDown();
            }
        });

        long durationSeconds = Math.max(1L, durationUs / 1_000_000L);
        long timeoutSeconds = Math.max(120L, Math.min(600L, durationSeconds * 5L + 60L));
        boolean completed;
        try {
            completed = latch.await(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Conversion audio interrompue", error);
        }

        if (!completed) {
            Transformer transformer = activeTransformer.getAndSet(null);
            if (transformer != null) {
                mainHandler.post(() -> {
                    try {
                        transformer.cancel();
                    } catch (Exception ignored) {
                    }
                });
            }
            if (outputFile.exists()) outputFile.delete();
            throw new IOException("Conversion de la musique trop longue");
        }

        Throwable error = failure.get();
        if (error != null) {
            if (outputFile.exists()) outputFile.delete();
            String message = error.getMessage();
            throw new IOException(message == null || message.trim().isEmpty()
                    ? "Conversion de la musique impossible"
                    : "Conversion de la musique impossible : " + message, error);
        }

        if (!outputFile.isFile() || outputFile.length() <= 0L) {
            throw new IOException("Piste AAC temporaire introuvable");
        }
    }
}
