package fr.paulremont.liseuse;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.ExistingWorkPolicy;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Garde-fou : si la chaîne de mises à jour s'est arrêtée, on la relance. */
public class WatchdogWorker extends Worker {

    public WatchdogWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        long last = Losc.load(c).optLong("updatedAt", 0);
        if (System.currentTimeMillis() - last > 14 * Losc.MIN) {
            long delay;
            try {
                delay = Losc.tick(c, false);
            } catch (Throwable t) {
                delay = 10 * Losc.MIN;
            }
            TickWorker.schedule(c, delay, ExistingWorkPolicy.REPLACE);
        }
        return Result.success();
    }
}
