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
import java.util.Comparator;
import java.util.List;

/** Timeline des décors du mode clip. Chaque entrée devient active à startUs. */
final class ClipBackgroundTimeline {
    static final class Entry {
        final long startUs;
        final BackgroundSpec.Type type;
        final Uri uri;
        final int color;
        final String label;

        Entry(long startUs, BackgroundSpec.Type type, Uri uri, int color, String label) {
            this.startUs = Math.max(0L, startUs);
            this.type = type == null ? BackgroundSpec.Type.COLOR : type;
            this.uri = uri;
            this.color = color;
            this.label = label == null ? "Décor" : label;
        }
    }

    private final ArrayList<Entry> entries = new ArrayList<>();

    void clear() {
        entries.clear();
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    int size() {
        return entries.size();
    }

    List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    void addOrReplace(long startUs, BackgroundSpec spec, String label) {
        addOrReplace(startUs, spec.getType(), spec.getUri(), spec.getColor(), label);
    }

    void addOrReplace(long startUs, BackgroundSpec.Type type, Uri uri, int color, String label) {
        long safeStart = Math.max(0L, startUs);
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).startUs == safeStart) {
                entries.set(i, new Entry(safeStart, type, uri, color, label));
                sort();
                return;
            }
        }
        entries.add(new Entry(safeStart, type, uri, color, label));
        sort();
    }

    Entry at(long timeUs) {
        if (entries.isEmpty()) {
            return new Entry(0L, BackgroundSpec.Type.COLOR, null, 0xFF00FF00, "Fond vert");
        }
        Entry selected = entries.get(0);
        for (Entry entry : entries) {
            if (entry.startUs > timeUs) break;
            selected = entry;
        }
        return selected;
    }

    File write(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Impossible de créer la timeline des décors");
        }
        JSONArray array = new JSONArray();
        try {
            for (Entry entry : entries) {
                JSONObject object = new JSONObject();
                object.put("startUs", entry.startUs);
                object.put("type", entry.type.name());
                object.put("color", entry.color);
                object.put("label", entry.label);
                if (entry.uri != null) object.put("uri", entry.uri.toString());
                array.put(object);
            }
        } catch (JSONException error) {
            throw new IOException("Timeline décor invalide", error);
        }
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write(array.toString());
        }
        return file;
    }

    static ClipBackgroundTimeline read(File file) throws IOException {
        ClipBackgroundTimeline timeline = new ClipBackgroundTimeline();
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
                    type = BackgroundSpec.Type.valueOf(object.optString("type", "COLOR"));
                } catch (IllegalArgumentException ignored) {
                    type = BackgroundSpec.Type.COLOR;
                }
                String uriValue = object.optString("uri", null);
                timeline.addOrReplace(
                        object.optLong("startUs", 0L),
                        type,
                        uriValue == null || uriValue.isEmpty() ? null : Uri.parse(uriValue),
                        object.optInt("color", 0xFF00FF00),
                        object.optString("label", "Décor"));
            }
        } catch (JSONException error) {
            throw new IOException("Impossible de lire la timeline décor", error);
        }
        return timeline;
    }

    private void sort() {
        entries.sort(Comparator.comparingLong(entry -> entry.startUs));
    }
}
