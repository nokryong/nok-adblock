package dev.nok.adblock;

import java.net.*;
import java.util.concurrent.*;

/** Regression for messaging connections killed by the old 60-second relay read timeout. */
public final class IdleProxyTests {
    public static void main(String[] args) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (LocalProxy proxy = new LocalProxy(q -> DnsMessage.error(q, 3), host -> false);
             ServerSocket server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
            CountDownLatch ready = new CountDownLatch(1);
            Future<?> upstream = worker.submit(() -> {
                try (Socket accepted = server.accept()) {
                    accepted.setSoTimeout(75000); ready.countDown();
                    if (accepted.getInputStream().read() != 7) throw new AssertionError("idle upload lost");
                    accepted.getOutputStream().write(8);
                } catch (java.io.IOException e) { throw new RuntimeException(e); }
            });
            try (Socket client = ProxyTests.connect(proxy, "127.0.0.1", server.getLocalPort())) {
                if (!ready.await(5, TimeUnit.SECONDS)) throw new AssertionError("server not connected");
                System.out.println("Checking a messaging-style connection after 65 seconds with no traffic...");
                Thread.sleep(65000);
                client.getOutputStream().write(7); client.getOutputStream().flush();
                if (client.getInputStream().read() != 8) throw new AssertionError("idle download lost");
                upstream.get(5, TimeUnit.SECONDS);
                System.out.println("PASS: upload and download survive 65 seconds idle");
            }
        } finally { worker.shutdownNow(); }
    }
}
