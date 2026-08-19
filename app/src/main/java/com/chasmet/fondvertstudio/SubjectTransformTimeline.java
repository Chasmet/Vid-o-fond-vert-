package com.chasmet.fondvertstudio;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Enregistre puis interpole la position et la taille du sujet pendant une prise.
 * Le fichier reste volontairement simple et local afin de ne jamais dépasser la
 * taille maximale des paramètres WorkManager.
 */
public final class SubjectTransformTimeline {
    public static final float DEFAULT_SCALE = 1f;
    public static final float DEFAULT_CENTER_X = 0.5f;
    public static final float DEFAULT_CENTER_Y = 0.5f;
    public static final float MIN_SCALE = 0.22f;
    public static final float MAX_SCALE = 3f;

    public static final class Transform {
        public final long timeUs;
        public final float scale;
        public final float centerX;
        public final float centerY;

        Transform(long timeUs, float scale, float centerX, float centerY) {
            this.timeUs = Math.max(0L, timeUs);
            this.scale = clamp(scale, MIN_SCALE, MAX_SCALE);
            this.centerX = clamp(centerX, 0f, 1f);
            this.centerY = clamp(centerY, 0f, 1f);
        }
    }

    private final List<Transform> keyframes = new ArrayList<>();
    private int readCursor;

    public void clear() {
        keyframes.clear();
        readCursor = 0;
    }

    public boolean isEmpty() {
        return keyframes.isEmpty();
    }

    public void add(long timeUs, float scale, float centerX, float centerY) {
        Transform next = new Transform(timeUs, scale, centerX, centerY);
        int size = keyframes.size();
        if (size > 0 && next.timeUs <= keyframes.get(size - 1).timeUs) {
            keyframes.set(size - 1, next);
        } else {
            keyframes.add(next);
        }
    }

    public Transform at(long timeUs) {
        if (keyframes.isEmpty()) {
            return new Transform(timeUs, DEFAULT_SCALE, DEFAULT_CENTER_X, DEFAULT_CENTER_Y);
        }
        if (timeUs < keyframes.get(readCursor).timeUs) {
            readCursor = 0;
        }
        while (readCursor + 1 < keyframes.size()
                && keyframes.get(readCursor + 1).timeUs <= timeUs) {
            readCursor++;
        }
        Transform first = keyframes.get(readCursor);
        if (readCursor + 1 >= keyframes.size() || timeUs <= first.timeUs) {
            return new Transform(timeUs, first.scale, first.centerX, first.centerY);
        }
        Transform second = keyframes.get(readCursor + 1);
        float amount = (float) (timeUs - first.timeUs)
                / Math.max(1L, second.timeUs - first.timeUs);
        amount = clamp(amount, 0f, 1f);
        return new Transform(timeUs,
                lerp(first.scale, second.scale, amount),
                lerp(first.centerX, second.centerX, amount),
                lerp(first.centerY, second.centerY, amount));
    }

    public void write(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Dossier des mouvements inaccessible");
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            writer.write("# time_us,scale,center_x,center_y");
            writer.newLine();
            for (Transform keyframe : keyframes) {
                writer.write(String.format(Locale.US, "%d,%.6f,%.6f,%.6f",
                        keyframe.timeUs, keyframe.scale,
                        keyframe.centerX, keyframe.centerY));
                writer.newLine();
            }
        }
    }

    public static SubjectTransformTimeline read(File file) throws IOException {
        SubjectTransformTimeline timeline = new SubjectTransformTimeline();
        if (file == null || !file.isFile()) {
            return timeline;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] values = line.split(",");
                if (values.length != 4) {
                    continue;
                }
                try {
                    timeline.add(Long.parseLong(values[0]),
                            Float.parseFloat(values[1]),
                            Float.parseFloat(values[2]),
                            Float.parseFloat(values[3]));
                } catch (NumberFormatException ignored) {
                    // Une ligne incomplète ne doit pas faire perdre toute la prise.
                }
            }
        }
        return timeline;
    }

    private static float lerp(float first, float second, float amount) {
        return first + (second - first) * amount;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
