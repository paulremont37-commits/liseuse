package fr.paulremont.liseuse;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.webkit.WebView;

/** Stream Deck : écran d'accueil plein écran, Wi-Fi gardé en mode faible latence. */
final class FlavorHooks {
    private FlavorHooks() {}

    private static WifiManager.WifiLock wifiLock;

    static boolean isHome() { return true; }

    static boolean immersive() { return true; }

    static void attach(Activity a, WebView web) {
        // Sans ça, Android met la puce Wi-Fi en économie d'énergie entre deux appuis :
        // le premier message après une pause peut alors attendre 100 à 300 ms.
        // En mode « faible latence » (Android 10+), le verrou ne joue que quand l'appli
        // est à l'écran et l'écran allumé : pas d'impact sur la batterie le reste du temps.
        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) a.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(mode, "StreamDeck:latence");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) wifiLock.acquire();
        } catch (Exception ignored) {
        }
    }

    private static boolean wanted = true;

    static void onResume(Activity a) {
        lowLatency(wanted);
    }

    /** Écran en veille (atténué) : on rend le Wi-Fi à l'économie d'énergie ; au réveil on le reprend. */
    static void lowLatency(boolean on) {
        wanted = on;
        try {
            if (wifiLock == null) return;
            if (on && !wifiLock.isHeld()) wifiLock.acquire();
            if (!on && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception ignored) {
        }
    }
}
