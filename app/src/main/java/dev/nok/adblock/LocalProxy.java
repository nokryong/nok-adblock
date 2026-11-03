package dev.nok.adblock;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Authenticated loopback SOCKS5 relay. All upstream sockets belong to the VPN-exempt app. */
public final class LocalProxy implements Closeable {
    public interface DnsHandler { byte[] answer(byte[] query) throws IOException; }
    public interface HostPolicy { boolean blocks(String host); }
    private final DnsHandler dns;
    private final HostPolicy policy;
    private final HostPolicy bypass;
    private final ServerSocket listener;
    private final ThreadPoolExecutor connections = pool(128), io = pool(256);
    private final ThreadPoolExecutor dnsWorkers = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128));
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Set<DatagramSocket> datagrams = ConcurrentHashMap.newKeySet();
    private final Semaphore udpPermits = new Semaphore(64);
    private final String username = "nok", password;
    private volatile boolean closed;
    private final AtomicLong fragments = new AtomicLong();
    private static ThreadPoolExecutor pool(int max) {
        return new ThreadPoolExecutor(0, max, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
    }
    public LocalProxy(DnsHandler dns, HostPolicy policy) throws IOException {
        this(dns, policy, host -> false);
    }
    public LocalProxy(DnsHandler dns, HostPolicy policy, HostPolicy bypass) throws IOException {
        this.dns = dns; this.policy = policy; this.bypass = bypass;
        byte[] random = new byte[24]; new SecureRandom().nextBytes(random);
        StringBuilder token = new StringBuilder(); for (byte b : random) token.append(String.format(Locale.ROOT, "%02x", b & 255));
        password = token.toString();
        listener = new ServerSocket(); listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 64);
        Thread accept = new Thread(this::accept, "nok-local-proxy"); accept.setDaemon(true); accept.start();
    }
    public int port() { return listener.getLocalPort(); }
    public String username() { return username; }
    public String password() { return password; }
    public long fragmentCount() { return fragments.get(); }
    private void accept() {
        while (!closed) {
            try {
                Socket socket = listener.accept(); sockets.add(socket);
                if (closed) { dispose(socket); break; }
                try { connections.execute(() -> serve(socket)); }
                catch (RejectedExecutionException e) { dispose(socket); }
            } catch (IOException e) { if (!closed) close(); }
        }
    }
    private void serve(Socket client) {
        Socket remote = null;
        try {
            client.setTcpNoDelay(true); client.setSoTimeout(5000);
            DataInputStream in = new DataInputStream(client.getInputStream()); OutputStream out = client.getOutputStream();
            if (in.readUnsignedByte() != 5) return;
            int methods = in.readUnsignedByte(); boolean passwordMethod = false;
            for (int i = 0; i < methods; i++) if (in.readUnsignedByte() == 2) passwordMethod = true;
            out.write(new byte[]{5, (byte) (passwordMethod ? 2 : 255)}); out.flush();
            if (!passwordMethod || in.readUnsignedByte() != 1) return;
            byte[] user = new byte[in.readUnsignedByte()]; in.readFully(user);
            byte[] pass = new byte[in.readUnsignedByte()]; in.readFully(pass);
            boolean valid = MessageDigest.isEqual(user, username.getBytes(StandardCharsets.UTF_8))
                    && MessageDigest.isEqual(pass, password.getBytes(StandardCharsets.UTF_8));
            out.write(new byte[]{1, (byte) (valid ? 0 : 1)}); out.flush(); if (!valid) return;
            if (in.readUnsignedByte() != 5) return;
            int command = in.readUnsignedByte(); if (in.readUnsignedByte() != 0) return;
            Destination target = Destination.read(in);
            if (command == 3) { udpAssociate(client, in, out); return; }
            if (command != 1) { reply(out, 7, 0); return; }
            if (target.port == 53) {
                reply(out, 0, 0); client.setSoTimeout(60000);
                while (!closed) {
                    int length;
                    try { length = in.readUnsignedShort(); } catch (EOFException e) { break; }
                    if (length < 12) break;
                    byte[] query = new byte[length]; in.readFully(query);
                    byte[] answer = dns.answer(query); out.write((answer.length >>> 8) & 255); out.write(answer.length & 255); out.write(answer); out.flush();
                }
                return;
            }
            if (target.host != null && policy.blocks(target.host)) { reply(out, 2, 0); return; }
            remote = new Socket(); sockets.add(remote);
            if (closed) return;
            remote.setTcpNoDelay(true); remote.connect(new InetSocketAddress(target.address(), target.port), 8000);
            // Messaging and server-first protocols may stay quiet for minutes. The tunnel
            // owns session expiry; a read timeout in either direction must not kill both.
            remote.setSoTimeout(0); client.setSoTimeout(0); reply(out, 0, 0);
            Socket upstream = remote;
            CountDownLatch downstreamDone = new CountDownLatch(1);
            io.execute(() -> {
                try { copy(upstream.getInputStream(), client.getOutputStream()); }
                catch (IOException ignored) { dispose(client); dispose(upstream); }
                finally {
                    try { client.shutdownOutput(); } catch (IOException ignored) {}
                    downstreamDone.countDown();
                }
            });
            OutputStream upload = remote.getOutputStream();
            byte[] first = readFirst(client, in);
            if (first.length > 0) {
                TlsHello.Plan plan = TlsHello.split(first);
                if (plan != null && plan.serverName != null && policy.blocks(plan.serverName)) return;
                if (plan == null || !bypass.blocks(plan.serverName)) upload.write(first);
                else {
                    for (int i = 0; i < plan.records.length; i++) {
                        upload.write(plan.records[i]); upload.flush();
                        if (i + 1 < plan.records.length) Thread.sleep(20);
                    }
                    fragments.incrementAndGet();
                }
                copy(in, upload);
            }
            try { remote.shutdownOutput(); } catch (IOException ignored) {}
            while (!closed && !downstreamDone.await(1, TimeUnit.SECONDS)) { /* Preserve a client half-close until the server finishes. */ }
        } catch (IOException | InterruptedException | RejectedExecutionException ignored) {
            if (Thread.currentThread().isInterrupted()) Thread.currentThread().interrupt();
        } finally { dispose(client); if (remote != null) dispose(remote); }
    }
    private byte[] readFirst(Socket client, InputStream in) throws IOException {
        byte[] bytes = new byte[TlsHello.MAX_RECORD + 5];
        int count = in.read(bytes); if (count <= 0) return new byte[0];
        if (bytes[0] != 22 || (count > 1 && bytes[1] != 3)) return Arrays.copyOf(bytes, count);
        client.setSoTimeout(500);
        try {
            while (count < 5) { int n = in.read(bytes, count, 5 - count); if (n < 0) break; count += n; }
            int desired = TlsHello.recordSize(bytes, count);
            while (desired > count) { int n = in.read(bytes, count, desired - count); if (n < 0) break; count += n; }
        } catch (SocketTimeoutException ignored) { /* Forward incomplete/unusual handshakes unchanged. */ }
        finally { client.setSoTimeout(0); }
        return Arrays.copyOf(bytes, count);
    }
    private void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[16384]; int count;
        while (!closed && (count = in.read(buffer)) != -1) { out.write(buffer, 0, count); out.flush(); }
    }
    private static void reply(OutputStream out, int status, int port) throws IOException {
        out.write(new byte[]{5, (byte) status, 0, 1, 127, 0, 0, 1, (byte) (port >>> 8), (byte) port}); out.flush();
    }
    private void udpAssociate(Socket control, InputStream in, OutputStream out) throws IOException {
        DatagramSocket relay = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0)); datagrams.add(relay);
        Set<UdpFlow> flows = ConcurrentHashMap.newKeySet();
        try {
            if (closed) return;
            reply(out, 0, relay.getLocalPort()); control.setSoTimeout(1000);
            io.execute(() -> udpReceive(relay, flows));
            while (!closed) {
                try { if (in.read() < 0) break; } catch (SocketTimeoutException ignored) {}
            }
        } finally { relay.close(); datagrams.remove(relay); for (UdpFlow f : flows) f.close(); }
    }
    private void udpReceive(DatagramSocket relay, Set<UdpFlow> flows) {
        Map<String, UdpFlow> targets = new HashMap<>(); SocketAddress peer = null;
        try {
            byte[] bytes = new byte[65535];
            while (!closed && !relay.isClosed()) {
                DatagramPacket packet = new DatagramPacket(bytes, bytes.length); relay.receive(packet);
                if (!packet.getAddress().isLoopbackAddress()) continue;
                if (peer == null) peer = packet.getSocketAddress();
                if (!peer.equals(packet.getSocketAddress()) || packet.getLength() < 7) continue;
                ByteArrayInputStream raw = new ByteArrayInputStream(bytes, 0, packet.getLength());
                DataInputStream in = new DataInputStream(raw);
                if (in.readUnsignedShort() != 0 || in.readUnsignedByte() != 0) continue;
                Destination dest;
                try { dest = Destination.read(in); } catch (IOException e) { continue; }
                int offset = packet.getLength() - raw.available();
                byte[] payload = Arrays.copyOfRange(bytes, offset, packet.getLength());
                byte[] header = Arrays.copyOf(bytes, offset);
                SocketAddress client = peer;
                if (dest.port == 53) {
                    if (payload.length < 12) continue;
                    try { dnsWorkers.execute(() -> {
                        try {
                            byte[] answer = dns.answer(payload);
                            if (answer.length > DnsMessage.udpLimit(payload)) answer = DnsMessage.truncated(payload);
                            sendUdp(relay, client, header, answer);
                        } catch (IOException ignored) {}
                    }); } catch (RejectedExecutionException ignored) {}
                    continue;
                }
                // QUIC (UDP 443) is ordinary traffic too. Never blackhole it globally.
                if (dest.host != null && policy.blocks(dest.host)) continue;
                String key = dest.key();
                targets.entrySet().removeIf(e -> e.getValue().socket.isClosed());
                UdpFlow flow = targets.get(key);
                if (flow == null) {
                    if (targets.size() >= 16 || !udpPermits.tryAcquire()) continue;
                    try {
                        flow = new UdpFlow(relay, client, header, dest, flows);
                        targets.put(key, flow); flows.add(flow); io.execute(flow);
                    } catch (IOException | RejectedExecutionException e) {
                        if (flow != null) flow.close(); else udpPermits.release();
                        continue;
                    }
                }
                try { flow.socket.send(new DatagramPacket(payload, payload.length)); } catch (IOException e) { flow.close(); }
            }
        } catch (IOException ignored) {}
        finally { for (UdpFlow flow : flows) flow.close(); relay.close(); datagrams.remove(relay); }
    }
    private void sendUdp(DatagramSocket relay, SocketAddress client, byte[] header, byte[] payload) throws IOException {
        if (closed || relay.isClosed() || header.length + payload.length > 65507) return;
        byte[] bytes = Arrays.copyOf(header, header.length + payload.length);
        System.arraycopy(payload, 0, bytes, header.length, payload.length);
        relay.send(new DatagramPacket(bytes, bytes.length, client));
    }
    private final class UdpFlow implements Runnable, Closeable {
        final DatagramSocket socket;
        final DatagramSocket relay;
        final SocketAddress client;
        final byte[] header;
        final Set<UdpFlow> owner;
        final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        UdpFlow(DatagramSocket relay, SocketAddress client, byte[] header, Destination destination, Set<UdpFlow> owner) throws IOException {
            this.relay = relay; this.client = client; this.header = header; this.owner = owner;
            socket = new DatagramSocket(); datagrams.add(socket);
            try { socket.connect(destination.address(), destination.port); socket.setSoTimeout(60000); }
            catch (IOException e) { socket.close(); datagrams.remove(socket); throw e; }
        }
        public void run() {
            try {
                byte[] buffer = new byte[65507];
                while (!closed && !socket.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length); socket.receive(packet);
                    sendUdp(relay, client, header, Arrays.copyOf(buffer, packet.getLength()));
                }
            } catch (IOException ignored) {} finally { close(); }
        }
        public void close() {
            if (done.compareAndSet(false, true)) { socket.close(); datagrams.remove(socket); owner.remove(this); udpPermits.release(); }
        }
    }
    private static final class Destination {
        byte[] ip; String host; int port;
        static Destination read(DataInputStream in) throws IOException {
            Destination d = new Destination(); int type = in.readUnsignedByte();
            if (type == 1 || type == 4) { d.ip = new byte[type == 1 ? 4 : 16]; in.readFully(d.ip); }
            else if (type == 3) {
                byte[] name = new byte[in.readUnsignedByte()]; if (name.length == 0) throw new IOException("Empty SOCKS host"); in.readFully(name);
                d.host = DomainRules.normalize(new String(name, StandardCharsets.US_ASCII));
                if (d.host == null) throw new IOException("Invalid SOCKS host");
            } else throw new IOException("Unknown SOCKS address");
            d.port = in.readUnsignedShort(); return d;
        }
        InetAddress address() throws UnknownHostException { return ip == null ? InetAddress.getByName(host) : InetAddress.getByAddress(ip); }
        String key() { return (host == null ? Arrays.toString(ip) : host) + ":" + port; }
    }
    private void dispose(Socket socket) { sockets.remove(socket); try { socket.close(); } catch (IOException ignored) {} }
    @Override public void close() {
        closed = true;
        try { listener.close(); } catch (IOException ignored) {}
        for (Socket socket : sockets) dispose(socket);
        for (DatagramSocket socket : datagrams) socket.close();
        datagrams.clear(); connections.shutdownNow(); io.shutdownNow(); dnsWorkers.shutdownNow();
    }
}
