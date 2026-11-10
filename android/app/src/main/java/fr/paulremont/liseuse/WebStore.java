package fr.paulremont.liseuse;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Sert les fichiers de l'interface et la met à jour.
 * Ordre : version téléchargée (dossier interne "web") puis version embarquée dans l'APK (assets/web).
 * Mise à jour : lit version.json sur GitHub Pages, télécharge tout dans un dossier temporaire,
 * puis remplace l'ancienne version d'un coup. En cas d'échec, rien ne change.
 */
class WebStore {

    static final String REMOTE = BuildConfig.WEB_REMOTE;

    interface Ready { void onReady(String version); }

    private final Context ctx;
    private final File dir;
    private final SharedPreferences prefs;
    private volatile boolean busy = false;

    WebStore(Context c) {
        ctx = c.getApplicationContext();
        dir = new File(ctx.getFilesDir(), "web");
        prefs = ctx.getSharedPreferences("liseuse", Context.MODE_PRIVATE);
    }

    String version() {
        return prefs.getString("webVersion", "intégrée");
    }

    WebResourceResponse serve(String path) {
        if (path == null || path.isEmpty() || path.equals("/")) path = "/index.html";
        String rel = path.startsWith("/") ? path.substring(1) : path;
        if (rel.contains("..")) return notFound();
        String mime = mime(rel);
        try {
            InputStream in;
            File f = new File(dir, rel);
            if (prefs.getString("webVersion", null) != null && f.isFile()) in = new FileInputStream(f);
            else in = ctx.getAssets().open("web/" + rel);
            boolean text = mime.startsWith("text/") || mime.endsWith("javascript") || mime.endsWith("json");
            WebResourceResponse r = new WebResourceResponse(mime, text ? "utf-8" : null, in);
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-cache");
            r.setResponseHeaders(h);
            return r;
        } catch (IOException e) {
            return notFound();
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", new HashMap<>(),
                new ByteArrayInputStream(new byte[0]));
    }

    private static String mime(String p) {
        String n = p.toLowerCase();
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "application/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".woff2")) return "font/woff2";
        if (n.endsWith(".woff")) return "font/woff";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".md") || n.endsWith(".txt") || n.endsWith("license")) return "text/plain";
        return "application/octet-stream";
    }

    void checkUpdate(Ready cb) {
        if (busy) return;
        busy = true;
        new Thread(() -> {
            try {
                JSONObject o = new JSONObject(get(REMOTE + "version.json?t=" + System.currentTimeMillis()));
                String v = o.getString("version");
                if (v.equals(prefs.getString("webVersion", ""))) return;
                JSONArray files = o.getJSONArray("files");

                File tmp = new File(ctx.getFilesDir(), "web-tmp");
                deleteAll(tmp);
                if (!tmp.mkdirs()) return;
                for (int k = 0; k < files.length(); k++) {
                    String rel = files.getString(k);
                    if (rel.contains("..") || rel.startsWith("/")) continue;
                    File out = new File(tmp, rel);
                    File parent = out.getParentFile();
                    if (parent != null) parent.mkdirs();
                    download(REMOTE + encodePath(rel) + "?v=" + Uri.encode(v), out);
                }
                if (!new File(tmp, "index.html").isFile()) return;

                File old = new File(ctx.getFilesDir(), "web-old");
                deleteAll(old);
                if (dir.exists() && !dir.renameTo(old)) return;
                if (!tmp.renameTo(dir)) {
                    old.renameTo(dir);
                    return;
                }
                deleteAll(old);
                prefs.edit().putString("webVersion", v).apply();
                cb.onReady(v);
            } catch (Exception ignored) {
                // Pas de réseau, Pages pas encore activé… on garde la version actuelle.
            } finally {
                busy = false;
            }
        }).start();
    }

    private static String encodePath(String rel) {
        StringBuilder b = new StringBuilder();
        for (String part : rel.split("/")) {
            if (b.length() > 0) b.append('/');
            b.append(Uri.encode(part));
        }
        return b.toString();
    }

    private static HttpURLConnection connect(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        c.setUseCaches(false);
        if (c.getResponseCode() != 200) {
            c.disconnect();
            throw new IOException("HTTP " + c.getResponseCode() + " " + url);
        }
        return c;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = connect(url);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copy(in, out);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File dest) throws IOException {
        HttpURLConnection c = connect(url);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
            copy(in, out);
        } finally {
            c.disconnect();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static void deleteAll(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteAll(k);
        f.delete();
    }
}
