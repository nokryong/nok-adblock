package dev.nok.adblock;

import java.util.Arrays;

/** IPv4 packets addressed exclusively to the VPN's virtual DNS resolver. */
public final class IpPacket {
    public static final int DNS_IP = 0x0A6F0002; // 10.111.0.2
    public final int source, destination, sourcePort, destinationPort, protocol, seq, ack, flags, window;
    public final byte[] data;
    private IpPacket(int source, int destination, int sourcePort, int destinationPort, int protocol,
                     int seq, int ack, int flags, int window, byte[] data) {
        this.source = source; this.destination = destination; this.sourcePort = sourcePort;
        this.destinationPort = destinationPort; this.protocol = protocol; this.seq = seq;
        this.ack = ack; this.flags = flags; this.window = window; this.data = data;
    }
    public static IpPacket parse(byte[] b, int length) {
        if (length < 20 || (b[0] & 0xf0) != 0x40) return null;
        int ihl = (b[0] & 15) * 4, total = DnsMessage.u16(b, 2), proto = b[9] & 255;
        if (ihl < 20 || total > length || total < ihl + 8 || (DnsMessage.u16(b, 6) & 0x3fff) != 0
                || read32(b, 16) != DNS_IP) return null;
        int srcPort = DnsMessage.u16(b, ihl), dstPort = DnsMessage.u16(b, ihl + 2);
        if (dstPort != 53) return null;
        int start, end = total, seq = 0, ack = 0, flags = 0, window = 0;
        if (proto == 17) {
            int udpLength = DnsMessage.u16(b, ihl + 4);
            if (udpLength < 8 || ihl + udpLength > total) return null;
            start = ihl + 8; end = ihl + udpLength;
        } else if (proto == 6) {
            if (total < ihl + 20) return null;
            int thl = ((b[ihl + 12] & 0xf0) >>> 4) * 4;
            if (thl < 20 || ihl + thl > total) return null;
            start = ihl + thl; seq = read32(b, ihl + 4); ack = read32(b, ihl + 8);
            flags = b[ihl + 13] & 255; window = DnsMessage.u16(b, ihl + 14);
        } else return null;
        return new IpPacket(read32(b, 12), read32(b, 16), srcPort, dstPort, proto, seq, ack,
                flags, window, Arrays.copyOfRange(b, start, end));
    }
    public byte[] udpReply(byte[] dns) {
        byte[] b = header(28 + dns.length, 17);
        DnsMessage.put16(b, 20, destinationPort); DnsMessage.put16(b, 22, sourcePort);
        DnsMessage.put16(b, 24, dns.length + 8);
        System.arraycopy(dns, 0, b, 28, dns.length);
        int sum = transportChecksum(b, 17);
        DnsMessage.put16(b, 26, sum == 0 ? 0xffff : sum);
        return b;
    }
    public byte[] tcpReply(int sequence, int acknowledgement, int tcpFlags, byte[] payload) {
        byte[] b = header(40 + payload.length, 6);
        DnsMessage.put16(b, 20, destinationPort); DnsMessage.put16(b, 22, sourcePort);
        write32(b, 24, sequence); write32(b, 28, acknowledgement);
        b[32] = 0x50; b[33] = (byte) tcpFlags; DnsMessage.put16(b, 34, 32768);
        System.arraycopy(payload, 0, b, 40, payload.length);
        DnsMessage.put16(b, 36, transportChecksum(b, 6));
        return b;
    }
    private byte[] header(int length, int protocol) {
        byte[] b = new byte[length]; b[0] = 0x45;
        DnsMessage.put16(b, 2, length); b[8] = 64; b[9] = (byte) protocol;
        write32(b, 12, destination); write32(b, 16, source);
        DnsMessage.put16(b, 10, checksum(b, 0, 20, 0)); return b;
    }
    public static int read32(byte[] b, int p) {
        return ((b[p] & 255) << 24) | ((b[p + 1] & 255) << 16) | ((b[p + 2] & 255) << 8) | (b[p + 3] & 255);
    }
    public static void write32(byte[] b, int p, int v) {
        b[p] = (byte) (v >>> 24); b[p + 1] = (byte) (v >>> 16); b[p + 2] = (byte) (v >>> 8); b[p + 3] = (byte) v;
    }
    public static int transportChecksum(byte[] b, int protocol) {
        long sum = protocol + b.length - 20;
        for (int i = 12; i < 20; i += 2) sum += DnsMessage.u16(b, i);
        return checksum(b, 20, b.length - 20, sum);
    }
    public static int checksum(byte[] b, int start, int count, long sum) {
        int end = start + count, p = start;
        while (p + 1 < end) { sum += DnsMessage.u16(b, p); p += 2; }
        if (p < end) sum += (b[p] & 255) << 8;
        while ((sum >>> 16) != 0) sum = (sum & 0xffff) + (sum >>> 16);
        return (int) (~sum & 0xffff);
    }
}
