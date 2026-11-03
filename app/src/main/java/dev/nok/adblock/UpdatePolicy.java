package dev.nok.adblock;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.*;

/** Host-testable release rules. Android still makes the final signature decision at install time. */
public final class UpdatePolicy {
    public static final String REPOSITORY = "nokryong/nok-adblock";
    public static final long MAX_APK_BYTES = 64L * 1024 * 1024;
    private static final Pattern APK = Pattern.compile("nok-adblock-(\\d{1,9}\\.\\d{1,9}\\.\\d{1,9})\\.apk");
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList("api.github.com", "github.com",
            "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com"));
    private UpdatePolicy() {}
    public static final class Asset {
        public final String version, url, sha256;
        public final long size;
        Asset(String version, String url, long size, String sha256) {
            this.version = version; this.url = url; this.size = size; this.sha256 = sha256;
        }
    }
    /** Stable x.y.z only; malformed versions are rejected rather than treated as old. */
    public static int compare(String left, String right) {
        long[] a = parts(left), b = parts(right);
        for (int i = 0; i < 3; i++) { int c = Long.compare(a[i], b[i]); if (c != 0) return c; }
        return 0;
    }
    private static long[] parts(String version) {
        String[] split = version.split("\\.", -1);
        if (split.length != 3) throw new IllegalArgumentException("Unsupported version: " + version);
        long[] out = new long[3];
        for (int i = 0; i < 3; i++) {
            if (!split[i].matches("\\d{1,9}")) throw new IllegalArgumentException("Unsupported version: " + version);
            out[i] = Long.parseLong(split[i]);
        }
        return out;
    }
    /** Returns null unless the asset is this repository's uploaded, hashed, versioned APK. */
    public static Asset accept(String name, String url, long size, String digest, String state) {
        Matcher match = APK.matcher(name == null ? "" : name);
        if (!match.matches() || !"uploaded".equals(state) || size <= 0 || size > MAX_APK_BYTES
                || digest == null || !digest.matches("sha256:[0-9a-fA-F]{64}") || url == null) return null;
        try {
            URI uri = new URI(url);
            String prefix = "/" + REPOSITORY + "/releases/download/";
            if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost()) || uri.getPort() != -1 || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null || uri.getPath() == null
                    || !uri.getPath().startsWith(prefix) || !uri.getPath().endsWith("/" + name)
                    || Arrays.asList(uri.getPath().split("/")).contains("..")) return null;
        } catch (java.net.URISyntaxException e) { return null; }
        return new Asset(match.group(1), url, size, digest.substring(7).toLowerCase(Locale.ROOT));
    }
    public static boolean allowedUrl(URL url) {
        return "https".equals(url.getProtocol()) && url.getUserInfo() == null
                && (url.getPort() == -1 || url.getPort() == 443) && HOSTS.contains(url.getHost());
    }
    /** Same signers, or a single-signer APK whose rotation history proves it descends from the installed key. */
    public static boolean compatibleSigners(Set<String> installed, Set<String> candidate, Set<String> history) {
        if (installed.isEmpty() || candidate.isEmpty()) return false;
        if (installed.equals(candidate)) return true;
        return installed.size() == 1 && candidate.size() == 1 && history.containsAll(installed);
    }
    public static void checkIdentity(String expectedPackage, String actualPackage, long installedCode, long actualCode,
                                     String expectedVersion, String actualVersion, int minSdk, int deviceSdk, boolean signers) throws IOException {
        if (!expectedPackage.equals(actualPackage)) throw new IOException("다른 앱의 APK입니다");
        if (actualCode <= installedCode) throw new IOException("현재 앱보다 새 버전이 아닙니다");
        if (!expectedVersion.equals(actualVersion)) throw new IOException("릴리즈와 APK의 버전이 다릅니다");
        if (minSdk > deviceSdk) throw new IOException("이 기기의 Android 버전을 지원하지 않습니다");
        if (!signers) throw new IOException("설치된 앱과 서명이 다릅니다");
    }
    /** Copies exactly the announced bytes; the caller discards the output on any failure. */
    public static void copyVerified(InputStream in, OutputStream out, long size, String sha256, BooleanSupplier cancelled) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IOException(e); }
        byte[] buffer = new byte[16384]; long total = 0; int n;
        while ((n = in.read(buffer)) != -1) {
            if (cancelled.getAsBoolean()) throw new IOException("취소됨");
            total += n;
            if (total > size) throw new IOException("APK 크기가 릴리즈 정보와 다릅니다");
            digest.update(buffer, 0, n); out.write(buffer, 0, n);
        }
        if (total != size) throw new IOException("APK 크기가 릴리즈 정보와 다릅니다");
        if (!hex(digest.digest()).equals(sha256)) throw new IOException("APK 체크섬이 일치하지 않습니다");
    }
    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
}
