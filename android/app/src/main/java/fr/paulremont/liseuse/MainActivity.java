package fr.paulremont.liseuse;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
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
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private FrameLayout root;
    private LinearLayout rescue;
    private volatile boolean ready = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final int MIN_CHROME = 80;
    private final Runnable watchdog = () -> {
        if (!ready) showRescue("L'interface n'a pas démarré" + versionNote() + ".");
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new WebStore(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.ink));
        setContentView(root);
        try {
            web = new WebView(this);
        } catch (Throwable t) {
            // Pas de composant WebView utilisable : écran de secours au lieu d'un plantage
            showRescue("Le composant qui affiche les pages (Android System WebView) est absent ou désactivé.");
            return;
        }
        web.setBackgroundColor(getColor(R.color.ink));
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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
        FlavorHooks.attach(this, web);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) {
                    if ("/__net".equals(u.getPath())) return Net.serve(MainActivity.this, u);
                    return store.serve(u.getPath());
                }
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
        int v = chromeVersion();
        if (v > 0 && v < MIN_CHROME) {
            showRescue("Le moteur d'affichage du téléphone est trop ancien (version " + v + ", il faut au moins " + MIN_CHROME + ").");
        } else {
            handler.postDelayed(watchdog, 12000);
        }
        checkForUpdate();
    }

    /* ---------- écran de secours (si l'interface ne s'affiche pas) ---------- */

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private int chromeVersion() {
        try {
            Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(WebSettings.getDefaultUserAgent(this));
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private String versionNote() {
        int v = chromeVersion();
        return v > 0 ? " (moteur d'affichage version " + v + ")" : "";
    }

    /** Paquet qui fournit l'affichage des pages : Chrome sur Android 7 à 9 en général, sinon Android System WebView. */
    private String webViewPackage() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                PackageInfo p = WebView.getCurrentWebViewPackage();
                if (p != null) return p.packageName;
            } catch (Throwable ignored) {
            }
        }
        try {
            if (getPackageManager().getApplicationInfo("com.android.chrome", 0).enabled) return "com.android.chrome";
        } catch (Exception ignored) {
        }
        return "com.google.android.webview";
    }

    private Button rescueButton(String label, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setTextColor(primary ? 0xFF241703 : 0xFFF2F0F9);
        b.setBackgroundColor(primary ? 0xFFFFB547 : 0xFF272635);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private void showRescue(String why) {
        if (rescue != null) {
            ((TextView) rescue.getChildAt(1)).setText(why + "\n\nMets à jour « Chrome » et « Android System WebView » dans le Play Store, puis touche Réessayer.");
            rescue.setVisibility(View.VISIBLE);
            return;
        }
        rescue = new LinearLayout(this);
        rescue.setOrientation(LinearLayout.VERTICAL);
        rescue.setGravity(Gravity.CENTER);
        rescue.setBackgroundColor(0xFF15141C);
        rescue.setPadding(dp(32), dp(32), dp(32), dp(32));
        rescue.setClickable(true);

        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name) + " ne s'affiche pas");
        title.setTextColor(0xFFF2F0F9);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        rescue.addView(title);

        TextView body = new TextView(this);
        body.setText(why + "\n\nMets à jour « Chrome » et « Android System WebView » dans le Play Store, puis touche Réessayer.");
        body.setTextColor(0xFFA3A0B8);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        body.setPadding(0, dp(12), 0, dp(12));
        rescue.addView(body);

        rescue.addView(rescueButton("Mettre à jour dans le Play Store", true, v -> openStore(webViewPackage())));
        rescue.addView(rescueButton("Réessayer", false, v -> retry()));
        rescue.addView(rescueButton("Ouvrir l'écran d'accueil Samsung", false, v -> goOtherHome()));
        rescue.addView(rescueButton("Choisir l'écran d'accueil par défaut", false, v -> open(new Intent(Settings.ACTION_HOME_SETTINGS))));
        rescue.addView(rescueButton("Réglages", false, v -> open(new Intent(Settings.ACTION_SETTINGS))));

        root.addView(rescue, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void retry() {
        if (web == null) { recreate(); return; }
        if (rescue != null) rescue.setVisibility(View.GONE);
        ready = false;
        web.reload();
        handler.removeCallbacks(watchdog);
        handler.postDelayed(watchdog, 12000);
    }

    private void openStore(String pkg) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            open(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg)));
        }
    }

    /** Ouvre l'écran d'accueil d'origine (Samsung en priorité) sans changer celui par défaut. */
    private void goOtherHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> list = getPackageManager().queryIntentActivities(home, 0);
        ResolveInfo pick = null;
        for (ResolveInfo ri : list) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            if (pkg.contains("sec.android.app.launcher") || pkg.contains("sec.android.app.twlauncher")) { pick = ri; break; }
            if (pick == null && !pkg.equals("com.android.settings")) pick = ri;
        }
        if (pick == null) { open(new Intent(Settings.ACTION_HOME_SETTINGS)); return; }
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        i.setClassName(pick.activityInfo.packageName, pick.activityInfo.name);
        open(i);
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
        if (web == null) {
            if (!FlavorHooks.isHome()) super.onBackPressed();
            return;
        }
        // L'interface décide (fermer une fenêtre…). Un écran d'accueil ne se ferme jamais ; les autres applis, oui.
        web.evaluateJavascript("(window.liseuseBack && window.liseuseBack()) ? 1 : 0", v -> {
            if (!"1".equals(v) && !FlavorHooks.isHome()) finish();
        });
    }

    /** Bouton d'accueil pressé alors que la liseuse est déjà ouverte : retour à la bibliothèque. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (web != null && intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) {
            web.evaluateJavascript("window.liseuseHome && window.liseuseHome()", null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web == null) return;
        web.onResume();
        FlavorHooks.onResume(this);
        hideSystemBars();
        if (System.currentTimeMillis() - lastCheck > CHECK_EVERY_MS) checkForUpdate();
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
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
        if (!FlavorHooks.immersive()) return;
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
        if (web == null) return;
        lastCheck = System.currentTimeMillis();
        store.checkUpdate(version -> runOnUiThread(() ->
                web.evaluateJavascript("window.liseuseUpdateReady && window.liseuseUpdateReady(" + quote(version) + ")", null)));
    }

    private static String quote(String s) {
        return "'" + s.replaceAll("[^A-Za-z0-9._-]", "") + "'";
    }

    /* ---------- connexion Wi-Fi au PC (Stream Deck) ---------- */

    private OkHttpClient http;
    private WebSocket sock;
    private int sockId = 0;

    private void wsEvent(int id, String ev, String data) {
        String js = "window.__deckWs && window.__deckWs(" + id + ",'" + ev + "'," + JSONObject.quote(data == null ? "" : data) + ")";
        runOnUiThread(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }

    private synchronized void wsConnect(int id, String url) {
        if (url == null || !(url.startsWith("ws://") || url.startsWith("wss://"))) { wsEvent(id, "close", "adresse invalide"); return; }
        if (sock != null) { sock.cancel(); sock = null; }
        if (http == null) http = new OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build();
        sockId = id;
        Request req;
        try { req = new Request.Builder().url(url).build(); } catch (Exception e) { wsEvent(id, "close", "adresse invalide"); return; }
        sock = http.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response r) { wsEvent(id, "open", ""); }
            @Override public void onMessage(WebSocket ws, String text) { wsEvent(id, "message", text); }
            @Override public void onClosing(WebSocket ws, int code, String reason) { ws.close(1000, null); }
            @Override public void onClosed(WebSocket ws, int code, String reason) { wsEvent(id, "close", reason); }
            @Override public void onFailure(WebSocket ws, Throwable t, Response r) { wsEvent(id, "close", t.getMessage()); }
        });
    }

    private synchronized void wsSend(int id, String text) {
        if (sock != null && id == sockId) sock.send(text);
    }

    private synchronized void wsClose(int id) {
        if (sock != null && id == sockId) { sock.close(1000, null); sock = null; }
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

        @JavascriptInterface
        public void openSamsungHome() { runOnUiThread(MainActivity.this::goOtherHome); }

        /** L'interface signale qu'elle s'est bien affichée : on annule l'écran de secours. */
        @JavascriptInterface
        public void ready() {
            ready = true;
            runOnUiThread(() -> {
                handler.removeCallbacks(watchdog);
                if (rescue != null) rescue.setVisibility(View.GONE);
            });
        }

        @JavascriptInterface
        public void wsConnect(int id, String url) { MainActivity.this.wsConnect(id, url); }

        @JavascriptInterface
        public void wsSend(int id, String text) { MainActivity.this.wsSend(id, text); }

        @JavascriptInterface
        public void wsClose(int id) { MainActivity.this.wsClose(id); }

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
