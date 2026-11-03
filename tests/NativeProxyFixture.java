package dev.nok.adblock;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Loopback-only DNS fixture used to exercise the packaged Android native tunnel over a socket pair. */
public final class NativeProxyFixture {
    public static void main(String[] args) throws Exception {
        Path ready = Paths.get(args[0]), stop = Paths.get(args[1]);
        Files.deleteIfExists(stop);
        try (LocalProxy proxy = new LocalProxy(q -> DnsMessage.error(q, 3), host -> false)) {
            String json = "{\"port\":" + proxy.port() + ",\"username\":\"" + proxy.username()
                    + "\",\"password\":\"" + proxy.password() + "\"}";
            Files.write(ready, json.getBytes(StandardCharsets.UTF_8));
            long deadline = System.nanoTime() + 120000000000L;
            while (!Files.exists(stop) && System.nanoTime() < deadline) Thread.sleep(100);
        } finally { Files.deleteIfExists(ready); }
    }
}
