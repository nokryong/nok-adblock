package dev.nok.adblock;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Bounded TCP endpoint for length-prefixed DNS, including segmentation and retransmission. */
public final class TcpDnsSession {
    public interface Resolver { void resolve(byte[] query, Consumer<byte[]> reply); }
    private final IpPacket template;
    private final Consumer<byte[]> sender;
    private final Resolver resolver;
    private final LongSupplier clock;
    private final Deque<Segment> flight = new ArrayDeque<>();
    private byte[] input = new byte[0], pending = new byte[0];
    private int nextClient, nextServer, peerWindow = 16384, unackedBytes, activeQueries;
    private boolean established, closed, finSent, peerFin;
    private long touched;
    private static final byte[] EMPTY = new byte[0];
    private static final class Segment {
        final int seq, flags, length;
        final byte[] bytes;
        long time; int retries;
        Segment(int seq, int flags, byte[] bytes, long now) {
            this.seq = seq; this.flags = flags; this.bytes = bytes; this.time = now;
            length = bytes.length + ((flags & 3) != 0 ? 1 : 0);
        }
    }
    public TcpDnsSession(IpPacket syn, int initialSeq, long now, Consumer<byte[]> sender, Resolver resolver) {
        this(syn, initialSeq, now, sender, resolver, () -> System.nanoTime() / 1000000L);
    }
    public TcpDnsSession(IpPacket syn, int initialSeq, long now, Consumer<byte[]> sender, Resolver resolver, LongSupplier clock) {
        template = syn; this.sender = sender; this.resolver = resolver;
        this.clock = clock;
        nextClient = syn.seq + 1; nextServer = initialSeq; touched = now;
        peerWindow = syn.window; send(0x12, EMPTY, now);
    }
    public synchronized void receive(IpPacket p, long now) {
        if (closed) return;
        touched = now;
        if ((p.flags & 4) != 0) { closed = true; return; }
        if ((p.flags & 2) != 0) { retransmitFirst(now); return; }
        if ((p.flags & 16) == 0) return;
        // Reject acknowledgements for bytes that have never been sent.
        if (p.ack - nextServer > 0) { ack(); return; }
        while (!flight.isEmpty()) {
            Segment s = flight.peek();
            if (p.ack - (s.seq + s.length) < 0) break;
            flight.remove(); unackedBytes -= s.length;
        }
        if (finSent && flight.isEmpty()) { closed = true; return; }
        if (!established) {
            if (!flight.isEmpty() || p.seq != nextClient) return;
            established = true;
        }
        peerWindow = p.window;
        if (p.data.length > 0 || (p.flags & 1) != 0) {
            if (p.seq != nextClient) { ack(); pump(now); return; }
            if (input.length + p.data.length > 65537 || activeQueries >= 16) { reset(); return; }
            byte[] joined = Arrays.copyOf(input, input.length + p.data.length);
            System.arraycopy(p.data, 0, joined, input.length, p.data.length); input = joined;
            nextClient += p.data.length;
            ack();
            while (input.length >= 2) {
                int length = DnsMessage.u16(input, 0);
                if (length < 12) { reset(); return; }
                if (input.length < length + 2) break;
                if (activeQueries >= 16) { reset(); return; }
                byte[] query = Arrays.copyOfRange(input, 2, length + 2);
                input = Arrays.copyOfRange(input, length + 2, input.length);
                activeQueries++;
                resolver.resolve(query, response -> answer(response));
            }
            if ((p.flags & 1) != 0) {
                nextClient++; ack();
                // DNS clients close after the answer. A half-close waits for pending work.
                peerFin = true;
            }
        }
        pump(now);
    }
    private synchronized void answer(byte[] response) {
        activeQueries--;
        if (closed || response.length > 65535) return;
        if (pending.length + response.length + 2 > 262144) { reset(); return; }
        int old = pending.length;
        pending = Arrays.copyOf(pending, old + response.length + 2);
        DnsMessage.put16(pending, old, response.length);
        System.arraycopy(response, 0, pending, old + 2, response.length);
        pump(clock.getAsLong());
    }
    private void pump(long now) {
        if (!established || closed) return;
        while (pending.length > 0 && unackedBytes < Math.min(peerWindow, 16384)) {
            int length = Math.min(1200, Math.min(pending.length, Math.min(peerWindow, 16384) - unackedBytes));
            byte[] part = Arrays.copyOf(pending, length);
            pending = Arrays.copyOfRange(pending, length, pending.length);
            send(0x18, part, now);
        }
        if (peerFin && !finSent && activeQueries == 0 && pending.length == 0 && flight.isEmpty()) {
            finSent = true;
            send(0x11, EMPTY, now);
        }
    }
    private void send(int flags, byte[] bytes, long now) {
        Segment s = new Segment(nextServer, flags, bytes, now);
        flight.add(s); nextServer += s.length; unackedBytes += s.length;
        sender.accept(template.tcpReply(s.seq, nextClient, flags, bytes));
    }
    private void ack() { sender.accept(template.tcpReply(nextServer, nextClient, 0x10, EMPTY)); }
    private void reset() { sender.accept(template.tcpReply(nextServer, nextClient, 0x14, EMPTY)); closed = true; }
    private void retransmitFirst(long now) {
        Segment s = flight.peek();
        if (s != null) { s.time = now; sender.accept(template.tcpReply(s.seq, nextClient, s.flags, s.bytes)); }
    }
    public synchronized boolean tick(long now) {
        if (closed || now - touched > 30000) { closed = true; return false; }
        Segment first = flight.peek();
        if (first != null && now - first.time > (1000L << Math.min(3, first.retries))) {
            if (++first.retries > 4) { closed = true; return false; }
            retransmitFirst(now);
        }
        pump(now);
        return true;
    }
}
