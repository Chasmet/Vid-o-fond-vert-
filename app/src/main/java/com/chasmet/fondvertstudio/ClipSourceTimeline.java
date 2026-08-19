package com.chasmet.fondvertstudio;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Manifeste des prises caméra réellement tournées, dans l'ordre du montage. */
final class ClipSourceTimeline {
    static final class Segment {
        final String sourcePath;
        final BackgroundSpec.Type backgroundType;
        final Uri backgroundUri;
        final int backgroundColor;
        final String label;
        final long durationMs;

        Segment(String sourcePath, BackgroundSpec.Type backgroundType, Uri backgroundUri,
                int backgroundColor, String label, long durationMs) {
            this.sourcePath = sourcePath;
            this.backgroundType = backgroundType == null
                    ? BackgroundSpec.Type.COLOR : backgroundType;
            this.backgroundUri = backgroundUri;
            this.backgroundColor = backgroundColor;
            this.label = label == null ? "Décor" : label;
            this.durationMs = Math.max(1L, durationMs);
        }
    }

    private final ArrayList<Segment> segments = new ArrayList<>();

    void clear() {
        segments.clear();
    }

    void add(File source, BackgroundSpec spec, String label, long durationMs) {
        if (source == null) return;
        segments.add(new Segment(source.getAbsolutePath(), spec.getType(), spec.getUri(),
                spec.getColor(), label, durationMs));
    }

    void add(Segment segment) {
        if (segment != null) segments.add(segment);
    }

    boolean isEmpty() {
        return segments.isEmpty();
    }

    int size() {
        return segments.size();
    }

    List<Segment> segments() {
        return Collections.unmodifiableList(segments);
    }

    long totalDurationMs() {
        long total = 0L;
        for (Segment segment : segments) total += segment.durationMs;
        return total;
    }

    File write(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Impossible de créer la timeline des prises");
        }
        JSONArray array = new JSONArray();
        try {
            for (Segment segment : segments) {
                JSONObject object = new JSONObject();
                object.put("source", segment.sourcePath);
                object.put("backgroundType", segment.backgroundType.name());
                object.put("backgroundColor", segment.backgroundColor);
                object.put("label", segment.label);
                object.put("durationMs", segment.durationMs);
                if (segment.backgroundUri != null) {
                    object.put("backgroundUri", segment.backgroundUri.toString());
                }
                array.put(object);
            }
        } catch (JSONException error) {
            throw new IOException("Timeline des prises invalide", error);
        }
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write(array.toString());
        }
        return file;
    }

    static ClipSourceTimeline read(File file) throws IOException {
        ClipSourceTimeline timeline = new ClipSourceTimeline();
        StringBuilder json = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) json.append(line);
        }
        try {
            JSONArray array = new JSONArray(json.toString());
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                BackgroundSpec.Type type;
                try {
                    type = BackgroundSpec.Type.valueOf(
                            object.optString("backgroundType", "COLOR"));
                } catch (IllegalArgumentException ignored) {
                    type = BackgroundSpec.Type.COLOR;
                }
                String uriValue = object.optString("backgroundUri", "");
                timeline.add(new Segment(
                        object.getString("source"),
                        type,
                        uriValue.isEmpty() ? null : Uri.parse(uriValue),
                        object.optInt("backgroundColor", 0xFF00FF00),
                        object.optString("label", "Décor"),
                        object.optLong("durationMs", 1L)));
            }
        } catch (JSONException error) {
            throw new IOException("Impossible de lire la timeline des prises", error);
        }
        return timeline;
    }
}
