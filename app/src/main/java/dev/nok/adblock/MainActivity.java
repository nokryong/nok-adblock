package dev.nok.adblock;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int BG = 0xff101a1d, CARD = 0xff1b282c, TEXT = 0xffedf4ef,
            MUTED = 0xffa2b2b0, GREEN = 0xff69e0b7, LINE = 0xff2c3c40;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private AppStore store;
    private LinearLayout root, body, tabs;
    private ScrollView scroll;
    private TextView statusTitle, statusDetail, queryCount, blockCount, errorCount, listCount, updateLabel;
    private Button power, updateButton;
    private ShieldView shield;
    private Switch bypassSwitch;
    private String page = "home", lastLogKey = "";
    private boolean resumed;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            refreshValues(); handler.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = AppStore.get(this);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                root.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root); root.requestApplyInsets();
        LinearLayout header = row(); header.setPadding(dp(24), dp(18), dp(24), dp(16)); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = text("N", 21, BG, true); mark.setGravity(Gravity.CENTER); mark.setBackground(shape(GREEN, 12));
        header.addView(mark, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout brand = column(); brand.setPadding(dp(12), 0, 0, 0);
        brand.addView(text("NOK", 21, TEXT, true)); TextView sub = text("DNS GUARD", 10, MUTED, true); sub.setLetterSpacing(.14f); brand.addView(sub);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView version = text("v" + AppUpdater.installedVersion(this), 12, MUTED, false); header.addView(version); root.addView(header);
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        body = column(); body.setPadding(dp(24), dp(12), dp(24), dp(24)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        tabs = row(); tabs.setPadding(dp(16), dp(10), dp(16), dp(10)); root.addView(tabs);
        if (state != null) page = state.getString("page", "home");
        if (AppUpdater.OPEN_UPDATE.equals(getIntent().getAction())) page = "home";
        showPage(page);
        ProtectionTileService.refresh(this);
        AppUpdater.schedule(this);
    }
    @Override protected void onSaveInstanceState(Bundle out) { out.putString("page", page); super.onSaveInstanceState(out); }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (AppUpdater.OPEN_UPDATE.equals(intent.getAction())) showPage("home");
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true; refresh.run();
        if (AppUpdater.checkDue(this)) checkUpdate(false);
    }
    @Override protected void onPause() { resumed = false; handler.removeCallbacks(refresh); store.persist(); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); io.shutdown(); super.onDestroy(); }
    private void showPage(String name) {
        int keep = name.equals(page) ? scroll.getScrollY() : 0;
        page = name; body.removeAllViews(); statusTitle = statusDetail = queryCount = blockCount = errorCount = listCount = updateLabel = null;
        power = updateButton = null; shield = null; bypassSwitch = null; tabs.removeAllViews();
        tab("home", "보호"); tab("logs", "차단 기록"); tab("settings", "설정");
        if (page.equals("logs")) logs(); else if (page.equals("settings")) settings(); else home();
        scroll.post(() -> scroll.scrollTo(0, keep)); refreshValues();
    }
    private void tab(String name, String title) {
        Button button = button(title, name.equals(page)); button.setTextSize(13);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1); p.setMargins(dp(4), 0, dp(4), 0);
        tabs.addView(button, p); button.setOnClickListener(v -> showPage(name));
    }
    private void home() {
        body.addView(text("조용한 인터넷,\n가벼운 하루.", 29, TEXT, true));
        addHint(body, "광고와 추적 도메인을 기기에서 걸러요.", 10, 22);
        AppUpdater.Ready update = AppUpdater.ready(this);
        if (update != null) {
            LinearLayout card = card(); card.addView(text("NOK " + update.version + " 업데이트 준비됨", 16, GREEN, true));
            addHint(card, "체크섬과 서명을 확인했어요. 설치 중 보호가 잠시 끊겼다가 다시 켜져요.", 8, 14);
            Button install = button("업데이트 설치", true); add(card, install, 0, 0, 48);
            install.setOnClickListener(v -> installUpdate()); add(body, card, 0, 16);
        }
        LinearLayout protection = card(); protection.setGravity(Gravity.CENTER_HORIZONTAL);
        shield = new ShieldView(); shield.setContentDescription("광고 차단 상태");
        protection.addView(shield, new LinearLayout.LayoutParams(dp(132), dp(132)));
        statusTitle = text("차단이 꺼져 있어요", 21, TEXT, true); statusTitle.setGravity(Gravity.CENTER); protection.addView(statusTitle);
        statusDetail = text("버튼을 눌러 DNS 보호를 시작하세요", 13, MUTED, false); statusDetail.setGravity(Gravity.CENTER);
        add(protection, statusDetail, 10, 20);
        power = button("보호 켜기", true); add(protection, power, 0, 0, 54); power.setOnClickListener(v -> toggle());
        add(body, protection, 0, 20);
        Button addTile = button("빠른 설정에 추가", false); add(body, addTile, 0, 20, 48);
        addTile.setOnClickListener(v -> addQuickSettingsTile());
        TextView today = text("오늘의 보호", 16, TEXT, true); add(body, today, 0, 12);
        LinearLayout stats = row();
        queryCount = stat(stats, "DNS 요청", false); blockCount = stat(stats, "광고·추적 차단", true); errorCount = stat(stats, "DNS 오류", false);
        add(body, stats, 0, 20);
        LinearLayout filter = card();
        filter.addView(text("차단 필터", 16, TEXT, true));
        listCount = text("", 14, GREEN, true); add(filter, listCount, 12, 6);
        updateLabel = text("", 12, MUTED, false); filter.addView(updateLabel);
        updateButton = button("차단 목록 갱신", false); add(filter, updateButton, 16, 0, 48);
        updateButton.setOnClickListener(v -> updateList()); add(body, filter, 0, 16);
        addHint(body, "차단 판단은 기기에서 · DNS는 HTTPS로 전송", 0, 8);
        addHint(body, "유튜브 영상 광고는 차단하지 않아요. 다른 VPN과 함께 쓸 수 없으며, 자체 보안 DNS를 사용하는 앱은 차단을 우회할 수 있어요.", 0, 0);
    }
    private TextView stat(LinearLayout parent, String label, boolean accent) {
        LinearLayout c = column(); c.setPadding(dp(12), dp(16), dp(8), dp(16)); c.setBackground(shape(CARD, 16));
        TextView value = text("0", 23, accent ? GREEN : TEXT, true); value.setMaxLines(1); c.addView(value);
        TextView caption = text(label, 10, MUTED, false); add(c, caption, 7, 0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); p.setMargins(0, 0, dp(6), 0); parent.addView(c, p); return value;
    }
    private void logs() {
        body.addView(text("차단 기록", 28, TEXT, true));
        addHint(body, "최근 100건을 메모리에만 보관해요.\n도메인을 누르면 허용 목록에 추가할 수 있어요.", 10, 22);
        lastLogKey = logKey();
        List<AppStore.LogEntry> entries = store.logs();
        if (entries.isEmpty()) {
            LinearLayout empty = card(); empty.addView(text(store.recordLogs ? "아직 차단 기록이 없어요" : "차단 기록 저장이 꺼져 있어요", 18, TEXT, true));
            addHint(empty, store.recordLogs ? "보호를 켜고 앱이나 웹사이트를 사용하면\n차단한 도메인이 여기에 나타나요." : "설정에서 ‘최근 차단 기록’을 켜 주세요.", 12, 0); body.addView(empty);
        }
        SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.KOREA);
        for (AppStore.LogEntry entry : entries) {
            LinearLayout item = card();
            TextView domain = text(entry.domain, 14, TEXT, true); domain.setBreakStrategy(0); item.addView(domain);
            addHint(item, time.format(new Date(entry.time)) + "  ·  광고·추적 도메인 차단", 8, 0);
            item.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("이 도메인 허용")
                    .setMessage(entry.domain + "\n\n이 도메인과 하위 도메인의 DNS 요청을 허용합니다.")
                    .setNegativeButton("취소", null).setPositiveButton("허용", (d, w) -> { store.allow(entry.domain, true); toast("허용 목록에 추가했습니다"); }).show());
            add(body, item, 0, 10);
        }
    }
    private String logKey() {
        List<AppStore.LogEntry> logs = store.logs(); return store.recordLogs + ":" + logs.size() + ":" + (logs.isEmpty() ? "" : logs.get(0).time);
    }
    private void settings() {
        body.addView(text("내 방식대로 보호", 28, TEXT, true));
        addHint(body, "연결과 예외 설정을 관리하세요.", 10, 22);
        LinearLayout bypass = card();
        bypassSwitch = new Switch(this); bypassSwitch.setText("접속 우회 (실험)"); bypassSwitch.setTextColor(TEXT); bypassSwitch.setTextSize(16);
        bypassSwitch.setMinHeight(dp(48)); bypassSwitch.setChecked(store.prefs.getBoolean("bypass", false)); bypass.addView(bypassSwitch);
        addHint(bypass, Build.VERSION.SDK_INT >= 29
                ? "아래 우회 사이트에만 연결 분할을 적용해요.\n해외 IP로 바뀌지 않으며 통신망에 따라 효과가 달라요. 보호를 끈 상태에서 모드를 변경하세요."
                : "접속 우회는 Android 10 이상에서 지원해요. 이 기기에서는 DNS 광고 차단을 사용할 수 있어요.", 8, 0);
        bypassSwitch.setOnCheckedChangeListener((widget, checked) -> {
            boolean old = store.prefs.getBoolean("bypass", false);
            if (old == checked) return;
            boolean stopped = "OFF".equals(DnsVpnService.state) || "ERROR".equals(DnsVpnService.state);
            if (Build.VERSION.SDK_INT < 29 || !stopped) { widget.setChecked(old); toast("보호를 끈 뒤 변경해 주세요"); return; }
            store.prefs.edit().putBoolean("bypass", checked).apply();
        });
        add(body, bypass, 0, 14);
        LinearLayout sites = card(); sites.addView(text("우회할 사이트", 17, TEXT, true));
        addHint(sites, "등록한 도메인과 하위 도메인에만 적용해요.\n목록 변경은 새 연결부터 반영돼요. 나머지 사이트와 메신저는 연결을 분할하지 않아요.", 10, 12);
        for (String domain : store.bypassed()) {
            Button item = button(domain + "  ×", false); item.setTextSize(12); item.setAllCaps(false); add(sites, item, 4, 4, 48);
            item.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("우회 목록에서 삭제")
                    .setMessage(domain).setNegativeButton("취소", null).setPositiveButton("삭제", (d, w) -> { store.bypassDomain(domain, false); showPage("settings"); }).show());
        }
        Button addSite = button("+ 우회 사이트 추가", false); add(sites, addSite, 8, 0, 48);
        addSite.setOnClickListener(v -> addDomain(true)); add(body, sites, 0, 14);
        LinearLayout dns = card(); dns.addView(text("암호화 DNS", 17, TEXT, true));
        addHint(dns, (store.provider().equals("google") ? "Google Public DNS" : "Cloudflare 1.1.1.1") + " · DNS over HTTPS\n허용된 DNS 질문은 선택한 제공자에게 전송돼요.", 10, 12);
        Button choose = button("DNS 제공자 선택", false); add(dns, choose, 0, 0, 48); choose.setOnClickListener(v -> chooseProvider());
        add(body, dns, 0, 14);
        LinearLayout exceptions = card(); exceptions.addView(text("허용 도메인", 17, TEXT, true));
        addHint(exceptions, "사이트가 정상 작동하지 않을 때 추가하세요.\n하위 도메인에도 적용되며 바로 반영돼요.", 10, 12);
        for (String domain : store.allowed()) {
            Button item = button(domain + "  ×", false); item.setTextSize(12); item.setAllCaps(false); add(exceptions, item, 4, 4, 48);
            item.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("허용 목록에서 삭제")
                    .setMessage(domain).setNegativeButton("취소", null).setPositiveButton("삭제", (d, w) -> { store.allow(domain, false); showPage("settings"); }).show());
        }
        Button addDomain = button("+ 도메인 추가", false); add(exceptions, addDomain, 8, 0, 48); addDomain.setOnClickListener(v -> addDomain()); add(body, exceptions, 0, 14);
        LinearLayout privacy = card();
        Switch logSwitch = new Switch(this); logSwitch.setText("최근 차단 기록"); logSwitch.setTextColor(TEXT); logSwitch.setTextSize(15);
        logSwitch.setMinHeight(dp(48)); logSwitch.setChecked(store.recordLogs); privacy.addView(logSwitch);
        addHint(privacy, "최대 100건 · 앱 프로세스 종료 시 사라져요.\n끄면 현재 기록도 비웁니다.", 6, 0);
        logSwitch.setOnCheckedChangeListener((b, checked) -> store.logging(checked)); add(body, privacy, 0, 14);
        LinearLayout fallback = card();
        Switch fallbackSwitch = new Switch(this); fallbackSwitch.setText("암호화 DNS 실패 시 기본 DNS 사용"); fallbackSwitch.setTextColor(TEXT); fallbackSwitch.setTextSize(15);
        fallbackSwitch.setMinHeight(dp(48)); fallbackSwitch.setChecked(store.fallbackDns()); fallback.addView(fallbackSwitch);
        addHint(fallback, "호텔·카페 Wi-Fi 로그인처럼 암호화 DNS에 연결되지 않을 때 통신망의 DNS를 잠시 사용해요. 이때 DNS 질문은 암호화되지 않아요. 광고 차단은 그대로 적용돼요.", 6, 0);
        fallbackSwitch.setOnCheckedChangeListener((b, checked) -> store.prefs.edit().putBoolean("dns_fallback", checked).apply()); add(body, fallback, 0, 14);
        LinearLayout updates = card();
        Switch autoSwitch = new Switch(this); autoSwitch.setText("자동 업데이트"); autoSwitch.setTextColor(TEXT); autoSwitch.setTextSize(15);
        autoSwitch.setMinHeight(dp(48)); autoSwitch.setChecked(AppUpdater.autoEnabled(this)); updates.addView(autoSwitch);
        addHint(updates, "GitHub에서 새 릴리즈를 확인해 미리 받아 검증하고 알려드려요. 설치할 때는 안드로이드 확인이 한 번 필요해요.\n현재 버전 " + AppUpdater.installedVersion(this), 6, 12);
        autoSwitch.setOnCheckedChangeListener((b, checked) -> AppUpdater.setAutoEnabled(this, checked));
        Button checkNow = button("지금 업데이트 확인", false); add(updates, checkNow, 0, 0, 48); checkNow.setOnClickListener(v -> checkUpdate(true));
        add(body, updates, 0, 14);
        LinearLayout help = card(); help.addView(text("연결 도움말", 17, TEXT, true));
        addHint(help, "광고가 계속 보이면 브라우저의 보안 DNS와 기기의 ‘프라이빗 DNS’를 확인하세요. 캐시된 광고는 앱을 다시 열어야 사라질 수 있어요.\n\n화면을 껐을 때 보호가 중지되면 배터리 설정에서 NOK의 백그라운드 실행을 허용해 주세요.\n\n안드로이드의 ‘VPN 없는 연결 차단’ 옵션은 사용하지 마세요. DNS 전용 VPN이라 일반 인터넷 연결도 막힐 수 있어요.", 10, 12);
        Button androidSettings = button("앱 시스템 설정 열기", false); add(help, androidSettings, 0, 0, 48);
        androidSettings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        add(body, help, 0, 14);
        Button about = button("앱 정보 · 개인정보 · 오픈소스", false); add(body, about, 0, 0, 48); about.setOnClickListener(v -> about());
    }
    private void chooseProvider() {
        String[] options = {"Cloudflare 1.1.1.1", "Google Public DNS"};
        new AlertDialog.Builder(this).setTitle("DNS 제공자").setSingleChoiceItems(options, store.provider().equals("google") ? 1 : 0,
                (dialog, which) -> { store.prefs.edit().putString("provider", which == 1 ? "google" : "cloudflare").apply(); dialog.dismiss(); showPage("settings"); }).setNegativeButton("닫기", null).show();
    }
    private void addQuickSettingsTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            StatusBarManager manager = getSystemService(StatusBarManager.class);
            if (manager != null) {
                try {
                    manager.requestAddTileService(new ComponentName(this, ProtectionTileService.class),
                            "NOK 광고 차단", android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notification),
                            getMainExecutor(), result -> {
                                if (isFinishing() || isDestroyed()) return;
                                if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                                    ProtectionTileService.refresh(this); toast("빠른 설정에 추가했습니다");
                                } else if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                                    ProtectionTileService.refresh(this); toast("이미 빠른 설정에 추가되어 있습니다");
                                } else if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED) tileHelp();
                            });
                    return;
                } catch (RuntimeException ignored) { /* Fall back to the manufacturer's tile editor. */ }
            }
        }
        tileHelp();
    }
    private void tileHelp() {
        new AlertDialog.Builder(this).setTitle("빠른 설정에 NOK 추가")
                .setMessage("빠른 설정창을 펼치고 편집 → 항목 추가에서 ‘NOK 광고 차단’을 찾아 원하는 위치로 옮겨 주세요.\n\n추가한 타일을 누르면 보호를 켜고 끌 수 있어요. 처음 켤 때에는 안드로이드의 VPN 연결 승인이 필요해요.")
                .setPositiveButton("확인", null).show();
    }
    private void addDomain() {
        addDomain(false);
    }
    private void addDomain(boolean bypass) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint("예: example.com"); input.setTextSize(16);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout container = column(); container.setPadding(dp(24), dp(8), dp(24), 0); container.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(bypass ? "우회할 사이트 도메인" : "허용할 도메인").setView(container).setNegativeButton("취소", null).setPositiveButton("추가", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String domain = DomainRules.normalize(input.getText().toString());
            if (domain == null) { input.setError("주소 전체가 아닌 도메인만 입력하세요"); return; }
            if (bypass) store.bypassDomain(domain, true); else store.allow(domain, true);
            dialog.dismiss(); showPage("settings");
        })); dialog.show();
    }
    private void about() {
        new AlertDialog.Builder(this).setTitle("NOK 광고 차단 " + AppUpdater.installedVersion(this))
                .setMessage("DNS 광고 차단과 실험적 HTTPS 접속 우회를 제공하는 독립 앱입니다. AdGuard·유니콘과는 별개 프로젝트입니다.\n\n• 광고·추적 도메인은 기기에서 판정합니다.\n• 허용된 DNS 질문은 선택한 Cloudflare 또는 Google에 HTTPS로 전송됩니다. 연결할 수 없으면 설정에 따라 통신망의 DNS를 잠시 사용합니다.\n• DNS 전용 모드는 DNS 요청만 처리합니다.\n• 접속 우회를 켜면 전체 TCP·UDP 연결을 기기 안의 중계기로 처리합니다. 해외 서버나 해외 IP를 제공하지 않습니다. HTTPS 인증서 검사와 암호화는 브라우저에서 그대로 수행합니다.\n• 차단 목록 갱신과 앱 업데이트 확인 시 GitHub에 연결합니다.\n• 분석 SDK, 계정, 원격 로그 수집이 없습니다.\n\n차단 목록: AdAway default blocklist\nKicelo, Dominik Schuermann 및 기여자 · CC BY 3.0\nhttps://github.com/AdAway/adaway.github.io\nhttps://creativecommons.org/licenses/by/3.0/\n\n접속 중계 엔진: hev-socks5-tunnel 2.19.3 · MIT\nhttps://github.com/heiher/hev-socks5-tunnel")
                .setNeutralButton("오픈소스 라이선스", (d, w) -> showLicenses()).setPositiveButton("확인", null).show();
    }
    private void showLicenses() {
        try (java.io.InputStream in = getAssets().open("licenses/hev-third-party.txt")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            TextView content = text(new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8), 11, TEXT, false);
            content.setPadding(dp(24), dp(16), dp(24), dp(16)); content.setTextIsSelectable(true);
            ScrollView view = new ScrollView(this); view.addView(content);
            new AlertDialog.Builder(this).setTitle("오픈소스 라이선스").setView(view).setPositiveButton("확인", null).show();
        } catch (java.io.IOException e) { toast("라이선스 파일을 읽지 못했습니다"); }
    }
    private void toggle() {
        if ("ON".equals(DnsVpnService.state) || "STARTING".equals(DnsVpnService.state)) {
            DnsVpnService.requestStop(this); refreshValues(); return;
        }
        if ("STOPPING".equals(DnsVpnService.state)) return;
        prepareVpn();
    }
    private void prepareVpn() {
        try {
            Intent permission = VpnService.prepare(this);
            if (permission != null) startActivityForResult(permission, 40); else launchService();
        } catch (Exception e) { toast("이 기기에서 VPN 승인을 열지 못했습니다"); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 40) { if (result == RESULT_OK) launchService(); else toast("VPN 연결이 승인되지 않았습니다"); }
    }
    private void launchService() {
        try {
            if ("STOPPING".equals(DnsVpnService.state)) return;
            DnsVpnService.requestStart(this);
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
        } catch (Exception e) { toast("서비스를 시작하지 못했습니다: " + e.getClass().getSimpleName()); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 41 && (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED))
            toast("알림 없이도 보호는 동작해요. 끄기는 앱이나 빠른 설정 타일에서 할 수 있어요.");
    }
    private void checkUpdate(boolean manual) {
        if (manual) toast("업데이트를 확인하고 있어요");
        io.execute(() -> {
            String message;
            try {
                AppUpdater.Ready update = AppUpdater.checkAndDownload(this);
                message = update == null ? "최신 버전이에요" : "NOK " + update.version + " 업데이트가 준비됐어요";
            } catch (Exception e) { message = "업데이트 확인 실패: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
            String result = message;
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (manual) toast(result);
                if (page.equals("home")) showPage("home");
                else if (manual && AppUpdater.ready(this) != null) showPage("home");
            });
        });
    }
    private void installUpdate() {
        AppUpdater.Ready update = AppUpdater.ready(this);
        if (update == null) { showPage(page); return; }
        if (!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this).setTitle("설치 허용이 필요해요")
                    .setMessage("안드로이드 설정에서 NOK의 ‘이 출처 허용’을 켠 뒤 돌아와 다시 설치를 눌러 주세요.")
                    .setNegativeButton("취소", null).setPositiveButton("설정 열기", (d, w) -> {
                        try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); }
                        catch (RuntimeException e) { toast("설정을 열지 못했습니다"); }
                    }).show();
            return;
        }
        toast("설치 파일을 확인하고 있어요");
        io.execute(() -> {
            try { AppUpdater.install(this, update); }
            catch (Exception e) {
                AppUpdater.clear(this);
                handler.post(() -> { if (!isFinishing() && !isDestroyed()) { toast("업데이트를 설치할 수 없어요. 다시 확인해 주세요."); showPage(page); } });
            }
        });
    }
    private void updateList() {
        if (!store.updating.compareAndSet(false, true)) return;
        refreshValues();
        io.execute(() -> {
            String message;
            try { int count = store.updateList(); message = "차단 목록 갱신 완료 · " + NumberFormat.getIntegerInstance().format(count) + "개"; }
            catch (Exception e) { store.updateError = "갱신 실패 · 기존 목록을 유지합니다"; message = store.updateError; }
            finally { store.updating.set(false); }
            String result = message;
            handler.post(() -> { if (!isFinishing() && !isDestroyed()) { refreshValues(); toast(result); } });
        });
    }
    private void refreshValues() {
        if (bypassSwitch != null) bypassSwitch.setEnabled(Build.VERSION.SDK_INT >= 29
                && ("OFF".equals(DnsVpnService.state) || "ERROR".equals(DnsVpnService.state)));
        if (page.equals("logs")) {
            if (!lastLogKey.equals(logKey()) && scroll.getScrollY() == 0) { body.removeAllViews(); logs(); } return;
        }
        if (!page.equals("home")) return;
        String state = DnsVpnService.state; boolean on = state.equals("ON"), busy = state.equals("STARTING"), stopping = state.equals("STOPPING");
        statusTitle.setText(on ? DnsVpnService.bypassActive ? "광고 차단 · 접속 우회" : "광고 차단 중" : busy ? "보호를 시작하고 있어요" : stopping ? "보호를 종료하고 있어요" : state.equals("ERROR") ? "연결을 확인해 주세요" : "보호가 꺼져 있어요");
        statusTitle.setTextColor(on ? GREEN : TEXT);
        statusDetail.setText(on ? DnsVpnService.bypassActive ? "HTTPS 접속 우회가 함께 켜져 있어요" : "DNS 광고·추적 차단이 켜져 있어요" : busy ? "취소 버튼으로 연결을 중단할 수 있어요" : stopping ? "VPN 연결을 닫고 원래 연결로 돌아갑니다" : state.equals("ERROR") ? DnsVpnService.lastError : store.prefs.getBoolean("bypass", false) ? "광고 차단과 접속 우회를 함께 시작해요" : "설정에서 접속 우회도 선택할 수 있어요");
        power.setText(on ? "보호 끄기" : busy ? "연결 취소" : stopping ? "연결 종료 중…" : "보호 켜기"); power.setEnabled(!stopping); power.setAlpha(stopping ? .5f : 1f);
        shield.on = on; shield.invalidate();
        long[] stats = store.stats(); NumberFormat n = NumberFormat.getIntegerInstance(Locale.KOREA);
        queryCount.setText(n.format(stats[0])); blockCount.setText(n.format(stats[1])); errorCount.setText(n.format(stats[2]));
        listCount.setText(n.format(store.rules.size()) + "개 도메인 · AdAway");
        long updated = store.updatedAt();
        updateLabel.setText(!store.updateError.isEmpty() ? store.updateError : updated == 0 ? "기본 차단 목록 사용 중" : "최근 갱신  " + new SimpleDateFormat("MM.dd HH:mm", Locale.KOREA).format(new Date(updated)));
        updateButton.setEnabled(!store.updating.get()); updateButton.setText(store.updating.get() ? "목록 갱신 중…" : "차단 목록 갱신");
    }
    private TextView text(String value, float size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setFontFeatureSettings("tnum"); v.setLineSpacing(dp(3), 1f);
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return v;
    }
    private Button button(String title, boolean filled) {
        Button b = new Button(this); b.setText(title); b.setAllCaps(false); b.setTextSize(14);
        b.setTextColor(filled ? BG : TEXT); b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x2269e0b7), shape(filled ? GREEN : LINE, 14), null));
        b.setPadding(dp(12), 0, dp(12), 0); b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48)); return b;
    }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); return v; }
    private LinearLayout card() { LinearLayout v = column(); v.setPadding(dp(20), dp(20), dp(20), dp(20)); v.setBackground(shape(CARD, 20)); return v; }
    private void add(LinearLayout parent, View child, int top, int bottom) { add(parent, child, top, bottom, -2); }
    private void add(LinearLayout parent, View child, int top, int bottom, int height) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, height < 0 ? height : dp(height)); p.setMargins(0, dp(top), 0, dp(bottom)); parent.addView(child, p);
    }
    private void addHint(LinearLayout parent, String value, int top, int bottom) { add(parent, text(value, 12, MUTED, false), top, bottom); }
    private int dp(float size) { return (int) (getResources().getDisplayMetrics().density * size + .5f); }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private final class ShieldView extends View {
        boolean on;
        final Paint paint = new Paint(3);
        ShieldView() { super(MainActivity.this); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); canvas.save(); canvas.scale(getWidth() / 132f, getHeight() / 132f);
            paint.setStyle(Paint.Style.FILL); paint.setColor(on ? 0xff203e37 : 0xff253237); canvas.drawCircle(66, 62, 55, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setColor(on ? 0xff365b4d : LINE); paint.setStrokeWidth(1); canvas.drawCircle(66, 62, 54, paint);
            Path path = new Path(); path.moveTo(66, 25); path.lineTo(94, 36); path.lineTo(94, 59);
            path.cubicTo(94, 79, 82, 93, 66, 101); path.cubicTo(50, 93, 38, 79, 38, 59); path.lineTo(38, 36); path.close();
            paint.setStyle(Paint.Style.FILL); paint.setColor(on ? GREEN : 0xff778e8d); canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(5); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); paint.setColor(BG);
            path.reset(); path.moveTo(52, 62); path.lineTo(62, 72); path.lineTo(81, 50); canvas.drawPath(path, paint); canvas.restore();
        }
    }
}
