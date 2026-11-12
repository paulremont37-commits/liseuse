package fr.paulremont.liseuse;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/** Fonctions appelables depuis l'interface via window.Losc */
class LoscBridge {
    private final Context c;

    LoscBridge(Context c) {
        this.c = c.getApplicationContext();
    }

    private SharedPreferences prefs() {
        return c.getSharedPreferences(Losc.PREFS, Context.MODE_PRIVATE);
    }

    @JavascriptInterface
    public String data() {
        return Losc.json(c);
    }

    @JavascriptInterface
    public void refresh() {
        TickWorker.refreshNow(c);
    }

    @JavascriptInterface
    public String settings() {
        SharedPreferences p = prefs();
        JSONObject o = new JSONObject();
        Losc.put(o, "notifGoals", p.getBoolean("notifGoals", true));
        Losc.put(o, "notifLineup", p.getBoolean("notifLineup", true));
        Losc.put(o, "notifNews", p.getBoolean("notifNews", true));
        boolean allowed = Build.VERSION.SDK_INT < 33
                || c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
        Losc.put(o, "notifAllowed", allowed);
        return o.toString();
    }

    @JavascriptInterface
    public void setPref(String name, boolean value) {
        if (!"notifGoals".equals(name) && !"notifLineup".equals(name) && !"notifNews".equals(name)) return;
        prefs().edit().putBoolean(name, value).apply();
    }

    /** Ouvre un article dans l'appli (page du média, sous le bandeau Dogues). */
    @JavascriptInterface
    public void openArticle(String url, String source) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
        Intent i = new Intent(c, ArticleActivity.class)
                .putExtra("url", url)
                .putExtra("source", source == null ? "" : source)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    @JavascriptInterface
    public void openNotifSettings() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 26) {
            i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
        } else {
            i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Exception ignored) {
        }
    }
}
