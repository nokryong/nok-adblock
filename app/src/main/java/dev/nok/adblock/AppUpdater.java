package dev.nok.adblock;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.*;
import android.os.Build;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Finds, downloads and verifies public releases. Installing always goes through Android's installer. */
public final class AppUpdater {
    public static final String OPEN_UPDATE = "dev.nok.OPEN_UPDATE", INSTALL_STATUS = "dev.nok.INSTALL_STATUS";
    private static final int JOB_ID = 7301;
    private static final long CHECK_INTERVAL = 6 * 60 * 60 * 1000L;
    private static final AtomicBoolean running = new AtomicBoolean();
    private AppUpdater() {}
    public static final class Ready {
        public final String version, sha256; public final long size; public final File file;
        Ready(String version, String sha256, long size, File file) { this.version = version; this.sha256 = sha256; this.size = size; this.file = file; }
    }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("nok", Context.MODE_PRIVATE); }
    private static File directory(Context c) { return new File(c.getCacheDir(), "updates"); }
    public static boolean autoEnabled(Context c) { return prefs(c).getBoolean("auto_update", true); }
    public static void setAutoEnabled(Context c, boolean enabled) {
        prefs(c).edit().putBoolean("auto_update", enabled).apply(); schedule(c);
    }
    public static String installedVersion(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException e) { return "?"; }
    }
    /** Jobs do not survive a reboot without another permission; the app and VPN start reschedule them. */
    public static void schedule(Context c) {
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        if (!autoEnabled(c)) { scheduler.cancel(JOB_ID); return; }
        if (scheduler.getPendingJob(JOB_ID) != null) return;
        scheduler.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, UpdateJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(CHECK_INTERVAL * 2).build());
    }
    public static boolean checkDue(Context c) {
        return autoEnabled(c) && System.currentTimeMillis() - prefs(c).getLong("update_checked", 0) >= CHECK_INTERVAL;
    }
    /** The verified download, if it is still newer than the installed app. */
    public static synchronized Ready ready(Context c) {
        SharedPreferences p = prefs(c);
        String version = p.getString("update_version", null), sha256 = p.getString("update_sha256", null);
        if (version == null || sha256 == null) return null;
        File file = new File(directory(c), sha256 + ".apk");
        long size = p.getLong("update_size", -1);
        try {
            if (UpdatePolicy.compare(version, installedVersion(c)) > 0 && file.isFile() && file.length() == size)
                return new Ready(version, sha256, size, file);
        } catch (IllegalArgumentException ignored) { /* Treated as no update. */ }
        clear(c); return null;
    }
    public static synchronized void clear(Context c) {
        prefs(c).edit().remove("update_version").remove("update_sha256").remove("update_size").apply();
        File[] files = directory(c).listFiles();
        if (files != null) for (File file : files) file.delete();
    }
    /** Runs on a worker thread. Returns the ready update, or null when already current. */
    public static Ready checkAndDownload(Context c) throws Exception {
        if (!running.compareAndSet(false, true)) throw new IOException("이미 확인 중입니다");
        try {
            UpdatePolicy.Asset best = latest();
            prefs(c).edit().putLong("update_checked", System.currentTimeMillis()).apply();
            if (best == null || UpdatePolicy.compare(best.version, installedVersion(c)) <= 0) { clear(c); return null; }
            Ready existing = ready(c);
            if (existing != null && existing.sha256.equals(best.sha256)) return existing;
            clear(c);
            File folder = directory(c); folder.mkdirs();
            File part = new File(folder, best.sha256 + ".part"), file = new File(folder, best.sha256 + ".apk");
            try {
                HttpURLConnection request = open(best.url);
                try (InputStream in = request.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                    UpdatePolicy.copyVerified(in, out, best.size, best.sha256, () -> false);
                } finally { request.disconnect(); }
                verifyPackage(c, part, best.version);
                if (!part.renameTo(file)) throw new IOException("업데이트 파일을 저장하지 못했습니다");
            } finally { part.delete(); }
            synchronized (AppUpdater.class) {
                prefs(c).edit().putString("update_version", best.version).putString("update_sha256", best.sha256).putLong("update_size", best.size).apply();
            }
            return new Ready(best.version, best.sha256, best.size, file);
        } finally { running.set(false); }
    }
    private static UpdatePolicy.Asset latest() throws Exception {
        HttpURLConnection request = open("https://api.github.com/repos/" + UpdatePolicy.REPOSITORY + "/releases?per_page=10");
        JSONArray releases;
        try (InputStream in = request.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > 2 * 1024 * 1024) throw new IOException("릴리즈 응답이 너무 큽니다");
                out.write(buffer, 0, n);
            }
            releases = new JSONArray(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } finally { request.disconnect(); }
        UpdatePolicy.Asset best = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.getJSONObject(i);
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue;
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;
            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.getJSONObject(j);
                UpdatePolicy.Asset candidate = UpdatePolicy.accept(asset.optString("name"), asset.optString("browser_download_url"),
                        asset.optLong("size"), asset.optString("digest"), asset.optString("state"));
                if (candidate != null && (best == null || UpdatePolicy.compare(candidate.version, best.version) > 0)) best = candidate;
            }
        }
        return best;
    }
    /** Follows redirects by hand so every hop stays on the expected GitHub hosts. */
    private static HttpURLConnection open(String address) throws IOException {
        URL url = new URL(address);
        for (int hop = 0; hop < 6; hop++) {
            if (!UpdatePolicy.allowedUrl(url)) throw new IOException("허용되지 않은 업데이트 주소입니다");
            HttpURLConnection request = (HttpURLConnection) url.openConnection();
            request.setInstanceFollowRedirects(false); request.setConnectTimeout(15000); request.setReadTimeout(30000);
            request.setRequestProperty("User-Agent", "NOK-Updater");
            request.setRequestProperty("Accept", url.getHost().equals("api.github.com") ? "application/vnd.github+json" : "application/octet-stream");
            int code = request.getResponseCode();
            if (code == 200) return request;
            String location = request.getHeaderField("Location");
            request.disconnect();
            if (code < 300 || code >= 400 || location == null) throw new IOException("업데이트 서버 응답 오류 (" + code + ")");
            url = new URL(url, location);
        }
        throw new IOException("다운로드 주소 이동이 너무 많습니다");
    }
    private static void verifyPackage(Context c, File apk, String version) throws Exception {
        PackageManager manager = c.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo current = manager.getPackageInfo(c.getPackageName(), flags), next = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (next == null || next.applicationInfo == null) throw new IOException("APK 정보를 읽지 못했습니다");
        Set<String> history = new HashSet<>();
        if (Build.VERSION.SDK_INT >= 28 && next.signingInfo != null && !next.signingInfo.hasMultipleSigners())
            history = digests(next.signingInfo.getSigningCertificateHistory());
        UpdatePolicy.checkIdentity(c.getPackageName(), next.packageName, code(current), code(next), version, next.versionName,
                next.applicationInfo.minSdkVersion, Build.VERSION.SDK_INT,
                UpdatePolicy.compatibleSigners(signers(current), signers(next), history));
    }
    private static long code(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }
    private static Set<String> signers(PackageInfo info) throws Exception {
        if (Build.VERSION.SDK_INT >= 28) return info.signingInfo == null ? new HashSet<>() : digests(info.signingInfo.getApkContentsSigners());
        return digests(info.signatures);
    }
    private static Set<String> digests(Signature[] signatures) throws Exception {
        Set<String> out = new HashSet<>();
        if (signatures != null) for (Signature s : signatures) out.add(UpdatePolicy.hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())));
        return out;
    }
    /** Re-verifies the stored file, then hands it to Android. UpdateReceiver opens the confirmation screen. */
    public static void install(Context c, Ready update) throws Exception {
        try (InputStream in = new FileInputStream(update.file); OutputStream none = new ByteArrayOutputStream() {
            @Override public void write(byte[] b, int off, int len) {}
        }) { UpdatePolicy.copyVerified(in, none, update.size, update.sha256, () -> false); }
        verifyPackage(c, update.file, update.version);
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName()); params.setSize(update.size);
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(update.file); OutputStream out = session.openWrite("base.apk", 0, update.size)) {
                byte[] buffer = new byte[65536]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                session.fsync(out);
            }
            // The installer fills in status extras, so this one explicit PendingIntent must stay mutable.
            PendingIntent status = PendingIntent.getBroadcast(c, 73, new Intent(c, UpdateReceiver.class).setAction(INSTALL_STATUS),
                    PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            session.commit(status.getIntentSender());
        } catch (Exception | LinkageError e) { installer.abandonSession(id); throw e; }
    }
    public static void notifyReady(Context c, Ready update) {
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("updates", "앱 업데이트", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(c, 74, new Intent(c, MainActivity.class).setAction(OPEN_UPDATE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        manager.notify(2, new Notification.Builder(c, "updates").setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("NOK " + update.version + " 업데이트 준비됨").setContentText("눌러서 설치 화면을 열어 주세요")
                .setAutoCancel(true).setContentIntent(open).build());
    }
}
