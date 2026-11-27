package com.chasmet.fondvertstudio;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Sauvegarde atomique du projet Fond vert courant.
 *
 * Les URI choisies via le sélecteur Android conservent leur autorisation de lecture et les
 * prises caméra restent dans l'espace privé de l'application. Une mise à jour de l'APK ne
 * supprime donc pas le montage en cours.
 */
final class ProjectRepository {
    static final String EXTRA_RESUME_PROJECT = "resume_green_screen_project";

    static final String STATUS_PAUSED = "PAUSED";
    static final String STATUS_FINALIZING = "FINALIZING";
    static final String STATUS_READY_EFFECT = "READY_EFFECT";
    static final String STATUS_READY_RAW = "READY_RAW";

    private static final int SCHEMA_VERSION = 1;
    private static final String PROJECT_DIRECTORY = "projects/current";
    private static final String MANIFEST_NAME = "project.json";

    static final class Draft {
        String format = CaptureFormat.VERTICAL;
        String musicUri = "";
        int audioStartMs;
        int detectedAudioStartMs;
        int quality = 1080;
        int lensFacing;
        String backgroundType = BackgroundSpec.Type.COLOR.name();
        String backgroundUri = "";
        int backgroundColor = 0xFF00FF00;
        String backgroundLabel = "Fond vert";
        float subjectScale = SubjectTransformTimeline.DEFAULT_SCALE;
        float subjectCenterX = SubjectTransformTimeline.DEFAULT_CENTER_X;
        float subjectCenterY = SubjectTransformTimeline.DEFAULT_CENTER_Y;
        String status = STATUS_PAUSED;
        String renderedPath = "";
        String rendererMessage = "";
        boolean audioApplied = true;
        long updatedAt = System.currentTimeMillis();
        final ClipSourceTimeline timeline = new ClipSourceTimeline();

        boolean isResumable() {
            if (!renderedPath.isEmpty()) {
                File output = new File(renderedPath);
                if (output.isFile() && output.length() > 0L) return true;
            }
            return !timeline.isEmpty();
        }

        boolean isReady() {
            return STATUS_READY_EFFECT.equals(status) || STATUS_READY_RAW.equals(status);
        }
    }

    private final Context context;
    private final File directory;
    private final AtomicFile manifest;

    ProjectRepository(Context context) {
        this.context = context.getApplicationContext();
        directory = new File(this.context.getFilesDir(), PROJECT_DIRECTORY);
        manifest = new AtomicFile(new File(directory, MANIFEST_NAME));
    }

    synchronized void save(Draft draft) throws IOException {
        if (draft == null) throw new IOException("Projet absent");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Dossier Projets inaccessible");
        }
        draft.updatedAt = System.currentTimeMillis();
        byte[] bytes;
        try {
            bytes = toJson(draft).toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException error) {
            throw new IOException("Projet impossible à sérialiser", error);
        }

        FileOutputStream output = null;
        try {
            output = manifest.startWrite();
            output.write(bytes);
            output.flush();
            output.getFD().sync();
            manifest.finishWrite(output);
        } catch (Exception error) {
            if (output != null) manifest.failWrite(output);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Sauvegarde du projet impossible", error);
        }
    }

    synchronized Draft load() {
        if (!manifest.getBaseFile().isFile()) return null;
        try (FileInputStream input = manifest.openRead();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            Draft draft = fromJson(new JSONObject(
                    output.toString(StandardCharsets.UTF_8.name())));
            pruneMissingFiles(draft);
            return draft.isResumable() ? draft : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    synchronized boolean hasResumableDraft() {
        return load() != null;
    }

    synchronized File createProjectFile(String prefix, String extension) throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Dossier Projets inaccessible");
        }
        String safePrefix = prefix == null ? "project" : prefix.replaceAll("[^A-Za-z0-9_-]", "_");
        String safeExtension = extension == null ? "" : extension.replaceAll("[^A-Za-z0-9.]", "");
        return new File(directory, safePrefix + "_" + System.currentTimeMillis() + safeExtension);
    }

    synchronized File adoptOutput(File source) throws IOException {
        if (source == null || !source.isFile() || source.length() <= 0L) {
            throw new IOException("Vidéo finale absente");
        }
        File destination = createProjectFile("video_ready", ".mp4");
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[256 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
            output.getFD().sync();
        } catch (IOException error) {
            if (destination.exists()) destination.delete();
            throw error;
        }
        if (!sameFile(source, destination)) source.delete();
        return destination;
    }

    synchronized void clear(boolean deleteProjectMedia) {
        Draft draft = deleteProjectMedia ? load() : null;
        if (draft != null) {
            for (ClipSourceTimeline.Segment segment : draft.timeline.segments()) {
                deleteOwnedFile(new File(segment.sourcePath));
            }
            if (!draft.renderedPath.isEmpty()) deleteOwnedFile(new File(draft.renderedPath));
        }
        manifest.delete();
        if (directory.isDirectory()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) deleteOwnedFile(file);
            }
            directory.delete();
        }
    }

    private void pruneMissingFiles(Draft draft) {
        ClipSourceTimeline valid = new ClipSourceTimeline();
        for (ClipSourceTimeline.Segment segment : draft.timeline.segments()) {
            File file = new File(segment.sourcePath);
            if (file.isFile() && file.length() > 0L) valid.add(segment);
        }
        draft.timeline.replaceWith(valid);
        if (!draft.renderedPath.isEmpty()) {
            File output = new File(draft.renderedPath);
            if (!output.isFile() || output.length() <= 0L) draft.renderedPath = "";
        }
    }

    private static JSONObject toJson(Draft draft) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schema", SCHEMA_VERSION);
        root.put("format", draft.format);
        root.put("musicUri", draft.musicUri);
        root.put("audioStartMs", draft.audioStartMs);
        root.put("detectedAudioStartMs", draft.detectedAudioStartMs);
        root.put("quality", draft.quality);
        root.put("lensFacing", draft.lensFacing);
        root.put("backgroundType", draft.backgroundType);
        root.put("backgroundUri", draft.backgroundUri);
        root.put("backgroundColor", draft.backgroundColor);
        root.put("backgroundLabel", draft.backgroundLabel);
        root.put("subjectScale", draft.subjectScale);
        root.put("subjectCenterX", draft.subjectCenterX);
        root.put("subjectCenterY", draft.subjectCenterY);
        root.put("status", draft.status);
        root.put("renderedPath", draft.renderedPath);
        root.put("rendererMessage", draft.rendererMessage);
        root.put("audioApplied", draft.audioApplied);
        root.put("updatedAt", draft.updatedAt);

        JSONArray segments = new JSONArray();
        for (ClipSourceTimeline.Segment segment : draft.timeline.segments()) {
            JSONObject item = new JSONObject();
            item.put("sourcePath", segment.sourcePath);
            item.put("backgroundType", segment.backgroundType.name());
            item.put("backgroundUri", segment.backgroundUri == null
                    ? "" : segment.backgroundUri.toString());
            item.put("backgroundColor", segment.backgroundColor);
            item.put("label", segment.label);
            item.put("durationMs", segment.durationMs);
            segments.put(item);
        }
        root.put("segments", segments);
        return root;
    }

    private static Draft fromJson(JSONObject root) throws JSONException {
        if (root.optInt("schema", -1) != SCHEMA_VERSION) {
            throw new JSONException("Version de projet incompatible");
        }
        Draft draft = new Draft();
        draft.format = CaptureFormat.sanitize(root.optString("format", CaptureFormat.VERTICAL));
        draft.musicUri = root.optString("musicUri", "");
        draft.audioStartMs = Math.max(0, root.optInt("audioStartMs", 0));
        draft.detectedAudioStartMs = Math.max(0, root.optInt("detectedAudioStartMs", 0));
        draft.quality = root.optInt("quality", 1080) >= 1080 ? 1080 : 720;
        draft.lensFacing = root.optInt("lensFacing", 0);
        draft.backgroundType = root.optString(
                "backgroundType", BackgroundSpec.Type.COLOR.name());
        draft.backgroundUri = root.optString("backgroundUri", "");
        draft.backgroundColor = root.optInt("backgroundColor", 0xFF00FF00);
        draft.backgroundLabel = root.optString("backgroundLabel", "Fond vert");
        draft.subjectScale = (float) root.optDouble(
                "subjectScale", SubjectTransformTimeline.DEFAULT_SCALE);
        draft.subjectCenterX = (float) root.optDouble(
                "subjectCenterX", SubjectTransformTimeline.DEFAULT_CENTER_X);
        draft.subjectCenterY = (float) root.optDouble(
                "subjectCenterY", SubjectTransformTimeline.DEFAULT_CENTER_Y);
        draft.status = root.optString("status", STATUS_PAUSED);
        draft.renderedPath = root.optString("renderedPath", "");
        draft.rendererMessage = root.optString("rendererMessage", "");
        draft.audioApplied = root.optBoolean("audioApplied", true);
        draft.updatedAt = root.optLong("updatedAt", 0L);

        JSONArray segments = root.optJSONArray("segments");
        if (segments != null) {
            for (int i = 0; i < segments.length(); i++) {
                JSONObject item = segments.optJSONObject(i);
                if (item == null) continue;
                String sourcePath = item.optString("sourcePath", "");
                if (sourcePath.isEmpty()) continue;
                BackgroundSpec.Type type;
                try {
                    type = BackgroundSpec.Type.valueOf(item.optString(
                            "backgroundType", BackgroundSpec.Type.COLOR.name()));
                } catch (IllegalArgumentException ignored) {
                    type = BackgroundSpec.Type.COLOR;
                }
                String uri = item.optString("backgroundUri", "");
                draft.timeline.add(new ClipSourceTimeline.Segment(
                        sourcePath,
                        type,
                        uri.isEmpty() ? null : Uri.parse(uri),
                        item.optInt("backgroundColor", 0xFF00FF00),
                        item.optString("label", "Décor"),
                        Math.max(1L, item.optLong("durationMs", 1L))));
            }
        }
        return draft;
    }

    private void deleteOwnedFile(File file) {
        if (file == null || !file.exists()) return;
        try {
            String target = file.getCanonicalPath();
            if (!isUnder(target, context.getFilesDir())
                    && !isUnder(target, context.getCacheDir())
                    && !isUnder(target, context.getExternalFilesDir(null))) {
                return;
            }
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) {
                    for (File child : children) deleteOwnedFile(child);
                }
            }
            file.delete();
        } catch (IOException ignored) {
        }
    }

    private static boolean isUnder(String target, File root) throws IOException {
        if (root == null) return false;
        String rootPath = root.getCanonicalPath();
        return target.equals(rootPath) || target.startsWith(rootPath + File.separator);
    }

    private static boolean sameFile(File left, File right) {
        try {
            return left.getCanonicalFile().equals(right.getCanonicalFile());
        } catch (IOException ignored) {
            return left.equals(right);
        }
    }
}
