package dev.nok.adblock;

import java.io.*;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.HttpsURLConnection;

/** Uses the app's VPN-exempt network path; Android's TLS and hostname validation remain enabled. */
public final class DohResolver {
    private final Set<HttpsURLConnection> active = ConcurrentHashMap.newKeySet();
    private volatile boolean stopped;
    public byte[] resolve(String endpoint, byte[] query) throws IOException {
        if (stopped) throw new IOException("중지됨");
        HttpsURLConnection c = (HttpsURLConnection) new URL(endpoint).openConnection();
        active.add(c);
        try {
            if (stopped) throw new IOException("중지됨");
            c.setConnectTimeout(6000); c.setReadTimeout(6000); c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/dns-message");
            c.setRequestProperty("Accept", "application/dns-message");
            c.setInstanceFollowRedirects(false); c.setUseCaches(false);
            c.setDoOutput(true); c.setFixedLengthStreamingMode(query.length);
            try (OutputStream out = c.getOutputStream()) { out.write(query); }
            if (c.getResponseCode() != 200) throw new IOException("DNS 서버 HTTP " + c.getResponseCode());
            String type = c.getContentType();
            if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/dns-message"))
                throw new IOException("DNS 응답 형식 오류");
            byte[] result;
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 65535) throw new IOException("DNS 응답 크기 초과");
                    out.write(buffer, 0, n);
                }
                result = out.toByteArray();
            }
            DnsMessage.validateResponse(query, result);
            return result;
        } finally { active.remove(c); c.disconnect(); }
    }
    public void close() { stopped = true; for (HttpsURLConnection c : active) c.disconnect(); active.clear(); }
}
