package dev.nok.adblock;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Tests the same tunnel ownership/cancellation code used by the Android VPN service. */
public final class ShutdownTests {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    private static final class Descriptor implements Closeable {
        final AtomicInteger closes = new AtomicInteger();
        final boolean fail;
        Descriptor() { this(false); }
        Descriptor(boolean fail) { this.fail = fail; }
        public void close() throws IOException {
            closes.incrementAndGet();
            if (fail) throw new IOException("simulated descriptor-close error");
        }
    }
    public static void main(String[] args) throws Exception {
        TunnelLease running = new TunnelLease(); Descriptor active = new Descriptor();
        check(running.attach(active), "attach running tunnel");
        running.close();
        check(running.isStopped() && active.closes.get() == 1, "Stop releases the tunnel without a service destruction callback");
        running.close(); running.close();
        check(active.closes.get() == 1, "repeated stop and onDestroy cannot double-close a descriptor");
        TunnelLease cancelled = new TunnelLease(); cancelled.close();
        Descriptor late = new Descriptor();
        check(!cancelled.attach(late) && late.closes.get() == 1, "a tunnel opened after cancellation closes immediately");
        TunnelLease opening = new TunnelLease(); Descriptor delayed = new Descriptor();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> establish = pool.submit(() -> { entered.countDown(); release.await(); return opening.attach(delayed); });
            check(entered.await(2, TimeUnit.SECONDS), "delayed establishment entered");
            opening.close(); release.countDown();
            check(!establish.get(2, TimeUnit.SECONDS) && delayed.closes.get() == 1,
                    "Stop during establishment cannot re-enable the VPN when establishment completes");
            for (int i = 0; i < 1000; i++) {
                TunnelLease racing = new TunnelLease(); Descriptor descriptor = new Descriptor();
                CountDownLatch start = new CountDownLatch(1);
                Future<?> attach = pool.submit(() -> { start.await(); racing.attach(descriptor); return null; });
                Future<?> stop = pool.submit(() -> { start.await(); racing.close(); return null; });
                start.countDown(); attach.get(2, TimeUnit.SECONDS); stop.get(2, TimeUnit.SECONDS);
                if (!racing.isStopped() || descriptor.closes.get() != 1)
                    throw new AssertionError("descriptor leak or double-close in cancellation race " + i);
            }
            check(true, "1000 concurrent open/stop races release exactly one descriptor");
        } finally { release.countDown(); pool.shutdownNow(); }
        TunnelLease old = new TunnelLease(), fresh = new TunnelLease();
        Descriptor oldFd = new Descriptor(), freshFd = new Descriptor();
        old.attach(oldFd); old.close(); fresh.attach(freshFd); old.close();
        check(!fresh.isStopped() && freshFd.closes.get() == 0, "old-run cleanup cannot close a newly started tunnel");
        fresh.close();
        TunnelLease failed = new TunnelLease(); Descriptor badFd = new Descriptor(true); failed.attach(badFd);
        try { failed.close(); throw new AssertionError("expected close error"); }
        catch (IOException expected) { check(failed.isStopped(), "descriptor-close errors leave the run cancelled"); }
        failed.close();
        check(badFd.closes.get() == 1, "close error cannot trigger a later double-close of a reused descriptor number");
        TunnelLease nativeRun = new TunnelLease(); Descriptor nativeFd = new Descriptor(); nativeRun.attach(nativeFd);
        nativeRun.cancel();
        check(nativeRun.isStopped() && nativeFd.closes.get() == 0, "native reader receives cancellation before descriptor close");
        nativeRun.close();
        check(nativeFd.closes.get() == 1, "descriptor closes after native reader has stopped");
        System.out.println("PASS: " + checks + " shutdown checks (including 1000 concurrent open/stop races)");
    }
}
