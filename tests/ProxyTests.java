package dev.nok.adblock;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** Local-only integration tests. No blocked sites or external network connections are used. */
public final class ProxyTests {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static byte[] hello(String host, String protocol) throws Exception {
        SSLEngine engine = SSLContext.getDefault().createSSLEngine(host, 443);
        engine.setUseClientMode(true); engine.setEnabledProtocols(new String[]{protocol});
        SSLParameters params = engine.getSSLParameters(); params.setServerNames(Collections.singletonList(new SNIHostName(host))); engine.setSSLParameters(params);
        engine.beginHandshake(); ByteBuffer out = ByteBuffer.allocate(65536);
        engine.wrap(ByteBuffer.allocate(0), out); return Arrays.copyOf(out.array(), out.position());
    }
    static byte[] joinedPayload(byte[][] records) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] record : records) {
            if (record.length < 5 || DnsMessage.u16(record, 3) != record.length - 5 || record[0] != 22) throw new AssertionError("invalid TLS fragment");
            out.write(record, 5, record.length - 5);
        }
        return out.toByteArray();
    }
    static Socket authenticate(LocalProxy proxy, String password) throws IOException {
        Socket socket = new Socket("127.0.0.1", proxy.port()); socket.setSoTimeout(4000);
        OutputStream out = socket.getOutputStream(); DataInputStream in = new DataInputStream(socket.getInputStream());
        out.write(new byte[]{5, 1, 2}); out.flush();
        if (in.readUnsignedByte() != 5 || in.readUnsignedByte() != 2) throw new AssertionError("SOCKS method");
        byte[] user = proxy.username().getBytes(StandardCharsets.UTF_8), pass = password.getBytes(StandardCharsets.UTF_8);
        out.write(1); out.write(user.length); out.write(user); out.write(pass.length); out.write(pass); out.flush();
        if (in.readUnsignedByte() != 1 || in.readUnsignedByte() != 0) { socket.close(); throw new IOException("Authentication rejected"); }
        return socket;
    }
    static byte[] destination(String ip, int port) throws IOException {
        byte[] address = InetAddress.getByName(ip).getAddress();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bytes.write(address.length == 16 ? 4 : 1); bytes.write(address);
        bytes.write(port >>> 8); bytes.write(port); return bytes.toByteArray();
    }
    static int request(Socket socket, int command, byte[] target) throws IOException {
        OutputStream out = socket.getOutputStream(); out.write(new byte[]{5, (byte) command, 0}); out.write(target); out.flush();
        DataInputStream in = new DataInputStream(socket.getInputStream());
        if (in.readUnsignedByte() != 5) throw new AssertionError("SOCKS response version");
        int status = in.readUnsignedByte(); in.readUnsignedByte(); int type = in.readUnsignedByte();
        byte[] address = new byte[type == 1 ? 4 : 16]; in.readFully(address); int port = in.readUnsignedShort();
        if (status != 0) throw new IOException("SOCKS status " + status); return port;
    }
    static Socket connect(LocalProxy proxy, String host, int port) throws IOException {
        Socket socket = authenticate(proxy, proxy.password()); request(socket, 1, destination(host, port)); return socket;
    }
    static byte[] udpFrame(String host, int port, byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bytes.write(new byte[]{0, 0, 0}); bytes.write(destination(host, port)); bytes.write(data); return bytes.toByteArray();
    }
    static void tlsTests() throws Exception {
        for (String protocol : new String[]{"TLSv1.2", "TLSv1.3"}) {
            byte[] original = hello("www.example.com", protocol); TlsHello.Plan plan = TlsHello.split(original);
            check(plan != null && "www.example.com".equals(plan.serverName), protocol + " SNI extraction");
            check(Arrays.equals(joinedPayload(plan.records), Arrays.copyOfRange(original, 5, original.length)), protocol + " preserves every handshake byte");
            check(plan.records.length == 2, protocol + " split into two records");
            for (byte[] record : plan.records) check(!new String(record, StandardCharsets.ISO_8859_1).contains("www.example.com"), "SNI not contiguous in either record");
        }
        check(TlsHello.split("GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII)) == null, "HTTP is not rewritten");
        Random random = new Random(14);
        for (int i = 0; i < 10000; i++) { byte[] input = new byte[random.nextInt(4096)]; random.nextBytes(input); TlsHello.split(input); }
        check(true, "10000 malformed TLS records do not crash");
    }
    static void proxyTests() throws Exception {
        ExecutorService workers = Executors.newCachedThreadPool();
        DomainRules selected = new DomainRules(Collections.singleton("example.com"), Collections.emptySet());
        check(selected.blocks("www.example.com") && !selected.blocks("example.com.evil"), "bypass selection respects domain boundaries");
        try (LocalProxy proxy = new LocalProxy(q -> DnsMessage.error(q, 3), host -> host.equals("blocked.example"), selected::blocks)) {
            check(proxy.port() > 0, "loopback proxy bound to ephemeral port");
            try { authenticate(proxy, "wrong password"); check(false, "wrong password accepted"); }
            catch (IOException expected) { check(true, "wrong password rejected"); }
            try (Socket noAuth = new Socket("127.0.0.1", proxy.port())) {
                noAuth.setSoTimeout(4000); noAuth.getOutputStream().write(new byte[]{5, 1, 0});
                byte[] reject = noAuth.getInputStream().readNBytes(2);
                check(reject.length == 2 && (reject[1] & 255) == 255, "unauthenticated method rejected");
            }
            byte[] query = CoreTests.query("ads.example", 1);
            try (Socket client = connect(proxy, "10.111.0.2", 53)) {
                client.getOutputStream().write(CoreTests.framed(query)); client.getOutputStream().flush();
                DataInputStream in = new DataInputStream(client.getInputStream());
                byte[] answer = new byte[in.readUnsignedShort()]; in.readFully(answer);
                check((answer[3] & 15) == 3 && DnsMessage.u16(answer, 0) == DnsMessage.u16(query, 0), "TCP DNS uses local filtering callback");
            }
            try (Socket control = authenticate(proxy, proxy.password()); DatagramSocket client = new DatagramSocket()) {
                int port = request(control, 3, destination("0.0.0.0", 0)); client.setSoTimeout(4000);
                byte[] frame = udpFrame("10.111.0.2", 53, query); client.send(new DatagramPacket(frame, frame.length, InetAddress.getLoopbackAddress(), port));
                DatagramPacket answer = new DatagramPacket(new byte[4096], 4096); client.receive(answer);
                check(answer.getLength() == frame.length && (answer.getData()[13] & 15) == 3, "UDP DNS routes through filtering callback");
                for (int echoPort : new int[]{0, 443}) try (DatagramSocket echo = new DatagramSocket(new InetSocketAddress("127.0.0.1", echoPort))) {
                    Future<?> done = workers.submit(() -> {
                        try { DatagramPacket packet = new DatagramPacket(new byte[128], 128); echo.setSoTimeout(4000); echo.receive(packet); echo.send(packet); }
                        catch (IOException e) { throw new RuntimeException(e); }
                    });
                    byte[] payload = "udp-echo".getBytes(StandardCharsets.US_ASCII), request = udpFrame("127.0.0.1", echo.getLocalPort(), payload);
                    client.send(new DatagramPacket(request, request.length, InetAddress.getLoopbackAddress(), port));
                    DatagramPacket response = new DatagramPacket(new byte[256], 256); client.receive(response); done.get(5, TimeUnit.SECONDS);
                    check(Arrays.equals(Arrays.copyOfRange(response.getData(), 10, response.getLength()), payload), "UDP port " + echo.getLocalPort() + " relays unchanged (including QUIC port 443)");
                }
            }
            try (ServerSocket echo = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
                Future<?> done = workers.submit(() -> {
                    try (Socket accepted = echo.accept()) {
                        accepted.setSoTimeout(4000); byte[] request = accepted.getInputStream().readNBytes(4);
                        if (!Arrays.equals(request, new byte[]{1, 2, 3, 4})) throw new AssertionError("TCP bytes changed");
                        accepted.getOutputStream().write(new byte[]{5, 6, 7, 8});
                    } catch (IOException e) { throw new RuntimeException(e); }
                });
                try (Socket client = connect(proxy, "127.0.0.1", echo.getLocalPort())) {
                    client.getOutputStream().write(new byte[]{1}); client.getOutputStream().flush();
                    client.getOutputStream().write(new byte[]{2, 3, 4});
                    check(Arrays.equals(client.getInputStream().readNBytes(4), new byte[]{5, 6, 7, 8}), "ordinary TCP and short first packets relay unchanged");
                }
                done.get(5, TimeUnit.SECONDS);
            }
            try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
                Future<byte[]> capture = workers.submit(() -> {
                    try (Socket accepted = server.accept()) {
                        accepted.setSoTimeout(4000); accepted.getOutputStream().write(42); accepted.shutdownOutput();
                        return accepted.getInputStream().readNBytes(4);
                    }
                });
                try (Socket client = connect(proxy, "127.0.0.1", server.getLocalPort())) {
                    check(client.getInputStream().read() == 42 && client.getInputStream().read() == -1, "server half-close reaches client");
                    client.getOutputStream().write(new byte[]{1, 2, 3, 4}); client.shutdownOutput();
                }
                check(Arrays.equals(capture.get(5, TimeUnit.SECONDS), new byte[]{1, 2, 3, 4}), "server half-close preserves client upload");
            }
            byte[] googleHello = hello("www.google.com", "TLSv1.3");
            try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
                Future<byte[]> capture = workers.submit(() -> {
                    try (Socket accepted = server.accept()) {
                        accepted.setSoTimeout(4000);
                        byte[] received = accepted.getInputStream().readNBytes(googleHello.length);
                        accepted.getOutputStream().write(42); return received;
                    }
                });
                try (Socket client = connect(proxy, "127.0.0.1", server.getLocalPort())) {
                    client.getOutputStream().write(googleHello); client.shutdownOutput();
                    check(client.getInputStream().read() == 42, "unselected Google TLS connection receives response");
                }
                check(Arrays.equals(capture.get(5, TimeUnit.SECONDS), googleHello), "unselected TLS is forwarded byte for byte");
                check(proxy.fragmentCount() == 0, "unselected site does not enter fragmentation path");
            }
            byte[] hello = hello("www.example.com", "TLSv1.3");
            try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
                Future<byte[]> capture = workers.submit(() -> {
                    try (Socket accepted = server.accept()) {
                        accepted.setSoTimeout(4000); byte[] bytes = accepted.getInputStream().readNBytes(hello.length + 5);
                        accepted.getOutputStream().write(42); return bytes;
                    }
                });
                try (Socket client = connect(proxy, "127.0.0.1", server.getLocalPort())) {
                    client.getOutputStream().write(hello); client.getOutputStream().flush(); client.shutdownOutput();
                    check(client.getInputStream().read() == 42, "fragmented connection preserves client half-close and server response");
                }
                byte[] received = capture.get(5, TimeUnit.SECONDS);
                int firstSize = 5 + DnsMessage.u16(received, 3);
                byte[][] records = {Arrays.copyOf(received, firstSize), Arrays.copyOfRange(received, firstSize, received.length)};
                check(Arrays.equals(joinedPayload(records), Arrays.copyOfRange(hello, 5, hello.length)), "SOCKS relay fragments real SSLEngine ClientHello without modifying handshake");
                check(proxy.fragmentCount() == 1, "TLS fragmentation path executed");
            }
            try (Socket blocked = authenticate(proxy, proxy.password())) {
                byte[] host = "blocked.example".getBytes(StandardCharsets.US_ASCII); ByteArrayOutputStream target = new ByteArrayOutputStream();
                target.write(3); target.write(host.length); target.write(host); target.write(1); target.write(187);
                try { request(blocked, 1, target.toByteArray()); check(false, "blocked host accepted"); }
                catch (IOException expected) { check(true, "domain policy rejects blocked SOCKS hosts"); }
            }
        } finally { workers.shutdownNow(); }
        LocalProxy stopped = new LocalProxy(q -> DnsMessage.error(q, 3), host -> false);
        Socket idle = authenticate(stopped, stopped.password()); stopped.close(); idle.setSoTimeout(2000);
        check(idle.getInputStream().read() == -1, "stopping proxy closes active control sockets"); idle.close();
    }
    public static void main(String[] args) throws Exception {
        tlsTests(); proxyTests();
        System.out.println("PASS: " + checks + " TLS/proxy checks (including 10000 malformed TLS inputs)");
    }
}
