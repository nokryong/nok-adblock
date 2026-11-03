package dev.nok.adblock;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import java.io.*;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AppStore {
    public static final String LIST_URL = "https://raw.githubusercontent.com/AdAway/adaway.github.io/master/hosts.txt";
    private static AppStore instance;
    public static synchronized AppStore get(Context c) {
        if (instance == null) instance = new AppStore(c.getApplicationContext());
        return instance;
    }
    final Context context;
    final SharedPreferences prefs;
    final AtomicBoolean updating = new AtomicBoolean();
    volatile DomainRules rules;
    volatile DomainRules bypassRules;
    volatile boolean recordLogs;
    volatile String updateError = "";
    private final ArrayDeque<LogEntry> logs = new ArrayDeque<>();
    private String day;
    private long total, blocked, errors;
    public static final class LogEntry {
        public final long time;
        public final String domain;
        LogEntry(String domain) { this.domain = domain; time = System.currentTimeMillis(); }
    }
    private AppStore(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("nok", Context.MODE_PRIVATE);
        bypassRules = new DomainRules(bypassed(), Collections.emptySet());
        recordLogs = prefs.getBoolean("logs", true);
        day = prefs.getString("day", today()); total = prefs.getLong("total", 0);
        blocked = prefs.getLong("blocked", 0); errors = prefs.getLong("errors", 0);
        try { rules = new DomainRules(readList(), allowed()); }
        catch (IOException e) { rules = new DomainRules(Collections.emptySet(), allowed()); updateError = "기본 목록을 읽지 못했습니다. 목록을 갱신해 주세요."; }
    }
    private String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()); }
    private AtomicFile listFile() { return new AtomicFile(new File(context.getFilesDir(), "hosts.txt")); }
    private Set<String> readList() throws IOException {
        try (InputStream in = listFile().openRead(); Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Set<String> list = DomainRules.parse(r);
            if (list.size() >= 100) return list;
        } catch (IOException ignored) { /* A damaged update falls back to the bundled list. */ }
        try (Reader r = new InputStreamReader(context.getAssets().open("hosts.txt"), StandardCharsets.UTF_8)) {
            return DomainRules.parse(r);
        }
    }
    public synchronized Set<String> allowed() { return new TreeSet<>(prefs.getStringSet("allowed", Collections.emptySet())); }
    public synchronized Set<String> bypassed() { return new TreeSet<>(prefs.getStringSet("bypass_domains", Collections.emptySet())); }
    public synchronized void bypassDomain(String domain, boolean enabled) {
        Set<String> sites = bypassed(); if (enabled) sites.add(domain); else sites.remove(domain);
        prefs.edit().putStringSet("bypass_domains", sites).apply();
        bypassRules = new DomainRules(sites, Collections.emptySet());
    }
    public synchronized void allow(String domain, boolean enabled) {
        Set<String> allow = allowed(); if (enabled) allow.add(domain); else allow.remove(domain);
        prefs.edit().putStringSet("allowed", allow).apply();
        try { rules = new DomainRules(readList(), allow); }
        catch (IOException e) { updateError = "허용 목록을 적용하지 못했습니다."; }
    }
    public synchronized long[] stats() { rollDay(); return new long[]{total, blocked, errors}; }
    private void rollDay() {
        String now = today();
        if (!day.equals(now)) { day = now; total = blocked = errors = 0; }
    }
    public synchronized void query(String domain, boolean isBlocked) {
        rollDay(); total++;
        if (isBlocked) {
            blocked++;
            if (recordLogs) { logs.addFirst(new LogEntry(domain)); while (logs.size() > 100) logs.removeLast(); }
        }
    }
    public synchronized void error() { rollDay(); errors++; }
    public synchronized void persist() {
        prefs.edit().putString("day", day).putLong("total", total).putLong("blocked", blocked).putLong("errors", errors).apply();
    }
    public synchronized List<LogEntry> logs() { return new ArrayList<>(logs); }
    public synchronized void logging(boolean enabled) {
        recordLogs = enabled; prefs.edit().putBoolean("logs", enabled).apply(); if (!enabled) logs.clear();
    }
    public boolean fallbackDns() { return prefs.getBoolean("dns_fallback", true); }
    public String provider() { return prefs.getString("provider", "cloudflare"); }
    public String endpoint() {
        return "google".equals(provider()) ? "https://dns.google/dns-query" : "https://cloudflare-dns.com/dns-query";
    }
    public long updatedAt() { return prefs.getLong("updated", 0); }
    /** Writes atomically only after a complete download and validation. Existing rules survive failures. */
    public int updateList() throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(LIST_URL).openConnection();
        connection.setConnectTimeout(12000); connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent", "NOK-DNS/0.1");
        byte[] bytes;
        try {
            if (connection.getResponseCode() != 200) throw new IOException("서버가 목록을 제공하지 않습니다");
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 4 * 1024 * 1024) throw new IOException("목록 크기 제한을 초과했습니다");
                    out.write(buffer, 0, n);
                }
                bytes = out.toByteArray();
            }
        } finally { connection.disconnect(); }
        Set<String> parsed = DomainRules.parse(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
        if (parsed.size() < 100) throw new IOException("유효한 차단 목록이 아닙니다");
        synchronized (this) {
            AtomicFile file = listFile(); FileOutputStream out = null;
            try { out = file.startWrite(); out.write(bytes); file.finishWrite(out); }
            catch (IOException e) { if (out != null) file.failWrite(out); throw e; }
            rules = new DomainRules(parsed, allowed());
            prefs.edit().putLong("updated", System.currentTimeMillis()).apply(); updateError = "";
        }
        return parsed.size();
    }
}
