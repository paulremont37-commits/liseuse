package fr.paulremont.liseuse;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * Mise à jour en arrière-plan : chaque passage programme le suivant
 * (toutes les 10 min, 5 min autour d'un match, 3 min pendant le match).
 * Un garde-fou toutes les 15 min relance la chaîne si Android l'a interrompue.
 */
public class TickWorker extends Worker {

    public TickWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        boolean force = getInputData().getBoolean("force", false);
        long delay;
        try {
            delay = Losc.tick(c, force);
        } catch (Throwable t) {
            delay = 10 * Losc.MIN;
        }
        if (!force) schedule(c, delay, ExistingWorkPolicy.APPEND_OR_REPLACE);
        return Result.success();
    }

    static Constraints network() {
        return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    }

    static void schedule(Context c, long delayMs, ExistingWorkPolicy policy) {
        OneTimeWorkRequest r = new OneTimeWorkRequest.Builder(TickWorker.class)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(network())
                .build();
        WorkManager.getInstance(c).enqueueUniqueWork("tick", policy, r);
    }

    /** Bouton « actualiser » ou ouverture de l'appli : un passage immédiat, sans toucher à la chaîne. */
    static void refreshNow(Context c) {
        OneTimeWorkRequest r = new OneTimeWorkRequest.Builder(TickWorker.class)
                .setInputData(new Data.Builder().putBoolean("force", true).build())
                .setConstraints(network())
                .build();
        WorkManager.getInstance(c).enqueueUniqueWork("tick-now", ExistingWorkPolicy.KEEP, r);
    }

    static void ensure(Context c) {
        schedule(c, 0, ExistingWorkPolicy.KEEP);
        PeriodicWorkRequest w = new PeriodicWorkRequest.Builder(WatchdogWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(network())
                .build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("watchdog", ExistingPeriodicWorkPolicy.KEEP, w);
    }
}
