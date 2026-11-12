package fr.paulremont.liseuse;

import android.app.Activity;
import android.webkit.WebView;

/** Liseuse et Stream Deck : écran d'accueil plein écran, rien de plus. */
final class FlavorHooks {
    private FlavorHooks() {}

    static boolean isHome() { return true; }

    static boolean immersive() { return true; }

    static void attach(Activity a, WebView web) {}

    static void onResume(Activity a) {}
}
