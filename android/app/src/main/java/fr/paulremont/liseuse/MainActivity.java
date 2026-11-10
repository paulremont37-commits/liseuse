package fr.paulremont.liseuse;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.List;

/**
 * Écran d'accueil de la liseuse : une WebView plein écran qui affiche l'interface (dossier web/ du repo).
 * L'interface est servie depuis l'adresse fixe https://appassets.androidplatform.net/ pour que la
 * bibliothèque (stockée dans la WebView) reste la même entre deux versions.
 */
public class MainActivity extends Activity {

    static final String HOST = "appassets.androidplatform.net";
    static final String START = "https://" + HOST + "/index.html";
    private static final int PICK_FILES = 42;
    private static final long CHECK_EVERY_MS = 6L * 60 * 60 * 1000;

    private WebView web;
    private WebStore store;
    private ValueCallback<Uri[]> fileCallback;
    private long lastCheck = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new WebStore(this);

        web = new WebView(this);
        web.setBackgroundColor(0xFF15141C);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setMediaPlaybackRequiresUserGesture(true);

        web.addJavascriptInterface(new Bridge(), "Android");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return store.serve(u.getPath());
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                // Les liens externes s'ouvrent dans le navigateur, jamais dans la liseuse
                open(new Intent(Intent.ACTION_VIEW, u));
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    startActivityForResult(i, PICK_FILES);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        web.loadUrl(START);
        hideSystemBars();
        checkForUpdate();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILES || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                result = new Uri[clip.getItemCount()];
                for (int k = 0; k < clip.getItemCount(); k++) result[k] = clip.getItemAt(k).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    /** Bouton retour : l'interface décide (fermer le lecteur, une fenêtre…). Un écran d'accueil ne se ferme jamais. */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.liseuseBack && window.liseuseBack()) ? 1 : 0", null);
    }

    /** Bouton d'accueil pressé alors que la liseuse est déjà ouverte : retour à la bibliothèque. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) {
            web.evaluateJavascript("window.liseuseHome && window.liseuseHome()", null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        hideSystemBars();
        if (System.currentTimeMillis() - lastCheck > CHECK_EVERY_MS) checkForUpdate();
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    /** Plein écran façon liseuse : barres système cachées, un glissement depuis le bord les fait réapparaître. */
    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    private void checkForUpdate() {
        lastCheck = System.currentTimeMillis();
        store.checkUpdate(version -> runOnUiThread(() ->
                web.evaluateJavascript("window.liseuseUpdateReady && window.liseuseUpdateReady(" + quote(version) + ")", null)));
    }

    private static String quote(String s) {
        return "'" + s.replaceAll("[^A-Za-z0-9._-]", "") + "'";
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    /** Fonctions appelables depuis l'interface via window.Android */
    private class Bridge {
        @JavascriptInterface
        public void keepScreenOn(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public int battery() {
            Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return -1;
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            return (level < 0 || scale <= 0) ? -1 : Math.round(level * 100f / scale);
        }

        @JavascriptInterface
        public void openWifi() { open(new Intent(Settings.ACTION_WIFI_SETTINGS)); }

        @JavascriptInterface
        public void openSettings() { open(new Intent(Settings.ACTION_SETTINGS)); }

        @JavascriptInterface
        public void openHomeSettings() { open(new Intent(Settings.ACTION_HOME_SETTINGS)); }

        /** Ouvre l'écran d'accueil d'origine (Samsung en priorité) sans changer celui par défaut. */
        @JavascriptInterface
        public void openSamsungHome() {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> list = getPackageManager().queryIntentActivities(home, 0);
            ResolveInfo pick = null;
            for (ResolveInfo ri : list) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                if (pkg.contains("sec.android.app.launcher")) { pick = ri; break; }
                if (pick == null && !pkg.equals("com.android.settings")) pick = ri;
            }
            if (pick == null) { open(new Intent(Settings.ACTION_HOME_SETTINGS)); return; }
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            i.setClassName(pick.activityInfo.packageName, pick.activityInfo.name);
            open(i);
        }

        @JavascriptInterface
        public void checkUpdate() { runOnUiThread(MainActivity.this::checkForUpdate); }

        @JavascriptInterface
        public void reload() { runOnUiThread(() -> web.reload()); }

        @JavascriptInterface
        public String webVersion() { return store.version(); }

        @JavascriptInterface
        public String appVersion() {
            try {
                PackageInfo p = getPackageManager().getPackageInfo(getPackageName(), 0);
                return p.versionName;
            } catch (Exception e) {
                return "";
            }
        }
    }
}
