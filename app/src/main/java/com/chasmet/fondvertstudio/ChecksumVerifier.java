package com.chasmet.fondvertstudio;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;

/** Vérification SHA-256 indépendante du gestionnaire de téléchargement. */
final class ChecksumVerifier {
    private ChecksumVerifier() {
    }

    static void verifySha256(File file, String checksumDocument) throws IOException {
        String expected = extractSha256(checksumDocument);
        String actual = sha256(file);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IOException("APK refusé : contrôle SHA-256 incorrect");
        }
    }

    static String extractSha256(String checksumDocument) throws IOException {
        if (checksumDocument == null) throw new IOException("Contrôle SHA-256 absent");
        String[] tokens = checksumDocument.trim().split("\\s+");
        for (String token : tokens) {
            if (token.matches("(?i)[0-9a-f]{64}")) return token.toLowerCase(Locale.ROOT);
        }
        throw new IOException("Contrôle SHA-256 invalide");
    }

    static String sha256(File file) throws IOException {
        if (file == null || !file.isFile()) throw new IOException("Fichier APK absent");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[128 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder value = new StringBuilder(64);
            for (byte item : digest.digest()) {
                value.append(String.format(Locale.ROOT, "%02x", item & 0xFF));
            }
            return value.toString();
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Calcul SHA-256 impossible", error);
        }
    }
}
