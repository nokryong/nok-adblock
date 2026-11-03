package dev.nok.adblock;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.VpnService;
import android.os.*;
import android.system.*;
import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class DnsVpnService extends VpnService {
    public static final String START = "dev.nok.START", STOP = "dev.nok.STOP";
    public static volatile String state = "OFF", lastError = "";
    public static volatile boolean bypassActive;
    private static volatile DnsVpnService instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Session current;

    /** A run owns all resources; an old callback cannot write into a new VPN. */
    private static final class Session {
        final boolean bypass;
        final TunnelLease lease = new TunnelLease();
        final Object outputLock = new Object();
        final AtomicBoolean cleanupStarted = new AtomicBoolean();
        final DohResolver resolver = new DohResolver();
        final ThreadPoolExecutor workers = new ThreadPoolExecutor(6, 6, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(96), r -> new Thread(r, "nok-dns"));
        final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor();
        final Map<String, TcpDnsSession> tcp = new HashMap<>();
        volatile ParcelFileDescriptor tunnel;
        volatile AppStore store;
        volatile String failure;
        volatile boolean userStopped, releaseFailed;
        volatile boolean nativeInUse;
        volatile long dohRetryAt;
        volatile LocalProxy proxy;
        Thread reader;
        Session(boolean bypass) { this.bypass = bypass; }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("nok", Context.MODE_PRIVATE);
    }
    public static void requestStart(Context context) {
        preferences(context).edit().putBoolean("wanted", true).apply();
        try {
            context.startForegroundService(new Intent(context, DnsVpnService.class).setAction(START));
        } catch (RuntimeException e) {
            preferences(context).edit().putBoolean("wanted", false).apply();
            throw e;
        }
    }
    /** stopService alone cannot tear down a VPN still bound by Android. */
    public static void requestStop(Context context) {
        preferences(context).edit().putBoolean("wanted", false).apply();
        DnsVpnService target = instance;
        if (target != null) {
            if (Looper.myLooper() == Looper.getMainLooper()) target.stopByUser();
            else target.main.post(() -> { if (instance == target) target.stopByUser(); });
        } else {
            context.stopService(new Intent(context, DnsVpnService.class));
            state = "OFF";
            ProtectionTileService.refresh(context);
        }
    }
    @Override public void onCreate() { super.onCreate(); instance = this; AppUpdater.schedule(this); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            stopByUser(); return START_NOT_STICKY;
        }
        // Delayed start requests and sticky restarts must respect the latest user intent.
        if (!preferences(this).getBoolean("wanted", false)) {
            stopByUser(); return START_NOT_STICKY;
        }
        if (current != null) return current.lease.isStopped() ? START_NOT_STICKY : START_STICKY;
        lastError = "";
        boolean bypass = preferences(this).getBoolean("bypass", false) && Build.VERSION.SDK_INT >= 29;
        Session session = new Session(bypass); current = session;
        publishState("STARTING");
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("protection", "광고 차단 상태", NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, DnsVpnService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification = new Notification.Builder(this, "protection").setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("NOK 보호").setContentText(bypass ? "광고 차단과 접속 우회를 사용하고 있습니다" : "DNS 광고 차단을 사용하고 있습니다")
                    .setOngoing(true).setContentIntent(open).addAction(new Notification.Action.Builder(null, "차단 끄기", stop).build()).build();
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(1, notification);
            session.reader = new Thread(() -> runTunnel(session), "nok-tunnel");
            session.reader.start(); return START_STICKY;
        } catch (Exception e) {
            session.failure = "차단 서비스를 시작하지 못했습니다: " + e.getClass().getSimpleName();
            closeSession(session); finishSession(session); return START_NOT_STICKY;
        }
    }
    private void runTunnel(Session session) {
        try {
            session.store = AppStore.get(this);
            if (session.lease.isStopped()) return;
            if (session.store.rules.size() == 0) throw new IOException("차단 목록이 비어 있습니다");
            Builder builder = new Builder().setSession("NOK DNS 광고 차단").setMtu(1500)
                    .addAddress("10.111.0.1", 32).addDnsServer("10.111.0.2").addRoute("10.111.0.2", 32)
                    .allowFamily(OsConstants.AF_INET6).setBlocking(false).setConfigureIntent(
                            PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            builder.addDisallowedApplication(getPackageName());
            if (session.bypass) {
                builder.setSession("NOK 광고 차단 · 접속 우회").addRoute("0.0.0.0", 0)
                        .addAddress("fd11:111::1", 128).addRoute("::", 0);
                LocalProxy created = new LocalProxy(query -> resolveBlocking(session, query),
                        host -> session.store.rules.blocks(host), host -> session.store.bypassRules.blocks(host));
                synchronized (session.outputLock) {
                    if (session.lease.isStopped()) { created.close(); return; }
                    session.proxy = created;
                }
            }
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
            if (session.lease.isStopped()) return;
            ParcelFileDescriptor opened = builder.establish();
            if (opened == null) throw new IOException("VPN 연결 승인이 필요합니다");
            // If Stop raced with establish(), attach closes the late descriptor immediately.
            if (!session.lease.attach(opened)) return;
            session.tunnel = opened;
            if (session.lease.isStopped()) return;
            if (session.bypass) {
                synchronized (session.outputLock) {
                    if (session.lease.isStopped()) return;
                    session.nativeInUse = true;
                }
                try {
                    session.maintenance.scheduleAtFixedRate(() -> {
                        if (!session.lease.isStopped()) session.store.persist();
                    }, 10, 10, TimeUnit.SECONDS);
                    NativeTunnel.run(getCacheDir(), opened.getFd(), session.proxy, session.lease, () -> main.post(() -> {
                        if (current == session && !session.lease.isStopped()) { bypassActive = true; publishState("ON"); }
                    }));
                } finally {
                    synchronized (session.outputLock) {
                        session.nativeInUse = false;
                        session.lease.close();
                    }
                }
                return;
            }
            main.post(() -> {
                if (current == session && !session.lease.isStopped()) publishState("ON");
            });
            session.maintenance.scheduleAtFixedRate(new Runnable() {
                int ticks;
                public void run() {
                    if (session.lease.isStopped()) return;
                    long now = SystemClock.elapsedRealtime();
                    synchronized (session.tcp) { session.tcp.values().removeIf(s -> !s.tick(now)); }
                    if (++ticks % 10 == 0) session.store.persist();
                }
            }, 1, 1, TimeUnit.SECONDS);
            StructPollfd poll = new StructPollfd(); poll.fd = opened.getFileDescriptor(); poll.events = (short) OsConstants.POLLIN;
            byte[] buffer = new byte[65535];
            while (!session.lease.isStopped()) {
                int ready = Os.poll(new StructPollfd[]{poll}, 250);
                if (session.lease.isStopped()) break;
                if (ready == 0) continue;
                if ((poll.revents & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0) throw new IOException("VPN 연결이 종료되었습니다");
                int count;
                try {
                    synchronized (session.outputLock) {
                        if (session.lease.isStopped()) break;
                        count = Os.read(poll.fd, buffer, 0, buffer.length);
                    }
                }
                catch (ErrnoException e) { if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue; throw e; }
                IpPacket packet = IpPacket.parse(buffer, count);
                if (packet == null) continue;
                if (packet.protocol == 17) resolve(session, packet.data, response -> write(session,
                        packet.udpReply(response.length > DnsMessage.udpLimit(packet.data) ? DnsMessage.truncated(packet.data) : response)));
                else handleTcp(session, packet);
            }
        } catch (Exception | LinkageError e) {
            if (!session.lease.isStopped()) session.failure = "DNS 연결 오류: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            closeSession(session);
            main.post(() -> finishSession(session));
        }
    }
    private byte[] resolveBlocking(Session session, byte[] query) throws IOException {
        if (session.lease.isStopped()) throw new IOException("중지됨");
        try {
            if (query.length < 12 || (query[2] & 0x80) != 0) return DnsMessage.error(query, 1);
            DnsMessage.Question question = DnsMessage.question(query);
            boolean blocked = session.store.rules.blocks(question.domain);
            session.store.query(question.domain, blocked);
            if (blocked) return DnsMessage.error(query, 3);
            return lookup(session, query);
        } catch (IOException e) {
            if (!session.lease.isStopped()) session.store.error();
            return DnsMessage.error(query, 2);
        }
    }
    /** Encrypted DNS first. Captive portals and DoH outages fall back to the network's resolver. */
    private byte[] lookup(Session session, byte[] query) throws IOException {
        boolean fallback = session.store.fallbackDns();
        if (!fallback || SystemClock.elapsedRealtime() >= session.dohRetryAt) {
            try {
                byte[] answer = session.resolver.resolve(session.store.endpoint(), query);
                session.dohRetryAt = 0; return answer;
            } catch (IOException e) {
                if (!fallback || session.lease.isStopped()) throw e;
                session.dohRetryAt = SystemClock.elapsedRealtime() + 30000; // Skip the 6s timeout for a while.
            }
        }
        return networkLookup(query);
    }
    private byte[] networkLookup(byte[] query) throws IOException {
        ConnectivityManager manager = getSystemService(ConnectivityManager.class);
        Network network = manager.getActiveNetwork(); // This app is excluded from its own VPN.
        LinkProperties link = network == null ? null : manager.getLinkProperties(network);
        if (link == null) throw new IOException("기본 네트워크 없음");
        IOException last = new IOException("기본 DNS 없음"); int tries = 0;
        for (InetAddress server : link.getDnsServers()) {
            if (++tries > 2) break;
            try { return PlainDns.exchange(server, 53, query, 2500, network::bindSocket); }
            catch (IOException e) { last = e; }
        }
        throw last;
    }
    private void handleTcp(Session session, IpPacket packet) {
        String key = packet.source + ":" + packet.sourcePort;
        synchronized (session.tcp) {
            TcpDnsSession tcpSession = session.tcp.get(key);
            long now = SystemClock.elapsedRealtime();
            if (tcpSession == null) {
                if ((packet.flags & 2) == 0) {
                    if ((packet.flags & 4) == 0) write(session, packet.tcpReply(packet.ack, packet.seq + packet.data.length + ((packet.flags & 1) != 0 ? 1 : 0), 0x14, new byte[0]));
                    return;
                }
                if (session.tcp.size() >= 64) return;
                tcpSession = new TcpDnsSession(packet, ThreadLocalRandom.current().nextInt(), now,
                        bytes -> write(session, bytes), (q, reply) -> resolve(session, q, reply), SystemClock::elapsedRealtime);
                session.tcp.put(key, tcpSession);
            } else tcpSession.receive(packet, now);
        }
    }
    private void resolve(Session session, byte[] query, Consumer<byte[]> reply) {
        if (session.lease.isStopped()) return;
        try {
            if ((query[2] & 0x80) != 0) return;
            DnsMessage.Question question = DnsMessage.question(query);
            boolean blocked = session.store.rules.blocks(question.domain);
            session.store.query(question.domain, blocked);
            if (blocked) { reply.accept(DnsMessage.error(query, 3)); return; }
        } catch (Exception e) { reply.accept(DnsMessage.error(query, 1)); return; }
        try {
            session.workers.execute(() -> {
                byte[] response;
                try { response = lookup(session, query); }
                catch (Exception e) {
                    if (session.lease.isStopped()) return;
                    session.store.error(); response = DnsMessage.error(query, 2);
                }
                if (!session.lease.isStopped()) reply.accept(response);
            });
        } catch (RejectedExecutionException e) {
            if (!session.lease.isStopped()) { session.store.error(); reply.accept(DnsMessage.error(query, 2)); }
        }
    }
    private void write(Session session, byte[] packet) {
        synchronized (session.outputLock) {
            if (session.lease.isStopped() || session.tunnel == null) return;
            try { Os.write(session.tunnel.getFileDescriptor(), packet, 0, packet.length); }
            catch (Exception e) { if (!session.lease.isStopped()) session.store.error(); }
        }
    }
    private void stopByUser() {
        preferences(this).edit().putBoolean("wanted", false).apply();
        Session session = current;
        if (session == null) {
            publishState("OFF"); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return;
        }
        session.userStopped = true;
        publishState("STOPPING");
        closeSession(session); // Explicitly release the VPN without waiting for onDestroy.
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (session.reader == null || !session.reader.isAlive()) finishSession(session);
    }
    private void closeSession(Session session) {
        session.lease.cancel();
        try { synchronized (session.outputLock) { if (!session.nativeInUse) session.lease.close(); } }
        catch (IOException e) { session.releaseFailed = true; session.failure = "VPN 연결 종료 오류: " + e.getMessage(); }
        session.workers.shutdownNow(); session.maintenance.shutdownNow();
        if (session.cleanupStarted.compareAndSet(false, true)) {
            // HTTPS disconnect may wait; keep it off the UI thread and after VPN release.
            new Thread(() -> {
                if (session.proxy != null) session.proxy.close();
                session.resolver.close();
                if (session.store != null) session.store.persist();
            }, "nok-cleanup").start();
        }
    }
    private void finishSession(Session session) {
        if (current != session) {
            if (instance == null && "STOPPING".equals(state)) publishState("OFF");
            return;
        }
        current = null;
        preferences(this).edit().putBoolean("wanted", false).apply();
        boolean error = session.releaseFailed || (!session.userStopped && session.failure != null);
        lastError = error ? session.failure : "";
        publishState(error ? "ERROR" : "OFF");
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    private void publishState(String value) {
        if (!"ON".equals(value)) bypassActive = false;
        state = value; ProtectionTileService.refresh(this);
    }
    @Override public void onRevoke() { main.post(() -> { if (instance == this) stopByUser(); }); }
    @Override public void onDestroy() {
        Session session = current; current = null;
        if (session != null) {
            preferences(this).edit().putBoolean("wanted", false).apply();
            session.userStopped = true; closeSession(session);
        }
        if (instance == this) {
            instance = null;
            if (!"ERROR".equals(state)) publishState(session != null && session.nativeInUse ? "STOPPING" : "OFF");
        }
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
