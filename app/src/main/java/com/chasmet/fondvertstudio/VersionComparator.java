package com.chasmet.fondvertstudio;

/** Comparaison de versions numériques, isolée pour être testée sans Android. */
final class VersionComparator {
    private VersionComparator() {
    }

    static int compare(String left, String right) {
        String[] a = normalize(left).split("[.-]");
        String[] b = normalize(right).split("[.-]");
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int av = i < a.length ? parseLeadingNumber(a[i]) : 0;
            int bv = i < b.length ? parseLeadingNumber(b[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    static String normalize(String version) {
        if (version == null) return "0";
        String normalized = version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        return normalized.isEmpty() ? "0" : normalized;
    }

    private static int parseLeadingNumber(String value) {
        int end = 0;
        while (end < value.length() && Character.isDigit(value.charAt(end))) end++;
        if (end == 0) return 0;
        try {
            return Integer.parseInt(value.substring(0, end));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
