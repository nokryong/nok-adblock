package dev.nok.adblock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** Wire-format DNS helpers. Only one standard, uncompressed question is accepted. */
public final class DnsMessage {
    private DnsMessage() {}
    public static final class Question {
        public final String domain;
        public final int type, end;
        Question(String domain, int type, int end) { this.domain = domain; this.type = type; this.end = end; }
    }
    public static int u16(byte[] b, int p) { return ((b[p] & 255) << 8) | (b[p + 1] & 255); }
    public static void put16(byte[] b, int p, int v) { b[p] = (byte) (v >>> 8); b[p + 1] = (byte) v; }
    public static Question question(byte[] b) throws IOException {
        if (b.length < 17 || u16(b, 4) != 1 || (b[2] & 0x78) != 0) throw new IOException("지원하지 않는 DNS 질문");
        int p = 12;
        StringBuilder name = new StringBuilder();
        while (true) {
            if (p >= b.length) throw new IOException("잘린 DNS 이름");
            int len = b[p++] & 255;
            if (len == 0) break;
            if (len > 63 || p + len > b.length || name.length() + len > 253) throw new IOException("잘못된 DNS 이름");
            if (name.length() > 0) name.append('.');
            for (int i = 0; i < len; i++) {
                int c = b[p++] & 255;
                if (c <= 32 || c >= 127 || c == '.') throw new IOException("잘못된 DNS 레이블");
                name.append((char) c);
            }
        }
        if (p + 4 > b.length || u16(b, p + 2) != 1) throw new IOException("지원하지 않는 DNS 클래스");
        return new Question(name.toString().toLowerCase(Locale.ROOT), u16(b, p), p + 4);
    }
    public static byte[] error(byte[] query, int code) {
        int end = 12;
        try { end = question(query).end; } catch (IOException ignored) {}
        byte[] out = Arrays.copyOf(query, Math.max(12, end));
        out[2] = (byte) (0x80 | (query.length > 2 ? query[2] & 1 : 0));
        out[3] = (byte) (0x80 | code);
        put16(out, 4, end == 12 ? 0 : 1);
        Arrays.fill(out, 6, 12, (byte) 0);
        return out;
    }
    public static byte[] truncated(byte[] query) {
        byte[] out = error(query, 0); out[2] |= 2; return out;
    }
    public static void validateResponse(byte[] query, byte[] answer) throws IOException {
        if (answer.length < 12 || answer.length > 65535 || (answer[2] & 0x80) == 0 || u16(answer, 0) != u16(query, 0))
            throw new IOException("올바르지 않은 DNS 응답");
        Question q = question(query), a = question(answer);
        if (!q.domain.equals(a.domain) || q.type != a.type) throw new IOException("DNS 질문과 응답이 다름");
    }
    /** Respect the client's EDNS size and the tunnel MTU; larger answers use TCP. */
    public static int udpLimit(byte[] query) {
        try {
            Question q = question(query);
            int p = q.end;
            if (u16(query, 6) == 0 && u16(query, 8) == 0 && u16(query, 10) == 1
                    && p + 11 <= query.length && query[p] == 0 && u16(query, p + 1) == 41)
                return Math.max(512, Math.min(1232, u16(query, p + 3)));
        } catch (IOException ignored) {}
        return 512;
    }
}
