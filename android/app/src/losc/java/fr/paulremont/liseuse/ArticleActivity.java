package fr.paulremont.liseuse;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Mode lecture : la page du média est chargée en coulisse, on en extrait le texte
 * (bibliothèque Readability de Mozilla, comme le mode lecture de Firefox) et on le remet
 * en page façon Dogues. Les articles réservés aux abonnés ne sont pas extraits.
 */
public class ArticleActivity extends Activity {

    private static final String READER = "https://" + MainActivity.HOST + "/reader.html";
    private static final String EXTRACT = ";(function(){try{"
            + "var ld=[].slice.call(document.querySelectorAll('script[type=\"application/ld+json\"]')).map(function(s){return s.textContent}).join(' ');"
            + "var paywall=/\"isAccessibleForFree\"\\s*:\\s*\"?false\"?/i.test(ld)"
            + "||!!document.querySelector('meta[property=\"article:content_tier\"][content=\"locked\"],meta[name=\"article:content_tier\"][content=\"locked\"]');"
            + "var og=document.querySelector('meta[property=\"og:image\"]'),site=document.querySelector('meta[property=\"og:site_name\"]'),pt=document.querySelector('meta[property=\"article:published_time\"]');"
            + "var r=new Readability(document.cloneNode(true),{charThreshold:300}).parse();"
            + "return JSON.stringify({ok:!!r,paywall:paywall,title:r?r.title:'',byline:r?r.byline:'',site:(site&&site.content)||(r&&r.siteName)||'',"
            + "html:r?r.content:'',len:r?r.length:0,url:location.href,lead:og?og.content:'',pub:pt?(Date.parse(pt.content)||0):0});"
            + "}catch(e){return JSON.stringify({ok:false,err:String(e)})}})()";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView page, reader;
    private ProgressBar bar;
    private TextView title;
    private String source = "";
    private String readability;
    private String pending;
    private boolean readerReady, done, extracted, showingPage;
    private int attempts;

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private TextView button(String label, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setMinWidth(dp(48));
        b.setBackgroundColor(0xFF13265A);
        b.setOnClickListener(l);
        return b;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String url = getIntent().getStringExtra("url");
        String s = getIntent().getStringExtra("source");
        source = s == null ? "" : s;
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            finish();
            return;
        }
        readability = asset("web/lib/Readability.js");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF06102A);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(8), dp(10), dp(8));
        top.addView(button("←  Retour", v -> onBackPressed()), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        title = new TextView(this);
        title.setText(source.isEmpty() ? "Article" : source);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setPadding(dp(14), 0, dp(14), 0);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(top);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFD6102A));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFD6102A));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));

        FrameLayout stack = new FrameLayout(this);
        root.addView(stack, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 1. La page du média, chargée en coulisse (sans images pour aller plus vite)
        page = new WebView(this);
        WebSettings ps = page.getSettings();
        ps.setJavaScriptEnabled(true);
        ps.setDomStorageEnabled(true);
        ps.setLoadWithOverviewMode(true);
        ps.setUseWideViewPort(true);
        ps.setBuiltInZoomControls(true);
        ps.setDisplayZoomControls(false);
        ps.setAllowFileAccess(false);
        ps.setBlockNetworkImage(true);
        page.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) return false;
                external(request.getUrl());
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String u) {
                String host = Uri.parse(u).getHost();
                if (host == null || done) return;
                if (host.startsWith("consent.")) {
                    // Choix des cookies Google : on montre la page le temps du clic
                    showPage(false);
                    state("consent");
                    return;
                }
                if (host.endsWith("news.google.com")) return; // redirection vers le média en cours
                if (showingPage && !extracted) hidePage();
                handler.removeCallbacks(extractLater);
                handler.postDelayed(extractLater, 1500);
            }
        });
        page.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                bar.setProgress(p);
                bar.setAlpha(p >= 100 ? 0.35f : 1f);
            }
        });
        stack.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 2. Le lecteur Dogues, par-dessus
        reader = new WebView(this);
        WebSettings rs = reader.getSettings();
        rs.setJavaScriptEnabled(true);
        rs.setAllowFileAccess(false);
        rs.setTextZoom(100);
        final WebStore store = new WebStore(this);
        reader.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (MainActivity.HOST.equals(u.getHost())) return store.serve(u.getPath());
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (MainActivity.HOST.equals(request.getUrl().getHost())) return false;
                external(request.getUrl());
                return true;
            }
        });
        reader.addJavascriptInterface(new Bridge(), "Article");
        stack.addView(reader, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        reader.loadUrl(READER);
        page.loadUrl(url);
        handler.postDelayed(() -> { if (!done) giveUp("fail"); }, 25000);
    }

    private final Runnable extractLater = this::extract;

    private void extract() {
        if (done || readability.isEmpty()) {
            if (readability.isEmpty()) giveUp("fail");
            return;
        }
        attempts++;
        page.evaluateJavascript(readability + EXTRACT, value -> {
            if (done) return;
            try {
                Object v = new JSONTokener(value).nextValue();
                JSONObject o = new JSONObject(v instanceof String ? (String) v : String.valueOf(v));
                if (o.optBoolean("paywall")) { giveUp("paywall"); return; }
                if (o.optBoolean("ok") && o.optInt("len") >= 500) {
                    if (o.optString("site").isEmpty()) o.put("site", source);
                    o.put("source", source);
                    done = true;
                    extracted = true;
                    render(o.toString());
                    return;
                }
            } catch (Exception ignored) { }
            if (attempts < 3) handler.postDelayed(extractLater, 2500);
            else giveUp("fail");
        });
    }

    private void giveUp(String kind) {
        if (done && !"fail".equals(kind) && !"paywall".equals(kind)) return;
        done = true;
        hidePage();
        state(kind);
    }

    private void state(String kind) {
        run("window.state&&window.state('" + kind + "')");
    }

    private void render(String json) {
        hidePage();
        run("window.render&&window.render(" + json + ")");
    }

    /** Exécute dans le lecteur, ou attend qu'il soit prêt. */
    private void run(String js) {
        runOnUiThread(() -> {
            if (readerReady) reader.evaluateJavascript(js, null);
            else pending = js;
        });
    }

    private void showPage(boolean withImages) {
        runOnUiThread(() -> {
            showingPage = true;
            if (withImages) page.getSettings().setBlockNetworkImage(false);
            reader.setVisibility(View.GONE);
            page.setVisibility(View.VISIBLE);
        });
    }

    private void hidePage() {
        runOnUiThread(() -> {
            showingPage = false;
            reader.setVisibility(View.VISIBLE);
        });
    }

    private void external(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
    }

    private String asset(String path) {
        try (InputStream in = getAssets().open(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private class Bridge {
        @JavascriptInterface
        public void readerReady() {
            runOnUiThread(() -> {
                readerReady = true;
                if (pending != null) {
                    reader.evaluateJavascript(pending, null);
                    pending = null;
                }
            });
        }

        @JavascriptInterface
        public void showPage() { ArticleActivity.this.showPage(true); }

        @JavascriptInterface
        public void openBrowser() {
            String u = page.getUrl();
            if (u != null) runOnUiThread(() -> external(Uri.parse(u)));
        }

        @JavascriptInterface
        public void openLink(String u) {
            if (u != null && (u.startsWith("https://") || u.startsWith("http://"))) runOnUiThread(() -> external(Uri.parse(u)));
        }
    }

    @Override
    public void onBackPressed() {
        if (showingPage && extracted) { hidePage(); return; }
        if (showingPage && page.canGoBack()) { page.goBack(); return; }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        for (WebView w : new WebView[]{page, reader}) {
            if (w != null) {
                w.stopLoading();
                w.destroy();
            }
        }
        super.onDestroy();
    }
}
