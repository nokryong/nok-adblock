package dev.nok.adblock;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** Splits an unencrypted TLS ClientHello into valid TLS records, without changing the handshake. */
public final class TlsHello {
    public static final int MAX_RECORD = 18432;
    private TlsHello() {}
    public static final class Plan {
        public final String serverName;
        public final byte[][] records;
        Plan(String serverName, byte[][] records) { this.serverName = serverName; this.records = records; }
    }
    public static int recordSize(byte[] bytes, int count) {
        if (count < 5 || bytes[0] != 22 || bytes[1] != 3) return -1;
        int size = DnsMessage.u16(bytes, 3);
        return size >= 4 && size <= MAX_RECORD ? size + 5 : -1;
    }
    public static Plan split(byte[] bytes) {
        int size = recordSize(bytes, bytes.length);
        if (size < 0 || size > bytes.length || bytes[5] != 1) return null;
        int nameStart = -1, nameLength = 0;
        try {
            int helloSize = ((bytes[6] & 255) << 16) | DnsMessage.u16(bytes, 7);
            int end = 9 + helloSize;
            if (end <= size && helloSize >= 38) {
                int p = 9 + 2 + 32;
                p += 1 + (bytes[p] & 255);
                int ciphers = DnsMessage.u16(bytes, p); p += 2 + ciphers;
                p += 1 + (bytes[p] & 255);
                int extensions = DnsMessage.u16(bytes, p); p += 2;
                int extensionsEnd = p + extensions;
                if (extensionsEnd > end) return null;
                while (p + 4 <= extensionsEnd) {
                    int type = DnsMessage.u16(bytes, p), length = DnsMessage.u16(bytes, p + 2); p += 4;
                    if (p + length > extensionsEnd) return null;
                    if (type == 0 && length >= 5) {
                        int namesEnd = p + 2 + DnsMessage.u16(bytes, p), n = p + 2;
                        if (namesEnd > p + length) return null;
                        while (n + 3 <= namesEnd) {
                            int nameType = bytes[n] & 255, len = DnsMessage.u16(bytes, n + 1); n += 3;
                            if (n + len > namesEnd) return null;
                            if (nameType == 0 && len >= 2 && len <= 253) { nameStart = n; nameLength = len; break; }
                            n += len;
                        }
                    }
                    if (nameStart >= 0) break;
                    p += length;
                }
            }
        } catch (IndexOutOfBoundsException e) { return null; }
        String host = null;
        if (nameStart >= 0) {
            for (int p = nameStart; p < nameStart + nameLength; p++)
                if ((bytes[p] & 255) < 33 || (bytes[p] & 255) > 126) return null;
            host = new String(bytes, nameStart, nameLength, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
        }
        // No identifiable SNI means there is no selected site to bypass.
        if (host == null) return null;
        int cut = nameStart + nameLength / 2;
        if (cut <= 5 || cut >= size) return null;
        byte[] first = Arrays.copyOf(bytes, cut);
        DnsMessage.put16(first, 3, cut - 5);
        byte[] second = new byte[5 + size - cut];
        System.arraycopy(bytes, 0, second, 0, 3); DnsMessage.put16(second, 3, size - cut);
        System.arraycopy(bytes, cut, second, 5, size - cut);
        byte[][] records = size == bytes.length ? new byte[][]{first, second}
                : new byte[][]{first, second, Arrays.copyOfRange(bytes, size, bytes.length)};
        return new Plan(host, records);
    }
}
