package dev.nok.adblock;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;

/** Release selection, download verification and DNS fallback rules. */
public final class UpdateTests {
    static int checks;
    static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    static boolean fails(Runnable action) {
        try { action.run(); return false; } catch (IllegalArgumentException e) { return true; }
    }
    static final String DIGEST = "sha256:" + new String(new char[64]).replace('\0', 'A');
    static String url(String name) { return "https://github.com/" + UpdatePolicy.REPOSITORY + "/releases/download/v1.2.3/" + name; }
    static void versionTests() {
        check(UpdatePolicy.compare("0.3.10", "0.3.9") > 0, "versions compare numerically");
        check(UpdatePolicy.compare("1.0.0", "0.99.99") > 0, "major version wins");
        check(UpdatePolicy.compare("0.3.2", "0.3.2") == 0, "same version");
        check(fails(() -> UpdatePolicy.compare("0.3", "0.3.2")), "short version rejected");
        check(fails(() -> UpdatePolicy.compare("0.3.2-beta", "0.3.2")), "prerelease name rejected");
    }
    static void assetTests() {
        String name = "nok-adblock-1.2.3.apk";
        UpdatePolicy.Asset good = UpdatePolicy.accept(name, url(name), 1000, DIGEST, "uploaded");
        check(good != null && good.version.equals("1.2.3") && good.sha256.equals(DIGEST.substring(7).toLowerCase(Locale.ROOT)), "valid asset accepted");
        check(UpdatePolicy.accept("evil-1.2.3.apk", url("evil-1.2.3.apk"), 1000, DIGEST, "uploaded") == null, "other file name rejected");
        check(UpdatePolicy.accept(name + ".sha256", url(name + ".sha256"), 1000, DIGEST, "uploaded") == null, "checksum file is not an APK");
        check(UpdatePolicy.accept(name, url(name), 1000, null, "uploaded") == null, "missing digest rejected");
        check(UpdatePolicy.accept(name, url(name), 1000, "sha256:abc", "uploaded") == null, "short digest rejected");
        check(UpdatePolicy.accept(name, url(name), 1000, DIGEST, "starter") == null, "incomplete upload rejected");
        check(UpdatePolicy.accept(name, url(name), 0, DIGEST, "uploaded") == null, "empty asset rejected");
        check(UpdatePolicy.accept(name, url(name), UpdatePolicy.MAX_APK_BYTES + 1, DIGEST, "uploaded") == null, "oversized asset rejected");
        check(UpdatePolicy.accept(name, "http://github.com/" + UpdatePolicy.REPOSITORY + "/releases/download/v1/" + name, 1000, DIGEST, "uploaded") == null, "plain HTTP rejected");
        check(UpdatePolicy.accept(name, "https://evil.example/" + UpdatePolicy.REPOSITORY + "/releases/download/v1/" + name, 1000, DIGEST, "uploaded") == null, "other host rejected");
        check(UpdatePolicy.accept(name, "https://github.com/someone/else/releases/download/v1/" + name, 1000, DIGEST, "uploaded") == null, "other repository rejected");
        check(UpdatePolicy.accept(name, "https://github.com/" + UpdatePolicy.REPOSITORY + "/releases/download/../../../x/" + name, 1000, DIGEST, "uploaded") == null, "path traversal rejected");
        check(UpdatePolicy.accept(name, url(name) + "?x=1", 1000, DIGEST, "uploaded") == null, "query rejected");
    }
    static void hostTests() throws Exception {
        check(UpdatePolicy.allowedUrl(new URL("https://api.github.com/repos")), "API allowed");
        check(UpdatePolicy.allowedUrl(new URL("https://release-assets.githubusercontent.com/x")), "asset host allowed");
        check(!UpdatePolicy.allowedUrl(new URL("http://github.com/x")), "redirect to HTTP rejected");
        check(!UpdatePolicy.allowedUrl(new URL("https://github.com.evil.example/x")), "lookalike host rejected");
        check(!UpdatePolicy.allowedUrl(new URL("https://github.com:8443/x")), "other port rejected");
        check(!UpdatePolicy.allowedUrl(new URL("https://user@github.com/x")), "embedded user rejected");
    }
    static void signerTests() {
        Set<String> a = Collections.singleton("a"), b = Collections.singleton("b"), both = new HashSet<>(Arrays.asList("a", "b")), none = Collections.emptySet();
        check(UpdatePolicy.compatibleSigners(a, a, none), "same signer accepted");
        check(!UpdatePolicy.compatibleSigners(a, b, none), "different signer rejected");
        check(UpdatePolicy.compatibleSigners(a, b, both), "rotation from installed key accepted");
        check(!UpdatePolicy.compatibleSigners(b, a, a), "history without the installed key rejected");
        check(!UpdatePolicy.compatibleSigners(none, none, none), "unknown signers rejected");
    }
    static boolean identityFails(String pkg, long code, String version, int minSdk, boolean signers) {
        try { UpdatePolicy.checkIdentity("a.b", pkg, 5, code, "1.0.0", version, minSdk, 30, signers); return false; }
        catch (IOException e) { return true; }
    }
    static void identityTests() {
        check(!identityFails("a.b", 6, "1.0.0", 26, true), "valid update accepted");
        check(identityFails("x.y", 6, "1.0.0", 26, true), "other package rejected");
        check(identityFails("a.b", 5, "1.0.0", 26, true), "same version code rejected");
        check(identityFails("a.b", 4, "1.0.0", 26, true), "downgrade rejected");
        check(identityFails("a.b", 6, "1.0.1", 26, true), "mismatched version name rejected");
        check(identityFails("a.b", 6, "1.0.0", 31, true), "unsupported device rejected");
        check(identityFails("a.b", 6, "1.0.0", 26, false), "signer mismatch rejected");
    }
    static boolean copyFails(byte[] data, long size, String sha256, boolean cancelled) {
        try {
            UpdatePolicy.copyVerified(new ByteArrayInputStream(data), new ByteArrayOutputStream(), size, sha256, () -> cancelled);
            return false;
        } catch (IOException e) { return true; }
    }
    static void downloadTests() throws Exception {
        byte[] data = new byte[50000]; new Random(7).nextBytes(data);
        String sha = UpdatePolicy.hex(MessageDigest.getInstance("SHA-256").digest(data));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        UpdatePolicy.copyVerified(new ByteArrayInputStream(data), out, data.length, sha, () -> false);
        check(Arrays.equals(out.toByteArray(), data), "verified copy preserves bytes");
        byte[] changed = data.clone(); changed[25000] ^= 1;
        check(copyFails(changed, data.length, sha, false), "changed byte rejected");
        check(copyFails(Arrays.copyOf(data, data.length - 1), data.length, sha, false), "truncated download rejected");
        check(copyFails(Arrays.copyOf(data, data.length + 1), data.length, sha, false), "extended download rejected");
        check(copyFails(data, data.length, sha, true), "cancelled download rejected");
    }
    static void plainDnsTests() throws Exception {
        byte[] query = CoreTests.query("example.com", 1);
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            byte[] good = query.clone(); good[2] = (byte) 0x81; good[3] = (byte) 0x80;
            byte[] wrong = good.clone(); wrong[0] ^= 1;
            Thread responder = new Thread(() -> {
                try {
                    for (byte[] reply : new byte[][]{good, wrong}) {
                        DatagramPacket in = new DatagramPacket(new byte[512], 512); server.receive(in);
                        server.send(new DatagramPacket(reply, reply.length, in.getSocketAddress()));
                    }
                } catch (IOException ignored) {}
            });
            responder.start();
            byte[] answer = PlainDns.exchange(InetAddress.getLoopbackAddress(), server.getLocalPort(), query, 3000, null);
            check(Arrays.equals(answer, good), "fallback returns the resolver's answer");
            boolean rejected = false;
            try { PlainDns.exchange(InetAddress.getLoopbackAddress(), server.getLocalPort(), query, 3000, null); }
            catch (IOException e) { rejected = true; }
            check(rejected, "fallback rejects a mismatched DNS ID");
            responder.join(3000);
            boolean timedOut = false;
            try { PlainDns.exchange(InetAddress.getLoopbackAddress(), server.getLocalPort(), query, 200, null); }
            catch (IOException e) { timedOut = true; }
            check(timedOut, "silent resolver times out");
        }
    }
    public static void main(String[] args) throws Exception {
        versionTests(); assetTests(); hostTests(); signerTests(); identityTests(); downloadTests(); plainDnsTests();
        System.out.println("PASS: " + checks + " update and DNS fallback checks");
    }
}
