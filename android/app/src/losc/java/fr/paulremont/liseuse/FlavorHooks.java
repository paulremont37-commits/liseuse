package fr.paulremont.liseuse;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.webkit.WebView;

import java.lang.ref.WeakReference;

/** Appli Dogues : appli normale (pas un écran d'accueil), mises à jour en arrière-plan, notifications. */
final class FlavorHooks {
    private FlavorHooks() {}

    static boolean isHome() { return false; }

    static boolean immersive() { return false; }

    static void attach(Activity a, WebView web) {
        web.addJavascriptInterface(new LoscBridge(a), "Losc");
        final WeakReference<WebView> ref = new WeakReference<>(web);
        Losc.listener = () -> {
            WebView w = ref.get();
            if (w != null) w.post(() -> w.evaluateJavascript("window.dogues && window.dogues.onData()", null));
        };
        TickWorker.ensure(a);
        if (Build.VERSION.SDK_INT >= 33
                && a.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
        }
    }

    static void onResume(Activity a) {
        TickWorker.ensure(a);
        long last = Losc.load(a).optLong("updatedAt", 0);
        if (System.currentTimeMillis() - last > 5 * Losc.MIN) TickWorker.refreshNow(a);
    }
}
