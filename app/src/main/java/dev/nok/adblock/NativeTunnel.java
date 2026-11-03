package dev.nok.adblock;

import hev.htproxy.TProxyService;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** JNI tunnel worker. Only the reader thread starts/joins native code, never the UI thread. */
public final class NativeTunnel {
    private NativeTunnel() {}
    public static void run(File cache, int fd, LocalProxy proxy, TunnelLease lease, Runnable ready) throws IOException, InterruptedException {
        // Only one tunnel runs at a time; anything left here came from a killed process.
        File[] stale = cache.listFiles((dir, name) -> name.startsWith("nok-tunnel-") && name.endsWith(".yml"));
        if (stale != null) for (File file : stale) file.delete();
        File config = File.createTempFile("nok-tunnel-", ".yml", cache);
        boolean started = false;
        try {
            String yaml = "tunnel:\n  mtu: 1500\n  ipv4: 10.111.0.1\n  ipv6: 'fd11:111::1'\n  icmp: 'off'\n"
                    + "socks5:\n  address: 127.0.0.1\n  port: " + proxy.port() + "\n  udp: 'udp'\n"
                    + "  username: '" + proxy.username() + "'\n  password: '" + proxy.password() + "'\n"
                    + "misc:\n  task-stack-size: 65536\n  tcp-buffer-size: 16384\n  max-session-count: 128\n"
                    + "  connect-timeout: 10000\n  tcp-read-write-timeout: 300000\n  udp-read-write-timeout: 60000\n"
                    + "  log-file: null\n  log-level: error\n";
            try (OutputStream out = new FileOutputStream(config)) { out.write(yaml.getBytes(StandardCharsets.UTF_8)); }
            if (lease.isStopped()) return;
            if (!TProxyService.TProxyStartService(config.getAbsolutePath(), fd)) throw new IOException("접속 우회 엔진을 시작하지 못했습니다");
            started = true;
            Thread.sleep(100);
            if (!TProxyService.TProxyIsRunning() && !lease.isStopped()) throw new IOException("접속 우회 엔진 초기화 실패");
            if (!lease.isStopped()) ready.run();
            while (!lease.isStopped()) {
                if (!TProxyService.TProxyIsRunning()) throw new IOException("접속 우회 엔진이 종료되었습니다");
                Thread.sleep(100);
            }
        } finally {
            try {
                if (started && !TProxyService.TProxyStopService()) throw new IOException("접속 우회 엔진 종료 실패");
            } finally { config.delete(); }
        }
    }
}
