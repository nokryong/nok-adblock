package dev.nok.adblock;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** Host-side regression tests for the actual DNS and TCP engine, without Android stubs. */
public final class CoreTests {
    static int checks;
    static final byte[] EMPTY = new byte[0];
    static void check(boolean ok, String message) {
        checks++; if (!ok) throw new AssertionError(message);
    }
    static byte[] query(String domain, int type) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0});
        for (String part : domain.split("\\.")) { out.write(part.length()); out.write(part.getBytes("US-ASCII")); }
        out.write(0); out.write(type >> 8); out.write(type); out.write(0); out.write(1); return out.toByteArray();
    }
    static byte[] clientPacket(int protocol, int seq, int ack, int flags, byte[] data) {
        int start = protocol == 6 ? 40 : 28;
        byte[] b = new byte[start + data.length]; b[0] = 0x45; b[8] = 64; b[9] = (byte) protocol;
        DnsMessage.put16(b, 2, b.length); IpPacket.write32(b, 12, 0x0a6f0001); IpPacket.write32(b, 16, IpPacket.DNS_IP);
        DnsMessage.put16(b, 20, 45000); DnsMessage.put16(b, 22, 53);
        if (protocol == 6) {
            IpPacket.write32(b, 24, seq); IpPacket.write32(b, 28, ack); b[32] = 0x50; b[33] = (byte) flags; DnsMessage.put16(b, 34, 32768);
        } else DnsMessage.put16(b, 24, data.length + 8);
        System.arraycopy(data, 0, b, start, data.length); return b;
    }
    static IpPacket client(int seq, int ack, int flags, byte[] data) {
        byte[] b = clientPacket(6, seq, ack, flags, data); return IpPacket.parse(b, b.length);
    }
    static byte[] framed(byte[] data) {
        byte[] framed = new byte[data.length + 2]; DnsMessage.put16(framed, 0, data.length); System.arraycopy(data, 0, framed, 2, data.length); return framed;
    }
    static void dnsTests() throws Exception {
        byte[] q = query("Ads.Example.com", 28);
        DnsMessage.Question parsed = DnsMessage.question(q);
        check(parsed.domain.equals("ads.example.com") && parsed.type == 28 && parsed.end == q.length, "DNS question decoding");
        byte[] blocked = DnsMessage.error(q, 3);
        DnsMessage.validateResponse(q, blocked);
        check((blocked[2] & 0x81) == 0x81 && (blocked[3] & 15) == 3, "NXDOMAIN and RD flags");
        check(DnsMessage.u16(blocked, 6) == 0 && DnsMessage.u16(blocked, 10) == 0, "No stale answer or OPT counts");
        check((DnsMessage.truncated(q)[2] & 2) != 0, "UDP truncation flag");
        check(DnsMessage.udpLimit(q) == 512, "Non EDNS limit");
        byte[] edns = Arrays.copyOf(q, q.length + 11); DnsMessage.put16(edns, 10, 1);
        DnsMessage.put16(edns, q.length + 1, 41); DnsMessage.put16(edns, q.length + 3, 4096);
        check(DnsMessage.udpLimit(edns) == 1232, "EDNS clamped to safe MTU");
        byte[] malformed = q.clone(); malformed[12] = (byte) 0xc0;
        try { DnsMessage.question(malformed); check(false, "Compressed query must fail"); } catch (IOException expected) { check(true, "Compressed query rejected"); }
        byte[] other = DnsMessage.error(query("other.example.com", 28), 0);
        try { DnsMessage.validateResponse(q, other); check(false, "Mismatched response"); } catch (IOException expected) { check(true, "Mismatched response rejected"); }
        Random random = new Random(3);
        for (int i = 0; i < 10000; i++) {
            byte[] b = new byte[random.nextInt(512)]; random.nextBytes(b);
            try { DnsMessage.question(b); } catch (IOException expected) {}
            DnsMessage.error(b, 1); DnsMessage.udpLimit(b);
        }
        check(true, "10000 malformed DNS inputs cannot crash the parser");
    }
    static void ruleTests(Path assets) throws Exception {
        String hosts = "# comment\n0.0.0.0 ads.example.com tracker.example.com\n127.0.0.1 beacon.test.org # end\n:: tracker6.test.org\n1.2.3.4 good.example.com\n127.0.0.1 localhost\n";
        Set<String> block = DomainRules.parse(new StringReader(hosts));
        check(block.size() == 4, "hosts sinkhole parser and multiple aliases");
        DomainRules rules = new DomainRules(block, Collections.singleton("safe.ads.example.com"));
        check(rules.blocks("Ads.Example.com"), "case-insensitive blocking");
        check(rules.blocks("sub.ads.example.com"), "blocking child domains");
        check(!rules.blocks("notads.example.com"), "label-boundary matching");
        check(!rules.blocks("deep.safe.ads.example.com"), "allow overrides block for child domains");
        check(!rules.blocks("good.example.com"), "arbitrary hosts mappings excluded");
        check("example.com".equals(DomainRules.normalize(" EXAMPLE.COM. ")), "domain normalization");
        check(DomainRules.normalize("https://example.com/a") == null, "reject full URL");
        check(DomainRules.normalize("1.1.1.1") == null && DomainRules.normalize("a..com") == null, "reject IP and empty labels");
        check(DomainRules.normalize("예시.한국").startsWith("xn--"), "internationalized domains");
        Set<String> bundled;
        try (Reader in = Files.newBufferedReader(assets)) { bundled = DomainRules.parse(in); }
        check(bundled.size() > 1000, "bundled list has real coverage");
        check(bundled.contains("ad.doubleclick.net"), "bundled known advertising domain");
        System.out.println("Bundled domain count: " + bundled.size());
    }
    static void packetTests() throws Exception {
        byte[] q = query("example.com", 1);
        byte[] original = clientPacket(17, 0, 0, 0, q);
        IpPacket p = IpPacket.parse(original, original.length);
        check(p != null && p.sourcePort == 45000 && Arrays.equals(p.data, q), "IPv4 UDP decoding");
        byte[] response = p.udpReply(DnsMessage.error(q, 3));
        check(IpPacket.read32(response, 12) == IpPacket.DNS_IP && IpPacket.read32(response, 16) == p.source, "UDP response IP direction");
        check(DnsMessage.u16(response, 20) == 53 && DnsMessage.u16(response, 22) == 45000, "UDP response port direction");
        check(IpPacket.checksum(response, 0, 20, 0) == 0 && IpPacket.transportChecksum(response, 17) == 0, "IPv4 and UDP checksums");
        original[6] = 0x20; check(IpPacket.parse(original, original.length) == null, "reject fragmented DNS packets");
        original = clientPacket(17, 0, 0, 0, q); original[19] = 3;
        check(IpPacket.parse(original, original.length) == null, "route accepts virtual DNS only");
        byte[] tcp = client(100, 0, 2, EMPTY).tcpReply(200, 101, 0x12, EMPTY);
        check(IpPacket.transportChecksum(tcp, 6) == 0 && IpPacket.checksum(tcp, 0, 20, 0) == 0, "TCP checksum");
        Random random = new Random(5);
        for (int i = 0; i < 10000; i++) { byte[] b = new byte[random.nextInt(512)]; random.nextBytes(b); IpPacket.parse(b, b.length); }
        check(true, "10000 malformed IP inputs cannot crash the parser");
    }
    static void tcpTests() throws Exception {
        List<byte[]> output = new ArrayList<>(); List<Consumer<byte[]>> callbacks = new ArrayList<>();
        List<byte[]> received = new ArrayList<>(); long[] now = {100};
        TcpDnsSession session = new TcpDnsSession(client(1000, 0, 2, EMPTY), 2000, now[0], output::add,
                (q, callback) -> { received.add(q); callbacks.add(callback); }, () -> now[0]);
        check(output.size() == 1 && (output.get(0)[33] & 255) == 0x12, "TCP SYN ACK");
        session.tick(1200); check(output.size() == 2, "TCP SYN retransmission");
        session.receive(client(1001, 2001, 16, EMPTY), 1300);
        byte[] q = query("example.com", 1), frame = framed(q);
        session.receive(client(1001, 2001, 24, Arrays.copyOf(frame, 7)), 1400);
        check(received.isEmpty(), "partial TCP DNS frame buffers");
        session.receive(client(1008, 2001, 24, Arrays.copyOfRange(frame, 7, frame.length)), 1500);
        check(received.size() == 1 && Arrays.equals(q, received.get(0)), "segmented TCP query reassembly");
        session.receive(client(1008, 2001, 24, Arrays.copyOfRange(frame, 7, frame.length)), 1501);
        check(received.size() == 1, "duplicate TCP segment is not resolved twice");
        byte[] answer = DnsMessage.error(q, 3); now[0] = 1600; callbacks.get(0).accept(answer);
        byte[] wire = output.get(output.size() - 1);
        check(Arrays.equals(Arrays.copyOfRange(wire, 40, wire.length), framed(answer)), "DNS TCP response framing");
        check(IpPacket.transportChecksum(wire, 6) == 0, "DNS TCP response checksum");
        int before = output.size(); session.tick(2700); check(output.size() == before + 1, "TCP response retransmission");
        int clientNext = 1001 + frame.length, serverNext = 2001 + answer.length + 2;
        session.receive(client(clientNext, serverNext, 16, EMPTY), 2800);
        before = output.size(); session.tick(4000); check(output.size() == before, "ACK retires retransmission");
        session.receive(client(clientNext, serverNext, 17, EMPTY), 4100);
        check((output.get(output.size() - 1)[33] & 1) != 0, "TCP FIN acknowledgement and close");
        session.receive(client(clientNext + 1, serverNext + 1, 16, EMPTY), 4200);
        check(!session.tick(4300), "TCP session retires after FIN ACK");

        // Half-close while the HTTPS resolver is still working must not discard its answer.
        output.clear(); callbacks.clear(); received.clear(); now[0] = 100;
        TcpDnsSession half = new TcpDnsSession(client(10, 0, 2, EMPTY), 20, 100, output::add,
                (query, callback) -> callbacks.add(callback), () -> now[0]);
        half.receive(client(11, 21, 16, EMPTY), 110);
        half.receive(client(11, 21, 25, frame), 120);
        half.receive(client(12 + frame.length, 21, 16, EMPTY), 130);
        callbacks.get(0).accept(answer);
        check(output.get(output.size() - 1).length > 40, "half-close preserves pending DNS answer");
        check(!half.tick(40000), "idle TCP sessions expire");

        // Large TCP responses must be segmented and resume after window acknowledgements.
        output.clear(); now[0] = 100;
        byte[] large = new byte[24000];
        TcpDnsSession big = new TcpDnsSession(client(30, 0, 2, EMPTY), 40, 100, output::add,
                (query, callback) -> callback.accept(large), () -> now[0]);
        big.receive(client(31, 41, 16, EMPTY), 110);
        big.receive(client(31, 41, 24, frame), 120);
        int payload = output.stream().mapToInt(b -> b.length - 40).sum();
        check(payload == 16384, "TCP in-flight limit is bounded");
        big.receive(client(31 + frame.length, 41 + payload, 16, EMPTY), 200);
        check(output.stream().mapToInt(b -> b.length - 40).sum() == 24002, "large TCP response drains after ACK");
        check(output.stream().allMatch(b -> b.length <= 1240), "TCP segments fit tunnel MTU");
    }
    public static void main(String[] args) throws Exception {
        dnsTests(); ruleTests(Paths.get(args[0])); packetTests(); tcpTests();
        System.out.println("PASS: " + checks + " checks (including 20000 malformed DNS/IP samples)");
    }
}
