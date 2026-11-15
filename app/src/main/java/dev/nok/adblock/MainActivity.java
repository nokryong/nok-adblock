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
    private static final Set<String> SECOND_LEVEL = new HashSet<>(Arrays.asList("co", "com", "net", "org", "ac", "go", "or", "ne", "gov", "edu"));
    private final Set<String> openLogs = new HashSet<>();
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
        tab("home", getString(R.string.main_tab_protection)); tab("logs", getString(R.string.main_tab_logs)); tab("settings", getString(R.string.main_tab_settings));
        if (page.equals("logs")) logs(); else if (page.equals("settings")) settings(); else home();
        scroll.post(() -> scroll.scrollTo(0, keep)); refreshValues();
    }
    private void tab(String name, String title) {
        Button button = button(title, name.equals(page)); button.setTextSize(13);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1); p.setMargins(dp(4), 0, dp(4), 0);
        tabs.addView(button, p); button.setOnClickListener(v -> showPage(name));
    }
    private void home() {
        body.addView(text(getString(R.string.main_home_title), 29, TEXT, true));
        addHint(body, getString(R.string.main_home_hint), 10, 22);
        AppUpdater.Ready update = AppUpdater.ready(this);
        if (update != null) {
            LinearLayout card = card(); card.addView(text(getString(R.string.main_update_ready, update.version), 16, GREEN, true));
            addHint(card, getString(R.string.main_update_verified), 8, 14);
            Button install = button(getString(R.string.main_install_update), true); add(card, install, 0, 0, 48);
            install.setOnClickListener(v -> installUpdate()); add(body, card, 0, 16);
        }
        LinearLayout protection = card(); protection.setGravity(Gravity.CENTER_HORIZONTAL);
        shield = new ShieldView(); shield.setContentDescription(getString(R.string.main_blocking_status));
        protection.addView(shield, new LinearLayout.LayoutParams(dp(132), dp(132)));
        statusTitle = text(getString(R.string.main_blocking_off), 21, TEXT, true); statusTitle.setGravity(Gravity.CENTER); protection.addView(statusTitle);
        statusDetail = text(getString(R.string.main_start_hint), 13, MUTED, false); statusDetail.setGravity(Gravity.CENTER);
        add(protection, statusDetail, 10, 20);
        power = button(getString(R.string.main_enable_protection), true); add(protection, power, 0, 0, 54); power.setOnClickListener(v -> toggle());
        add(body, protection, 0, 20);
        Button addTile = button(getString(R.string.main_add_tile), false); add(body, addTile, 0, 20, 48);
        addTile.setOnClickListener(v -> addQuickSettingsTile());
        TextView today = text(getString(R.string.main_today), 16, TEXT, true); add(body, today, 0, 12);
        LinearLayout stats = row();
        queryCount = stat(stats, getString(R.string.main_dns_queries), false); blockCount = stat(stats, getString(R.string.main_ads_blocked), true); errorCount = stat(stats, getString(R.string.main_dns_errors), false);
        add(body, stats, 0, 20);
        LinearLayout filter = card();
        filter.addView(text(getString(R.string.main_block_filter), 16, TEXT, true));
        listCount = text("", 14, GREEN, true); add(filter, listCount, 12, 6);
        updateLabel = text("", 12, MUTED, false); filter.addView(updateLabel);
        updateButton = button(getString(R.string.main_update_list), false); add(filter, updateButton, 16, 0, 48);
        updateButton.setOnClickListener(v -> updateList()); add(body, filter, 0, 16);
        addHint(body, getString(R.string.main_privacy_hint), 0, 8);
        addHint(body, getString(R.string.main_limits), 0, 0);
    }
    private TextView stat(LinearLayout parent, String label, boolean accent) {
        LinearLayout c = column(); c.setPadding(dp(12), dp(16), dp(8), dp(16)); c.setBackground(shape(CARD, 16));
        TextView value = text("0", 23, accent ? GREEN : TEXT, true); value.setMaxLines(1); c.addView(value);
        TextView caption = text(label, 10, MUTED, false); add(c, caption, 7, 0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); p.setMargins(0, 0, dp(6), 0); parent.addView(c, p); return value;
    }
    private void logs() {
        body.addView(text(getString(R.string.main_tab_logs), 28, TEXT, true));
        addHint(body, getString(R.string.main_logs_hint), 10, 22);
        lastLogKey = logKey();
        List<AppStore.LogEntry> entries = store.logs();
        if (entries.isEmpty()) {
            LinearLayout empty = card(); empty.addView(text(store.recordLogs ? getString(R.string.main_logs_empty) : getString(R.string.main_logs_disabled), 18, TEXT, true));
            addHint(empty, store.recordLogs ? getString(R.string.main_logs_empty_hint) : getString(R.string.main_logs_disabled_hint), 12, 0); body.addView(empty);
        }
        SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        LinkedHashMap<String, LinkedHashMap<String, List<AppStore.LogEntry>>> groups = new LinkedHashMap<>();
        for (AppStore.LogEntry entry : entries)
            groups.computeIfAbsent(baseDomain(entry.domain), k -> new LinkedHashMap<>()).computeIfAbsent(entry.domain, k -> new ArrayList<>()).add(entry);
        for (Map.Entry<String, LinkedHashMap<String, List<AppStore.LogEntry>>> group : groups.entrySet()) {
            LinearLayout item = card();
            LinearLayout hosts = accordion(item, group.getKey(), "b:" + group.getKey(), 14);
            for (Map.Entry<String, List<AppStore.LogEntry>> host : group.getValue().entrySet()) {
                LinearLayout times = accordion(hosts, host.getKey(), "h:" + host.getKey(), 13);
                for (AppStore.LogEntry entry : host.getValue()) {
                    TextView stamp = text(time.format(new Date(entry.time)), 12, MUTED, false); stamp.setPadding(0, dp(8), 0, dp(8));
                    stamp.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(getString(R.string.main_allow_title))
                            .setMessage(entry.domain + getString(R.string.main_allow_message))
                            .setNegativeButton(getString(R.string.main_cancel), null).setPositiveButton(getString(R.string.main_allow), (d, w) -> { store.allow(entry.domain, true); toast(getString(R.string.main_allowed_toast)); }).show());
                    times.addView(stamp);
                }
            }
            add(body, item, 0, 10);
        }
    }
    private LinearLayout accordion(LinearLayout parent, String title, String key, float size) {
        LinearLayout head = row(); head.setGravity(Gravity.CENTER_VERTICAL); head.setMinimumHeight(dp(40));
        TextView label = text(title, size, TEXT, true); label.setBreakStrategy(0);
        head.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView arrow = new ImageView(this); arrow.setImageResource(R.drawable.ic_arrow_down);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(20), dp(20)); p.setMargins(dp(12), 0, 0, 0); head.addView(arrow, p);
        LinearLayout content = column(); content.setPadding(dp(12), 0, 0, 0);
        boolean open = openLogs.contains(key);
        content.setVisibility(open ? View.VISIBLE : View.GONE); arrow.setRotation(open ? 180 : 0);
        head.setOnClickListener(v -> {
            boolean show = !openLogs.remove(key); if (show) openLogs.add(key);
            content.setVisibility(show ? View.VISIBLE : View.GONE); arrow.setRotation(show ? 180 : 0);
        });
        parent.addView(head); parent.addView(content); return content;
    }
    private static String baseDomain(String domain) {
        String[] labels = domain.split("\\.");
        if (labels.length < 3) return domain;
        int keep = labels[labels.length - 1].length() == 2 && SECOND_LEVEL.contains(labels[labels.length - 2]) ? 3 : 2;
        return String.join(".", Arrays.copyOfRange(labels, labels.length - keep, labels.length));
    }
    private String logKey() {
        List<AppStore.LogEntry> logs = store.logs(); return store.recordLogs + ":" + logs.size() + ":" + (logs.isEmpty() ? "" : logs.get(0).time);
    }
    private void settings() {
        body.addView(text(getString(R.string.main_settings_title), 28, TEXT, true));
        addHint(body, getString(R.string.main_settings_hint), 10, 22);
        LinearLayout bypass = card();
        bypassSwitch = new Switch(this); bypassSwitch.setText(getString(R.string.main_bypass)); bypassSwitch.setTextColor(TEXT); bypassSwitch.setTextSize(16);
        bypassSwitch.setMinHeight(dp(48)); bypassSwitch.setChecked(store.prefs.getBoolean("bypass", false)); bypass.addView(bypassSwitch);
        addHint(bypass, Build.VERSION.SDK_INT >= 29
                ? getString(R.string.main_bypass_hint)
                : getString(R.string.main_bypass_unsupported), 8, 0);
        bypassSwitch.setOnCheckedChangeListener((widget, checked) -> {
            boolean old = store.prefs.getBoolean("bypass", false);
            if (old == checked) return;
            boolean stopped = "OFF".equals(DnsVpnService.state) || "ERROR".equals(DnsVpnService.state);
            if (Build.VERSION.SDK_INT < 29 || !stopped) { widget.setChecked(old); toast(getString(R.string.main_stop_before_change)); return; }
            store.prefs.edit().putBoolean("bypass", checked).apply();
        });
        add(body, bypass, 0, 14);
        LinearLayout sites = card(); sites.addView(text(getString(R.string.main_bypass_sites), 17, TEXT, true));
        addHint(sites, getString(R.string.main_bypass_sites_hint), 10, 12);
        for (String domain : store.bypassed()) {
            Button item = button(domain + "  ×", false); item.setTextSize(12); item.setAllCaps(false); add(sites, item, 4, 4, 48);
            item.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(getString(R.string.main_remove_bypass))
                    .setMessage(domain).setNegativeButton(getString(R.string.main_cancel), null).setPositiveButton(getString(R.string.main_delete), (d, w) -> { store.bypassDomain(domain, false); showPage("settings"); }).show());
        }
        Button addSite = button(getString(R.string.main_add_bypass), false); add(sites, addSite, 8, 0, 48);
        addSite.setOnClickListener(v -> addDomain(true)); add(body, sites, 0, 14);
        LinearLayout dns = card(); dns.addView(text(getString(R.string.main_encrypted_dns), 17, TEXT, true));
        addHint(dns, (store.provider().equals("google") ? "Google Public DNS" : "Cloudflare 1.1.1.1") + getString(R.string.main_dns_provider_hint), 10, 12);
        Button choose = button(getString(R.string.main_choose_dns), false); add(dns, choose, 0, 0, 48); choose.setOnClickListener(v -> chooseProvider());
        add(body, dns, 0, 14);
        LinearLayout exceptions = card(); exceptions.addView(text(getString(R.string.main_allowed_domains), 17, TEXT, true));
        addHint(exceptions, getString(R.string.main_allowed_hint), 10, 12);
        for (String domain : store.allowed()) {
            Button item = button(domain + "  ×", false); item.setTextSize(12); item.setAllCaps(false); add(exceptions, item, 4, 4, 48);
            item.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(getString(R.string.main_remove_allowed))
                    .setMessage(domain).setNegativeButton(getString(R.string.main_cancel), null).setPositiveButton(getString(R.string.main_delete), (d, w) -> { store.allow(domain, false); showPage("settings"); }).show());
        }
        Button addDomain = button(getString(R.string.main_add_domain), false); add(exceptions, addDomain, 8, 0, 48); addDomain.setOnClickListener(v -> addDomain()); add(body, exceptions, 0, 14);
        LinearLayout privacy = card();
        Switch logSwitch = new Switch(this); logSwitch.setText(getString(R.string.main_recent_logs)); logSwitch.setTextColor(TEXT); logSwitch.setTextSize(15);
        logSwitch.setMinHeight(dp(48)); logSwitch.setChecked(store.recordLogs); privacy.addView(logSwitch);
        addHint(privacy, getString(R.string.main_recent_logs_hint), 6, 0);
        logSwitch.setOnCheckedChangeListener((b, checked) -> store.logging(checked)); add(body, privacy, 0, 14);
        LinearLayout fallback = card();
        Switch fallbackSwitch = new Switch(this); fallbackSwitch.setText(getString(R.string.main_dns_fallback)); fallbackSwitch.setTextColor(TEXT); fallbackSwitch.setTextSize(15);
        fallbackSwitch.setMinHeight(dp(48)); fallbackSwitch.setChecked(store.fallbackDns()); fallback.addView(fallbackSwitch);
        addHint(fallback, getString(R.string.main_dns_fallback_hint), 6, 0);
        fallbackSwitch.setOnCheckedChangeListener((b, checked) -> store.prefs.edit().putBoolean("dns_fallback", checked).apply()); add(body, fallback, 0, 14);
        LinearLayout updates = card();
        Switch autoSwitch = new Switch(this); autoSwitch.setText(getString(R.string.main_auto_updates)); autoSwitch.setTextColor(TEXT); autoSwitch.setTextSize(15);
        autoSwitch.setMinHeight(dp(48)); autoSwitch.setChecked(AppUpdater.autoEnabled(this)); updates.addView(autoSwitch);
        addHint(updates, getString(R.string.main_auto_updates_description, AppUpdater.installedVersion(this)), 6, 12);
        autoSwitch.setOnCheckedChangeListener((b, checked) -> AppUpdater.setAutoEnabled(this, checked));
        Button checkNow = button(getString(R.string.main_check_updates), false); add(updates, checkNow, 0, 0, 48); checkNow.setOnClickListener(v -> checkUpdate(true));
        add(body, updates, 0, 14);
        LinearLayout help = card(); help.addView(text(getString(R.string.main_connection_help), 17, TEXT, true));
        addHint(help, getString(R.string.main_connection_help_body), 10, 12);
        Button androidSettings = button(getString(R.string.main_system_settings), false); add(help, androidSettings, 0, 0, 48);
        androidSettings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        add(body, help, 0, 14);
        Button about = button(getString(R.string.main_about), false); add(body, about, 0, 0, 48); about.setOnClickListener(v -> about());
    }
    private void chooseProvider() {
        String[] options = {"Cloudflare 1.1.1.1", "Google Public DNS"};
        new AlertDialog.Builder(this).setTitle(getString(R.string.main_dns_provider)).setSingleChoiceItems(options, store.provider().equals("google") ? 1 : 0,
                (dialog, which) -> { store.prefs.edit().putString("provider", which == 1 ? "google" : "cloudflare").apply(); dialog.dismiss(); showPage("settings"); }).setNegativeButton(getString(R.string.main_close), null).show();
    }
    private void addQuickSettingsTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            StatusBarManager manager = getSystemService(StatusBarManager.class);
            if (manager != null) {
                try {
                    manager.requestAddTileService(new ComponentName(this, ProtectionTileService.class),
                            getString(R.string.main_app_name), android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notification),
                            getMainExecutor(), result -> {
                                if (isFinishing() || isDestroyed()) return;
                                if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                                    ProtectionTileService.refresh(this); toast(getString(R.string.main_tile_added));
                                } else if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                                    ProtectionTileService.refresh(this); toast(getString(R.string.main_tile_already_added));
                                } else if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED) tileHelp();
                            });
                    return;
                } catch (RuntimeException ignored) { /* Fall back to the manufacturer's tile editor. */ }
            }
        }
        tileHelp();
    }
    private void tileHelp() {
        new AlertDialog.Builder(this).setTitle(getString(R.string.main_tile_help_title))
                .setMessage(getString(R.string.main_tile_help_body))
                .setPositiveButton(getString(R.string.main_ok), null).show();
    }
    private void addDomain() {
        addDomain(false);
    }
    private void addDomain(boolean bypass) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint(getString(R.string.main_domain_example)); input.setTextSize(16);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout container = column(); container.setPadding(dp(24), dp(8), dp(24), 0); container.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(bypass ? getString(R.string.main_bypass_domain_title) : getString(R.string.main_allow_domain_title)).setView(container).setNegativeButton(getString(R.string.main_cancel), null).setPositiveButton(getString(R.string.main_add), null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String domain = DomainRules.normalize(input.getText().toString());
            if (domain == null) { input.setError(getString(R.string.main_invalid_domain)); return; }
            if (bypass) store.bypassDomain(domain, true); else store.allow(domain, true);
            dialog.dismiss(); showPage("settings");
        })); dialog.show();
    }
    private void about() {
        new AlertDialog.Builder(this).setTitle(getString(R.string.main_about_title, AppUpdater.installedVersion(this)))
                .setMessage(getString(R.string.main_about_body))
                .setNeutralButton(getString(R.string.main_licenses), (d, w) -> showLicenses()).setPositiveButton(getString(R.string.main_ok), null).show();
    }
    private void showLicenses() {
        try (java.io.InputStream in = getAssets().open("licenses/hev-third-party.txt")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            TextView content = text(new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8), 11, TEXT, false);
            content.setPadding(dp(24), dp(16), dp(24), dp(16)); content.setTextIsSelectable(true);
            ScrollView view = new ScrollView(this); view.addView(content);
            new AlertDialog.Builder(this).setTitle(getString(R.string.main_licenses)).setView(view).setPositiveButton(getString(R.string.main_ok), null).show();
        } catch (java.io.IOException e) { toast(getString(R.string.main_license_error)); }
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
        } catch (Exception e) { toast(getString(R.string.main_vpn_permission_error)); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 40) { if (result == RESULT_OK) launchService(); else toast(getString(R.string.main_vpn_permission_denied)); }
    }
    private void launchService() {
        try {
            if ("STOPPING".equals(DnsVpnService.state)) return;
            DnsVpnService.requestStart(this);
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
        } catch (Exception e) { toast(getString(R.string.main_service_error_prefix) + e.getClass().getSimpleName()); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 41 && (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED))
            toast(getString(R.string.main_notifications_denied));
    }
    private void checkUpdate(boolean manual) {
        if (manual) toast(getString(R.string.main_checking_update));
        io.execute(() -> {
            String message;
            try {
                AppUpdater.Ready update = AppUpdater.checkAndDownload(this);
                message = update == null ? getString(R.string.main_up_to_date) : getString(R.string.main_update_downloaded, update.version);
            } catch (Exception e) { message = getString(R.string.main_update_error_prefix) + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
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
            new AlertDialog.Builder(this).setTitle(getString(R.string.main_install_permission))
                    .setMessage(getString(R.string.main_install_permission_body))
                    .setNegativeButton(getString(R.string.main_cancel), null).setPositiveButton(getString(R.string.main_open_settings), (d, w) -> {
                        try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); }
                        catch (RuntimeException e) { toast(getString(R.string.main_settings_error)); }
                    }).show();
            return;
        }
        toast(getString(R.string.main_verifying_apk));
        io.execute(() -> {
            try { AppUpdater.install(this, update); }
            catch (Exception e) {
                AppUpdater.clear(this);
                handler.post(() -> { if (!isFinishing() && !isDestroyed()) { toast(getString(R.string.main_install_error)); showPage(page); } });
            }
        });
    }
    private void updateList() {
        if (!store.updating.compareAndSet(false, true)) return;
        refreshValues();
        io.execute(() -> {
            String message;
            try { int count = store.updateList(); message = getString(R.string.main_list_updated, NumberFormat.getIntegerInstance().format(count)); }
            catch (Exception e) { store.updateError = getString(R.string.main_list_update_failed); message = store.updateError; }
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
        statusTitle.setText(on ? DnsVpnService.bypassActive ? getString(R.string.main_status_bypass) : getString(R.string.main_status_on) : busy ? getString(R.string.main_status_starting) : stopping ? getString(R.string.main_status_stopping) : state.equals("ERROR") ? getString(R.string.main_status_error) : getString(R.string.main_status_off));
        statusTitle.setTextColor(on ? GREEN : TEXT);
        statusDetail.setText(on ? DnsVpnService.bypassActive ? getString(R.string.main_detail_bypass) : getString(R.string.main_detail_on) : busy ? getString(R.string.main_detail_starting) : stopping ? getString(R.string.main_detail_stopping) : state.equals("ERROR") ? DnsVpnService.lastError : store.prefs.getBoolean("bypass", false) ? getString(R.string.main_detail_bypass_ready) : getString(R.string.main_detail_off));
        power.setText(on ? getString(R.string.main_disable_protection) : busy ? getString(R.string.main_cancel_connection) : stopping ? getString(R.string.main_disconnecting) : getString(R.string.main_enable_protection)); power.setEnabled(!stopping); power.setAlpha(stopping ? .5f : 1f);
        shield.on = on; shield.invalidate();
        long[] stats = store.stats(); NumberFormat n = NumberFormat.getIntegerInstance(Locale.getDefault());
        queryCount.setText(n.format(stats[0])); blockCount.setText(n.format(stats[1])); errorCount.setText(n.format(stats[2]));
        listCount.setText(getString(R.string.main_list_count, n.format(store.rules.size())));
        long updated = store.updatedAt();
        updateLabel.setText(!store.updateError.isEmpty() ? store.updateError : updated == 0 ? getString(R.string.main_default_list) : getString(R.string.main_last_updated_prefix) + new SimpleDateFormat("MM.dd HH:mm", Locale.getDefault()).format(new Date(updated)));
        updateButton.setEnabled(!store.updating.get()); updateButton.setText(store.updating.get() ? getString(R.string.main_updating_list) : getString(R.string.main_update_list));
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
