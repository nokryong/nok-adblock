package dev.nok.adblock;

import android.content.*;
import android.content.pm.PackageInstaller;
import android.net.VpnService;
import android.widget.Toast;

/** Installer status, plus restoring protection that the package update interrupted. */
public final class UpdateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            AppUpdater.clear(context); AppUpdater.schedule(context);
            try {
                if (context.getSharedPreferences("nok", Context.MODE_PRIVATE).getBoolean("wanted", false)
                        && VpnService.prepare(context) == null) DnsVpnService.requestStart(context);
            } catch (RuntimeException ignored) { /* The user can restart protection from the app. */ }
            return;
        }
        if (!AppUpdater.INSTALL_STATUS.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) return;
            try { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (RuntimeException e) { Toast.makeText(context, "설치 화면을 열지 못했습니다", Toast.LENGTH_LONG).show(); }
        } else if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_FAILURE_ABORTED) {
            Toast.makeText(context, "업데이트를 설치하지 못했습니다", Toast.LENGTH_LONG).show();
        }
    }
}
