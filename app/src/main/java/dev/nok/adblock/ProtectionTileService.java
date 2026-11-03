package dev.nok.adblock;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.VpnService;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** User-controlled Quick Settings switch. The VPN service remains the source of truth. */
public final class ProtectionTileService extends TileService {
    public static void refresh(Context context) {
        try {
            requestListeningState(context, new ComponentName(context, ProtectionTileService.class));
        } catch (RuntimeException ignored) { /* Tile may not have been added, or System UI is restarting. */ }
    }

    @Override public void onTileAdded() { super.onTileAdded(); update(); }
    @Override public void onStartListening() { super.onStartListening(); update(); }

    @Override public void onClick() {
        super.onClick();
        if (isLocked()) unlockAndRun(this::toggle); else toggle();
    }

    private void toggle() {
        String state = DnsVpnService.state;
        if ("STOPPING".equals(state)) { update(); return; }
        if ("ON".equals(state) || "STARTING".equals(state)) {
            DnsVpnService.requestStop(this);
            update();
            return;
        }
        try {
            if (VpnService.prepare(this) != null) { openConsent(); return; }
            DnsVpnService.requestStart(this);
        } catch (RuntimeException e) {
            // If the OS requires a visible activity, retry from the permission activity.
            openConsent();
        }
    }

    private void openConsent() {
        Intent intent = new Intent(this, TileConsentActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pending = PendingIntent.getActivity(this, 72, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            startActivityAndCollapse(pending);
        } else startActivityAndCollapse(intent);
    }

    private void update() {
        Tile tile = getQsTile();
        if (tile == null) return;
        String state = DnsVpnService.state;
        boolean on = "ON".equals(state), busy = "STARTING".equals(state), stopping = "STOPPING".equals(state);
        String detail = on ? DnsVpnService.bypassActive ? "켜짐 · 접속 우회" : "켜짐" : busy ? "연결 중 · 눌러서 취소" : stopping ? "연결 종료 중" : "ERROR".equals(state) ? "연결 오류" : "꺼짐";
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_notification));
        tile.setLabel("NOK 광고 차단");
        tile.setState(stopping ? Tile.STATE_UNAVAILABLE : on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setContentDescription("NOK 광고 차단, " + detail);
        if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(detail);
        if (Build.VERSION.SDK_INT >= 30) tile.setStateDescription(detail);
        tile.updateTile();
    }
}
