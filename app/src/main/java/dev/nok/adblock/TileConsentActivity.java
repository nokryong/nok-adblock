package dev.nok.adblock;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/** Private activity: only our tile can enter the VPN-consent/start flow. */
public final class TileConsentActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) return; // The system restores any pending permission result.
        try {
            if ("ON".equals(DnsVpnService.state) || "STARTING".equals(DnsVpnService.state) || "STOPPING".equals(DnsVpnService.state)) { finish(); return; }
            Intent permission = VpnService.prepare(this);
            if (permission != null) startActivityForResult(permission, 40); else startProtection();
        } catch (RuntimeException e) { error(); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 40) {
            if (result == RESULT_OK) startProtection();
            else { ProtectionTileService.refresh(this); finish(); }
        }
    }
    private void startProtection() {
        try {
            // Recheck approval if another VPN became selected while the prompt was visible.
            if (VpnService.prepare(this) != null || "STOPPING".equals(DnsVpnService.state)) { finish(); return; }
            DnsVpnService.requestStart(this);
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 41);
            } else finish();
        } catch (RuntimeException e) { error(); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 41) finish(); // Notification denial does not undo approved VPN protection.
    }
    private void error() {
        Toast.makeText(this, "차단을 시작하지 못했습니다. NOK 앱에서 연결을 확인해 주세요.", Toast.LENGTH_LONG).show();
        ProtectionTileService.refresh(this); finish();
    }
}
