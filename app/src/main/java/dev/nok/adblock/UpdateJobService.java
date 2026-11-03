package dev.nok.adblock;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Periodic release check. Prepares the update in the background; installing still needs the user. */
public final class UpdateJobService extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        if (!AppUpdater.autoEnabled(this)) return false;
        new Thread(() -> {
            boolean retry = false;
            try {
                String announced = getSharedPreferences("nok", MODE_PRIVATE).getString("update_notified", "");
                AppUpdater.Ready update = AppUpdater.checkAndDownload(this);
                if (update != null && !update.version.equals(announced)) {
                    AppUpdater.notifyReady(this, update);
                    getSharedPreferences("nok", MODE_PRIVATE).edit().putString("update_notified", update.version).apply();
                }
            } catch (Exception e) { retry = true; }
            jobFinished(params, retry);
        }, "nok-update-job").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { return true; }
}
