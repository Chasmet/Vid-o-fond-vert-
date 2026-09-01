package com.chasmet.fondvertstudio;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppUpdateManager {
    private static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/Chasmet/Vid-o-fond-vert-/releases/latest";
    private static final String USER_AGENT = "FondVertStudio-Android-Updater";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AppUpdateManager() {
    }

    public interface CheckCallback {
        void onSuccess(UpdateInfo info);
        void onError(String message);
    }

    public interface DownloadCallback {
        void onStarted(long totalBytes);
        void onProgress(int percent, long downloadedBytes, long totalBytes, long bytesPerSecond);
        void onReady(File apkFile);
        void onError(String message);
    }

    public static final class UpdateInfo {
        public final String version;
        public final String title;
        public final String notes;
        public final String publishedAt;
        public final String downloadUrl;
        public final long assetSize;
        public final boolean newer;

        UpdateInfo(String version, String title, String notes, String publishedAt,
                   String downloadUrl, long assetSize, boolean newer) {
            this.version = version;
            this.title = title;
            this.notes = notes;
            this.publishedAt = publishedAt;
            this.downloadUrl = downloadUrl;
            this.assetSize = assetSize;
            this.newer = newer;
        }
    }

    public static void checkLatest(Context context, CheckCallback callback) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = openConnection(LATEST_RELEASE_URL);
                int responseCode = connection.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("GitHub répond avec le code " + responseCode);
                }

                String json = readAll(connection.getInputStream());
                JSONObject release = new JSONObject(json);
                String tag = release.optString("tag_name", "");
                String version = normalizeVersion(tag);
                String title = release.optString("name", tag);
                String notes = release.optString("body", "");
                String publishedAt = release.optString("published_at", "");

                JSONArray assets = release.optJSONArray("assets");
                String apkUrl = null;
                long apkSize = -1L;
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        if (asset == null) {
                            continue;
                        }
                        String name = asset.optString("name", "");
                        if (name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", null);
                            apkSize = asset.optLong("size", -1L);
                            break;
                        }
                    }
                }

                if (apkUrl == null || apkUrl.isEmpty()) {
                    throw new IllegalStateException("La dernière Release ne contient aucun APK.");
                }

                String currentVersion = getCurrentVersionName(appContext);
                boolean newer = compareVersions(version, currentVersion) > 0;
                UpdateInfo info = new UpdateInfo(version, title, notes, publishedAt,
                        apkUrl, apkSize, newer);
                MAIN.post(() -> callback.onSuccess(info));
            } catch (Exception error) {
                String message = cleanError(error, "Impossible de vérifier les mises à jour.");
                MAIN.post(() -> callback.onError(message));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    public static void download(Context context, UpdateInfo info, DownloadCallback callback) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                File baseDirectory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (baseDirectory == null) {
                    baseDirectory = new File(appContext.getFilesDir(), "updates");
                }
                if (!baseDirectory.exists() && !baseDirectory.mkdirs()) {
                    throw new IllegalStateException("Impossible de préparer le dossier de mise à jour.");
                }

                File apkFile = new File(baseDirectory,
                        "Fond-Vert-Studio-v" + sanitizeVersion(info.version) + ".apk");
                if (apkFile.exists() && !apkFile.delete()) {
                    throw new IllegalStateException("Impossible de remplacer l'ancien APK téléchargé.");
                }

                connection = openConnection(info.downloadUrl);
                connection.setInstanceFollowRedirects(true);
                int responseCode = connection.getResponseCode();
                if (responseCode < 200 || responseCode >= 300) {
                    throw new IllegalStateException("Téléchargement refusé, code " + responseCode);
                }

                long contentLength = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                        ? connection.getContentLengthLong() : connection.getContentLength();
                long totalBytes = contentLength > 0 ? contentLength : info.assetSize;
                long finalTotalBytes = totalBytes;
                MAIN.post(() -> callback.onStarted(finalTotalBytes));

                long downloaded = 0L;
                long startedAt = System.currentTimeMillis();
                long lastUiAt = 0L;
                byte[] buffer = new byte[128 * 1024];

                try (InputStream input = new BufferedInputStream(connection.getInputStream());
                     BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(apkFile))) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        downloaded += count;
                        long now = System.currentTimeMillis();
                        if (now - lastUiAt >= 180L || (totalBytes > 0 && downloaded >= totalBytes)) {
                            lastUiAt = now;
                            int percent = totalBytes > 0
                                    ? (int) Math.min(100L, (downloaded * 100L) / totalBytes) : -1;
                            long elapsedMs = Math.max(1L, now - startedAt);
                            long speed = (downloaded * 1000L) / elapsedMs;
                            long progressDownloaded = downloaded;
                            MAIN.post(() -> callback.onProgress(
                                    percent, progressDownloaded, finalTotalBytes, speed));
                        }
                    }
                    output.flush();
                }

                validateDownloadedApk(appContext, apkFile);
                MAIN.post(() -> callback.onReady(apkFile));
            } catch (Exception error) {
                String message = cleanError(error, "Échec du téléchargement de la mise à jour.");
                MAIN.post(() -> callback.onError(message));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    public static boolean installDownloadedApk(Activity activity, File apkFile) {
        if (apkFile == null || !apkFile.isFile()) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            Intent permissionIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(permissionIntent);
            return false;
        }

        Uri apkUri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".fileprovider", apkFile);
        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(installIntent);
        return true;
    }

    public static String getCurrentVersionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "0" : info.versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            return "0";
        }
    }

    private static void validateDownloadedApk(Context context, File apkFile) throws Exception {
        PackageManager packageManager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo candidate = packageManager.getPackageArchiveInfo(apkFile.getAbsolutePath(), flags);
        if (candidate == null) {
            throw new IllegalStateException("Le fichier téléchargé n'est pas un APK Android valide.");
        }
        if (!context.getPackageName().equals(candidate.packageName)) {
            throw new IllegalStateException("APK refusé : le nom de l'application ne correspond pas.");
        }

        PackageInfo current = packageManager.getPackageInfo(context.getPackageName(), flags);
        if (!sameSigner(current, candidate)) {
            throw new IllegalStateException(
                    "APK refusé : signature différente. Android ne peut pas mettre cette version à jour sans réinstallation.");
        }

        long currentCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? current.getLongVersionCode() : current.versionCode;
        long candidateCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? candidate.getLongVersionCode() : candidate.versionCode;
        if (candidateCode <= currentCode) {
            throw new IllegalStateException("APK refusé : la version téléchargée n'est pas plus récente.");
        }
    }

    private static boolean sameSigner(PackageInfo left, PackageInfo right) {
        Signature[] leftSignatures = signaturesOf(left);
        Signature[] rightSignatures = signaturesOf(right);
        if (leftSignatures.length == 0 || rightSignatures.length == 0) {
            return false;
        }
        for (Signature leftSignature : leftSignatures) {
            for (Signature rightSignature : rightSignatures) {
                if (Arrays.equals(leftSignature.toByteArray(), rightSignature.toByteArray())) {
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signaturesOf(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            if (info.signingInfo.hasMultipleSigners()) {
                Signature[] signers = info.signingInfo.getApkContentsSigners();
                return signers == null ? new Signature[0] : signers;
            }
            Signature[] history = info.signingInfo.getSigningCertificateHistory();
            return history == null ? new Signature[0] : history;
        }
        return info.signatures == null ? new Signature[0] : info.signatures;
    }

    private static HttpURLConnection openConnection(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        return connection;
    }

    private static String readAll(InputStream inputStream) throws Exception {
        try (InputStream input = inputStream;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    static int compareVersions(String left, String right) {
        String[] a = normalizeVersion(left).split("[.-]");
        String[] b = normalizeVersion(right).split("[.-]");
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int av = i < a.length ? parseLeadingNumber(a[i]) : 0;
            int bv = i < b.length ? parseLeadingNumber(b[i]) : 0;
            if (av != bv) {
                return Integer.compare(av, bv);
            }
        }
        return 0;
    }

    private static int parseLeadingNumber(String value) {
        int end = 0;
        while (end < value.length() && Character.isDigit(value.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(value.substring(0, end));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String normalizeVersion(String version) {
        if (version == null) {
            return "0";
        }
        String normalized = version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        return normalized.isEmpty() ? "0" : normalized;
    }

    private static String sanitizeVersion(String version) {
        return normalizeVersion(version).replaceAll("[^0-9A-Za-z._-]", "_");
    }

    private static String cleanError(Exception error, String fallback) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? fallback : message.trim();
    }
}
