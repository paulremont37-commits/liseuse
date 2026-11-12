package fr.paulremont.liseuse;

import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Requêtes réseau des extensions de la liseuse.
 * L'interface appelle https://appassets.androidplatform.net/__net?u=<adresse>&m=<méthode>&h=<en-têtes JSON>&b=<corps>
 * et reçoit la réponse du site comme si elle venait de l'appli (pas de blocage CORS, en-têtes libres).
 */
class Net {

    private static OkHttpClient client;
    private static String userAgent;

    /** Cookies gardés en mémoire le temps de la session, par domaine. */
    private static final CookieJar COOKIES = new CookieJar() {
        private final Map<String, List<Cookie>> store = new HashMap<>();

        @Override
        public synchronized void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            List<Cookie> list = store.get(url.host());
            if (list == null) list = new ArrayList<>();
            for (Cookie c : cookies) {
                Iterator<Cookie> it = list.iterator();
                while (it.hasNext()) if (it.next().name().equals(c.name())) it.remove();
                list.add(c);
            }
            store.put(url.host(), list);
        }

        @Override
        public synchronized List<Cookie> loadForRequest(HttpUrl url) {
            List<Cookie> out = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (List<Cookie> list : store.values())
                for (Cookie c : list) if (c.expiresAt() > now && c.matches(url)) out.add(c);
            return out;
        }
    };

    private static synchronized OkHttpClient client(Context c) {
        if (client == null) {
            client = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .cookieJar(COOKIES)
                    .build();
            try { userAgent = WebSettings.getDefaultUserAgent(c); } catch (Exception e) { userAgent = "Mozilla/5.0 (Linux; Android) Liseuse"; }
        }
        return client;
    }

    static WebResourceResponse serve(Context ctx, Uri u) {
        String url = u.getQueryParameter("u");
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return error(400, "adresse invalide");
        String method = u.getQueryParameter("m");
        if (method == null || method.isEmpty()) method = "GET";
        method = method.toUpperCase();
        try {
            OkHttpClient http = client(ctx);
            Request.Builder b = new Request.Builder().url(url);
            String h = u.getQueryParameter("h");
            boolean hasUa = false;
            String contentType = null;
            if (h != null && !h.isEmpty()) {
                JSONObject o = new JSONObject(h);
                Iterator<String> keys = o.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    String v = o.optString(k);
                    if (k.equalsIgnoreCase("user-agent")) hasUa = true;
                    if (k.equalsIgnoreCase("content-type")) contentType = v;
                    b.header(k, v);
                }
            }
            if (!hasUa) b.header("User-Agent", userAgent);
            String body = u.getQueryParameter("b");
            if ("GET".equals(method) || "HEAD".equals(method)) {
                b.method(method, null);
            } else {
                MediaType mt = MediaType.parse(contentType != null ? contentType : "application/x-www-form-urlencoded");
                b.method(method, RequestBody.create(body == null ? "" : body, mt));
            }
            try (Response r = http.newCall(b.build()).execute()) {
                ResponseBody rb = r.body();
                byte[] bytes = rb != null ? rb.bytes() : new byte[0];
                String type = r.header("Content-Type", "application/octet-stream");
                String mime = type.split(";")[0].trim();
                String charset = null;
                MediaType parsed = MediaType.parse(type);
                if (parsed != null && parsed.charset() != null) charset = parsed.charset().name();
                Map<String, String> headers = new HashMap<>();
                headers.put("Access-Control-Allow-Origin", "*");
                headers.put("Access-Control-Expose-Headers", "X-Status, X-Final-Url");
                headers.put("Cache-Control", "no-store");
                headers.put("X-Status", String.valueOf(r.code()));
                headers.put("X-Final-Url", r.request().url().toString());
                int code = r.code();
                // WebView refuse les codes 3xx dans une réponse interceptée
                if (code < 200 || (code >= 300 && code < 400) || code > 599) code = 200;
                String reason = r.message();
                if (reason == null || reason.trim().isEmpty()) reason = "OK";
                return new WebResourceResponse(mime, charset, code, reason, headers, new ByteArrayInputStream(bytes));
            }
        } catch (Exception e) {
            return error(502, e.getClass().getSimpleName() + (e.getMessage() != null ? " : " + e.getMessage() : ""));
        }
    }

    private static WebResourceResponse error(int code, String msg) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Expose-Headers", "X-Status, X-Error");
        headers.put("X-Status", "0");
        headers.put("X-Error", msg.replaceAll("[\\r\\n]", " "));
        return new WebResourceResponse("text/plain", "utf-8", code, "Erreur", headers,
                new ByteArrayInputStream(msg.getBytes(StandardCharsets.UTF_8)));
    }
}
