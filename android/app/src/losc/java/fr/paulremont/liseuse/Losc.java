package fr.paulremont.liseuse;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Moteur de données de l'appli Dogues (appli de fan non officielle).
 * Tout est récupéré ici, côté Android, pour que les mises à jour et les notifications
 * marchent aussi quand l'appli est fermée. L'interface lit juste le fichier losc.json.
 *
 * Sources :
 *  - actus : flux RSS de Google Actualités (titres + liens vers les articles) ;
 *  - matchs, live, compos, absents, classement, effectif : API-Football (clé perso, 100 requêtes/jour).
 */
final class Losc {
    private Losc() {}

    static final String PREFS = "losc";
    static final String API = "https://v3.football.api-sports.io";
    static final int LIGUE1 = 61;
    static final long MIN = 60_000L, HOUR = 60 * MIN;
    static final Object LOCK = new Object();

    static final Set<String> LIVE = set("1H", "HT", "2H", "ET", "BT", "P", "LIVE", "INT", "SUSP");
    static final Set<String> DONE = set("FT", "AET", "PEN", "AWD", "WO");
    static final Set<String> UPCOMING = set("NS", "TBD");

    interface Listener { void onUpdate(); }
    static volatile Listener listener;

    private static OkHttpClient http;

    static synchronized OkHttpClient http() {
        if (http == null) {
            http = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build();
        }
        return http;
    }

    private static Set<String> set(String... v) {
        Set<String> s = new HashSet<>();
        Collections.addAll(s, v);
        return s;
    }

    static class ApiError extends Exception {
        ApiError(String m) { super(m); }
    }

    /* ------------------------------------------------------------------ fichier */

    static File file(Context c) { return new File(c.getFilesDir(), "losc.json"); }

    static JSONObject load(Context c) {
        File f = file(c);
        if (!f.isFile()) return new JSONObject();
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return new JSONObject(new String(b, 0, off, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static void save(Context c, JSONObject o) {
        File tmp = new File(c.getFilesDir(), "losc.json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(file(c))) {
            file(c).delete();
            tmp.renameTo(file(c));
        }
    }

    static String json(Context c) {
        synchronized (LOCK) {
            return load(c).toString();
        }
    }

    /* ------------------------------------------------------------------ cycle */

    /** Fait le tour des sources qui sont « dues » et renvoie le délai avant le prochain passage. */
    static long tick(Context c, boolean force) {
        long delay;
        synchronized (LOCK) {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONObject d = load(c);
            long now = System.currentTimeMillis();
            JSONObject errors = d.optJSONObject("errors");
            if (errors == null) errors = new JSONObject();

            // Actus : au plus une fois toutes les 9 minutes (ou tout de suite si on force)
            if (force || now - d.optLong("newsAt", 0) > 9 * MIN) {
                try {
                    fetchNews(c, d, p);
                    d.put("newsAt", now);
                    errors.remove("news");
                } catch (Exception e) {
                    put(errors, "news", "Actus indisponibles pour le moment.");
                }
            }

            // Données foot
            String key = p.getString("key", "").trim();
            if (key.isEmpty()) {
                put(errors, "api", "nokey");
            } else if (force || now - p.getLong("apiErrAt", 0) > HOUR) {
                try {
                    footData(c, d, p, key, now, force);
                    errors.remove("api");
                    p.edit().remove("apiErrAt").apply();
                } catch (ApiError e) {
                    put(errors, "api", e.getMessage());
                    p.edit().putLong("apiErrAt", now).apply();
                } catch (Exception e) {
                    put(errors, "api", "Connexion à la base foot impossible. Nouvel essai bientôt.");
                    p.edit().putLong("apiErrAt", now - HOUR + 10 * MIN).apply();
                }
            }

            delay = nextDelay(d, now);
            put(d, "errors", errors);
            put(d, "updatedAt", now);
            put(d, "nextAt", now + delay);
            put(d, "budget", budget(p));
            save(c, d);
        }
        Listener l = listener;
        if (l != null) l.onUpdate();
        return delay;
    }

    static long nextDelay(JSONObject d, long now) {
        JSONObject m = d.optJSONObject("match");
        if (m != null && LIVE.contains(m.optString("status"))) return 3 * MIN;
        JSONObject next = nextFixture(d, now);
        if (next != null) {
            long ko = next.optLong("ts") * 1000;
            if (now >= ko - 90 * MIN && now <= ko + 150 * MIN) return 5 * MIN;
        }
        return 10 * MIN;
    }

    /* ------------------------------------------------------------------ API-Football */

    static int season(long now) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris"));
        cal.setTimeInMillis(now);
        int y = cal.get(Calendar.YEAR);
        return cal.get(Calendar.MONTH) >= Calendar.JULY ? y : y - 1;
    }

    static String utcDay() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(System.currentTimeMillis());
    }

    static JSONObject budget(SharedPreferences p) {
        JSONObject b = new JSONObject();
        boolean today = utcDay().equals(p.getString("budgetDay", ""));
        put(b, "used", today ? p.getInt("used", 0) : 0);
        put(b, "remaining", today ? p.getInt("remaining", -1) : -1);
        return b;
    }

    static JSONObject api(SharedPreferences p, String key, String path) throws Exception {
        String today = utcDay();
        if (!today.equals(p.getString("budgetDay", ""))) {
            p.edit().putString("budgetDay", today).putInt("used", 0).putInt("remaining", -1).apply();
        }
        if (p.getInt("remaining", -1) == 0) {
            throw new ApiError("Quota du jour atteint (100 requêtes). Il se recharge à 1 h du matin.");
        }
        Request r = new Request.Builder().url(API + path).header("x-apisports-key", key).build();
        try (Response res = http().newCall(r).execute()) {
            SharedPreferences.Editor e = p.edit().putInt("used", p.getInt("used", 0) + 1);
            String rem = res.header("x-ratelimit-requests-remaining");
            if (rem != null) {
                try { e.putInt("remaining", Integer.parseInt(rem.trim())); } catch (NumberFormatException ignored) { }
            }
            e.apply();
            if (res.code() == 429) throw new ApiError("Trop de requêtes d'un coup. Nouvel essai dans quelques minutes.");
            if (res.code() == 401 || res.code() == 403) throw new ApiError("Clé API refusée. Vérifie-la dans les réglages.");
            String body = res.body() != null ? res.body().string() : "";
            JSONObject o = new JSONObject(body);
            String msg = errorText(o.opt("errors"));
            if (msg != null) throw new ApiError(msg);
            return o;
        }
    }

    static String errorText(Object errs) {
        if (errs instanceof JSONObject) {
            JSONObject j = (JSONObject) errs;
            if (j.length() == 0) return null;
            if (j.has("token")) return "Clé API refusée. Vérifie-la dans les réglages.";
            if (j.has("requests")) return "Quota du jour atteint (100 requêtes). Il se recharge à 1 h du matin.";
            if (j.has("rateLimit")) return "Trop de requêtes d'un coup. Nouvel essai dans quelques minutes.";
            if (j.has("plan")) return "L'abonnement gratuit API-Football ne donne pas accès à cette saison (" + j.optString("plan") + ").";
            Iterator<String> k = j.keys();
            return k.hasNext() ? j.optString(k.next()) : null;
        }
        if (errs instanceof JSONArray && ((JSONArray) errs).length() > 0) return ((JSONArray) errs).optString(0);
        return null;
    }

    static void footData(Context c, JSONObject d, SharedPreferences p, String key, long now, boolean force) throws Exception {
        int season = season(now);
        put(d, "season", season);
        JSONObject b = budget(p);
        int remaining = b.optInt("remaining", -1);
        boolean low = remaining >= 0 && remaining < 15;

        // 1. L'identifiant de l'équipe (une seule fois)
        int team = p.getInt("team", 0);
        if (team == 0) {
            JSONArray r = api(p, key, "/teams?search=Lille").optJSONArray("response");
            for (int i = 0; r != null && i < r.length(); i++) {
                JSONObject t = r.optJSONObject(i).optJSONObject("team");
                if (t != null && "France".equals(t.optString("country")) && t.optString("name").startsWith("Lille")) {
                    team = t.optInt("id");
                    break;
                }
            }
            if (team == 0) throw new ApiError("Équipe introuvable dans la base API-Football.");
            p.edit().putInt("team", team).apply();
        }
        put(d, "team", team);

        // 2. Le calendrier de la saison (toutes compétitions) : toutes les 3 h, ou après un match
        long fxAt = d.optLong("fixturesAt", 0);
        if (now - fxAt > 3 * HOUR || d.optBoolean("needsRefresh") || (force && now - fxAt > 30 * MIN)) {
            JSONArray r = api(p, key, "/fixtures?team=" + team + "&season=" + season).optJSONArray("response");
            List<JSONObject> list = new ArrayList<>();
            for (int i = 0; r != null && i < r.length(); i++) list.add(slimFixture(r.optJSONObject(i)));
            Collections.sort(list, (x, y) -> Long.compare(x.optLong("ts"), y.optLong("ts")));
            put(d, "fixtures", new JSONArray(list));
            put(d, "fixturesAt", now);
            put(d, "needsRefresh", false);
        }

        // 3. Le prochain match : live, compo, absents
        JSONObject next = nextFixture(d, now);
        if (next != null) {
            long id = next.optLong("id");
            long ko = next.optLong("ts") * 1000;
            JSONObject match = d.optJSONObject("match");
            if (match != null && match.optLong("id") != id) {
                if (DONE.contains(match.optString("status"))) put(d, "lastMatch", match);
                match = null;
            }
            boolean live = match != null ? LIVE.contains(match.optString("status")) : LIVE.contains(next.optString("status"));
            boolean window = now >= ko - 90 * MIN && now <= ko + 150 * MIN;
            if (window || live) {
                long mAt = d.optLong("matchAt", 0);
                boolean hasLineup = match != null && match.optJSONArray("lineups") != null && match.optJSONArray("lineups").length() > 0;
                boolean started = now >= ko - 2 * MIN;
                boolean due;
                if (live || started) due = now - mAt >= 150_000L;
                else if (!hasLineup) due = now - mAt >= 9 * MIN;
                else due = now - mAt >= 30 * MIN;
                if (force && now - mAt >= MIN) due = true;
                if (due) {
                    JSONArray r = api(p, key, "/fixtures?id=" + id).optJSONArray("response");
                    if (r != null && r.length() > 0) {
                        JSONObject m = slimMatch(r.optJSONObject(0));
                        notifyMatch(c, p, match, m, team);
                        put(d, "match", m);
                        put(d, "matchAt", now);
                        if (DONE.contains(m.optString("status"))) {
                            put(d, "needsRefresh", true);
                            put(d, "standingsAt", 0);
                            put(d, "lastMatch", m);
                        }
                    }
                }
            }
            // Absents pour ce match : toutes les 6 h, toutes les heures le jour du match
            long injAt = d.optLong("injAt", 0);
            boolean sameFixture = d.optLong("injFixture", 0) == id;
            long every = window ? HOUR : 6 * HOUR;
            if ((!sameFixture || now - injAt > every || (force && now - injAt > HOUR)) && (!low || window)) {
                JSONArray r = api(p, key, "/injuries?fixture=" + id).optJSONArray("response");
                JSONArray out = new JSONArray();
                for (int i = 0; r != null && i < r.length(); i++) {
                    JSONObject it = r.optJSONObject(i);
                    JSONObject pl = it.optJSONObject("player");
                    JSONObject tm = it.optJSONObject("team");
                    if (pl == null) continue;
                    JSONObject o = new JSONObject();
                    put(o, "id", pl.optInt("id"));
                    put(o, "name", pl.optString("name"));
                    put(o, "type", pl.optString("type"));
                    put(o, "reason", pl.optString("reason"));
                    put(o, "team", tm != null ? tm.optInt("id") : 0);
                    out.put(o);
                }
                put(d, "injuries", out);
                put(d, "injFixture", id);
                put(d, "injAt", now);
            }
        }

        if (low) return; // on garde le reste du quota pour les matchs

        // 4. Classement de Ligue 1 : toutes les 6 h, et après chaque match
        if (now - d.optLong("standingsAt", 0) > 6 * HOUR) {
            JSONArray r = api(p, key, "/standings?league=" + LIGUE1 + "&season=" + season).optJSONArray("response");
            JSONArray out = new JSONArray();
            if (r != null && r.length() > 0) {
                JSONObject lg = r.optJSONObject(0).optJSONObject("league");
                JSONArray groups = lg != null ? lg.optJSONArray("standings") : null;
                JSONArray rows = groups != null && groups.length() > 0 ? groups.optJSONArray(0) : null;
                for (int i = 0; rows != null && i < rows.length(); i++) {
                    JSONObject s = rows.optJSONObject(i);
                    JSONObject t = s.optJSONObject("team");
                    JSONObject all = s.optJSONObject("all");
                    JSONObject goals = all != null ? all.optJSONObject("goals") : null;
                    JSONObject o = new JSONObject();
                    put(o, "rank", s.optInt("rank"));
                    put(o, "id", t != null ? t.optInt("id") : 0);
                    put(o, "name", t != null ? t.optString("name") : "");
                    put(o, "pts", s.optInt("points"));
                    put(o, "diff", s.optInt("goalsDiff"));
                    put(o, "form", s.optString("form", ""));
                    put(o, "desc", s.isNull("description") ? "" : s.optString("description"));
                    put(o, "played", all != null ? all.optInt("played") : 0);
                    put(o, "win", all != null ? all.optInt("win") : 0);
                    put(o, "draw", all != null ? all.optInt("draw") : 0);
                    put(o, "lose", all != null ? all.optInt("lose") : 0);
                    put(o, "gf", goals != null ? goals.optInt("for") : 0);
                    put(o, "ga", goals != null ? goals.optInt("against") : 0);
                    out.put(o);
                }
            }
            put(d, "standings", out);
            put(d, "standingsAt", now);
        }

        // 5. Effectif : une fois par jour
        if (now - d.optLong("squadAt", 0) > 24 * HOUR) {
            JSONArray r = api(p, key, "/players/squads?team=" + team).optJSONArray("response");
            JSONArray out = new JSONArray();
            JSONArray players = r != null && r.length() > 0 ? r.optJSONObject(0).optJSONArray("players") : null;
            for (int i = 0; players != null && i < players.length(); i++) {
                JSONObject pl = players.optJSONObject(i);
                JSONObject o = new JSONObject();
                put(o, "id", pl.optInt("id"));
                put(o, "name", pl.optString("name"));
                put(o, "age", pl.isNull("age") ? 0 : pl.optInt("age"));
                put(o, "number", pl.isNull("number") ? 0 : pl.optInt("number"));
                put(o, "pos", pl.optString("position"));
                out.put(o);
            }
            put(d, "squad", out);
            put(d, "squadAt", now);
        }

        // 6. Meilleurs buteurs de Ligue 1 : une fois par jour
        if (now - d.optLong("scorersAt", 0) > 24 * HOUR) {
            JSONArray r = api(p, key, "/players/topscorers?league=" + LIGUE1 + "&season=" + season).optJSONArray("response");
            JSONArray out = new JSONArray();
            for (int i = 0; r != null && i < r.length() && i < 15; i++) {
                JSONObject it = r.optJSONObject(i);
                JSONObject pl = it.optJSONObject("player");
                JSONArray st = it.optJSONArray("statistics");
                JSONObject s0 = st != null && st.length() > 0 ? st.optJSONObject(0) : null;
                JSONObject tm = s0 != null ? s0.optJSONObject("team") : null;
                JSONObject g = s0 != null ? s0.optJSONObject("goals") : null;
                JSONObject o = new JSONObject();
                put(o, "name", pl != null ? pl.optString("name") : "");
                put(o, "team", tm != null ? tm.optString("name") : "");
                put(o, "teamId", tm != null ? tm.optInt("id") : 0);
                put(o, "goals", g != null && !g.isNull("total") ? g.optInt("total") : 0);
                put(o, "assists", g != null && !g.isNull("assists") ? g.optInt("assists") : 0);
                out.put(o);
            }
            put(d, "scorers", out);
            put(d, "scorersAt", now);
        }
    }

    /** Prochain match à suivre : celui en cours, sinon le premier à venir (on garde un match commencé il y a moins de 3 h). */
    static JSONObject nextFixture(JSONObject d, long now) {
        JSONArray fx = d.optJSONArray("fixtures");
        JSONObject m = d.optJSONObject("match");
        for (int i = 0; fx != null && i < fx.length(); i++) {
            JSONObject f = fx.optJSONObject(i);
            String st = f.optString("status");
            if (m != null && m.optLong("id") == f.optLong("id")) st = m.optString("status");
            if (LIVE.contains(st)) return f;
            if (UPCOMING.contains(st) && f.optLong("ts") * 1000 > now - 3 * HOUR) return f;
        }
        return null;
    }

    static JSONObject slimFixture(JSONObject it) {
        JSONObject o = new JSONObject();
        JSONObject f = it.optJSONObject("fixture");
        JSONObject l = it.optJSONObject("league");
        JSONObject t = it.optJSONObject("teams");
        JSONObject g = it.optJSONObject("goals");
        if (f != null) {
            put(o, "id", f.optLong("id"));
            put(o, "ts", f.optLong("timestamp"));
            JSONObject st = f.optJSONObject("status");
            put(o, "status", st != null ? st.optString("short") : "");
            put(o, "elapsed", st != null && !st.isNull("elapsed") ? st.optInt("elapsed") : -1);
            JSONObject v = f.optJSONObject("venue");
            put(o, "venue", v != null && !v.isNull("name") ? v.optString("name") : "");
            put(o, "city", v != null && !v.isNull("city") ? v.optString("city") : "");
        }
        if (l != null) {
            put(o, "league", l.optString("name"));
            put(o, "leagueId", l.optInt("id"));
            put(o, "round", l.isNull("round") ? "" : l.optString("round"));
        }
        if (t != null) {
            put(o, "home", team(t.optJSONObject("home")));
            put(o, "away", team(t.optJSONObject("away")));
        }
        put(o, "gh", g != null && !g.isNull("home") ? g.optInt("home") : -1);
        put(o, "ga", g != null && !g.isNull("away") ? g.optInt("away") : -1);
        return o;
    }

    static JSONObject team(JSONObject t) {
        JSONObject o = new JSONObject();
        if (t == null) return o;
        put(o, "id", t.optInt("id"));
        put(o, "name", t.optString("name"));
        return o;
    }

    static JSONObject slimMatch(JSONObject it) {
        JSONObject o = slimFixture(it);
        JSONArray ev = it.optJSONArray("events");
        JSONArray evOut = new JSONArray();
        for (int i = 0; ev != null && i < ev.length(); i++) {
            JSONObject e = ev.optJSONObject(i);
            JSONObject time = e.optJSONObject("time");
            JSONObject tm = e.optJSONObject("team");
            JSONObject pl = e.optJSONObject("player");
            JSONObject as = e.optJSONObject("assist");
            JSONObject x = new JSONObject();
            put(x, "min", time != null && !time.isNull("elapsed") ? time.optInt("elapsed") : 0);
            put(x, "extra", time != null && !time.isNull("extra") ? time.optInt("extra") : 0);
            put(x, "team", tm != null ? tm.optInt("id") : 0);
            put(x, "player", pl != null && !pl.isNull("name") ? pl.optString("name") : "");
            put(x, "assist", as != null && !as.isNull("name") ? as.optString("name") : "");
            put(x, "type", e.optString("type"));
            put(x, "detail", e.isNull("detail") ? "" : e.optString("detail"));
            evOut.put(x);
        }
        put(o, "events", evOut);
        JSONArray lu = it.optJSONArray("lineups");
        JSONArray luOut = new JSONArray();
        for (int i = 0; lu != null && i < lu.length(); i++) {
            JSONObject l = lu.optJSONObject(i);
            JSONObject x = new JSONObject();
            JSONObject tm = l.optJSONObject("team");
            JSONObject coach = l.optJSONObject("coach");
            put(x, "team", tm != null ? tm.optInt("id") : 0);
            put(x, "formation", l.isNull("formation") ? "" : l.optString("formation"));
            put(x, "coach", coach != null && !coach.isNull("name") ? coach.optString("name") : "");
            put(x, "xi", players(l.optJSONArray("startXI")));
            put(x, "subs", players(l.optJSONArray("substitutes")));
            luOut.put(x);
        }
        put(o, "lineups", luOut);
        return o;
    }

    static JSONArray players(JSONArray a) {
        JSONArray out = new JSONArray();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject pl = a.optJSONObject(i).optJSONObject("player");
            if (pl == null) continue;
            JSONObject x = new JSONObject();
            put(x, "n", pl.isNull("number") ? 0 : pl.optInt("number"));
            put(x, "name", pl.optString("name"));
            put(x, "pos", pl.isNull("pos") ? "" : pl.optString("pos"));
            out.put(x);
        }
        return out;
    }

    /* ------------------------------------------------------------------ actus */

    static final String[] NEWS_QUERIES = { "LOSC", "\"Lille OSC\" OR \"les Dogues\"" };

    static void fetchNews(Context c, JSONObject d, SharedPreferences p) throws Exception {
        JSONArray old = d.optJSONArray("news");
        Set<String> seen = new HashSet<>();
        for (int i = 0; old != null && i < old.length(); i++) seen.add(old.optJSONObject(i).optString("id"));
        boolean first = old == null;

        List<JSONObject> items = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int ok = 0;
        for (String q : NEWS_QUERIES) {
            String url = "https://news.google.com/rss/search?q=" + URLEncoder.encode(q, "UTF-8") + "&hl=fr&gl=FR&ceid=FR:fr";
            try (Response res = http().newCall(new Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android) Dogues").build()).execute()) {
                if (!res.isSuccessful() || res.body() == null) continue;
                for (JSONObject it : parseRss(res.body().byteStream())) {
                    if (ids.add(it.optString("id"))) items.add(it);
                }
                ok++;
            }
        }
        if (ok == 0) throw new IOException("aucun flux");
        Collections.sort(items, (x, y) -> Long.compare(y.optLong("pub"), x.optLong("pub")));
        JSONArray out = new JSONArray();
        for (int i = 0; i < items.size() && i < 80; i++) out.put(items.get(i));
        put(d, "news", out);

        if (first || !p.getBoolean("notifNews", true)) return;
        long now = System.currentTimeMillis();
        String day = utcDay();
        int count = day.equals(p.getString("newsDay", "")) ? p.getInt("newsCount", 0) : 0;
        int sent = 0;
        for (JSONObject it : items) {
            if (seen.contains(it.optString("id"))) continue;
            String tag = it.optString("tag");
            if (tag.isEmpty() || "Compo".equals(tag)) continue;
            if (now - it.optLong("pub") > 3 * HOUR) continue;
            if (count >= 8 || sent >= 3) break;
            notify(c, "news", it.optString("id").hashCode(), tag + " · " + it.optString("source"), it.optString("title"));
            count++;
            sent++;
        }
        p.edit().putString("newsDay", day).putInt("newsCount", count).apply();
    }

    static List<JSONObject> parseRss(InputStream in) throws Exception {
        XmlPullParser x = Xml.newPullParser();
        x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        x.setInput(in, null);
        List<JSONObject> out = new ArrayList<>();
        SimpleDateFormat rfc = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        String title = null, link = null, pub = null, source = null;
        boolean inItem = false;
        for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String n = x.getName();
                if ("item".equals(n)) { inItem = true; title = link = pub = source = null; }
                else if (inItem && "title".equals(n)) title = x.nextText();
                else if (inItem && "link".equals(n)) link = x.nextText();
                else if (inItem && "pubDate".equals(n)) pub = x.nextText();
                else if (inItem && "source".equals(n)) source = x.nextText();
            } else if (ev == XmlPullParser.END_TAG && "item".equals(x.getName())) {
                inItem = false;
                if (title == null || link == null) continue;
                String t = title.trim();
                String src = source != null ? source.trim() : "";
                if (!src.isEmpty() && t.endsWith(" - " + src)) t = t.substring(0, t.length() - src.length() - 3).trim();
                long ts = System.currentTimeMillis();
                try { if (pub != null) ts = rfc.parse(pub.trim()).getTime(); } catch (Exception ignored) { }
                JSONObject o = new JSONObject();
                put(o, "id", Integer.toHexString(normalize(t).hashCode()));
                put(o, "title", t);
                put(o, "link", link.trim());
                put(o, "source", src);
                put(o, "pub", ts);
                put(o, "tag", tag(t));
                out.add(o);
            }
        }
        return out;
    }

    static String normalize(String t) {
        return t.toLowerCase(Locale.FRENCH).replaceAll("[^a-z0-9àâäéèêëîïôöùûüç]+", " ").trim();
    }

    static String tag(String title) {
        String t = title.toLowerCase(Locale.FRENCH);
        if (t.matches(".*(transfert|mercato|recrue|signe |signé|prolong|prêt |offre|départ|vendu|rachat).*")) return "Mercato";
        if (t.matches(".*(bless|forfait|indisponible|opér|rechute|infirmerie|incertain|absent|touché).*")) return "Infirmerie";
        if (t.matches(".*(suspen|carton rouge|expuls).*")) return "Suspension";
        if (t.matches(".*(compo|composition|groupe pour|onze de départ).*")) return "Compo";
        if (t.matches(".*(officiel|communiqué).*")) return "Officiel";
        return "";
    }

    /* ------------------------------------------------------------------ notifications */

    static void notifyMatch(Context c, SharedPreferences p, JSONObject old, JSONObject neu, int team) {
        String score = name(neu, "home") + " " + goals(neu, "gh") + " – " + goals(neu, "ga") + " " + name(neu, "away");
        long id = neu.optLong("id");

        // Compo officielle
        if (p.getBoolean("notifLineup", true)) {
            boolean had = old != null && old.optJSONArray("lineups") != null && old.optJSONArray("lineups").length() > 0;
            JSONArray lu = neu.optJSONArray("lineups");
            if (!had && lu != null && lu.length() > 0 && !p.getBoolean("lineupSent" + id, false)) {
                for (int i = 0; i < lu.length(); i++) {
                    JSONObject l = lu.optJSONObject(i);
                    if (l.optInt("team") != team) continue;
                    StringBuilder b = new StringBuilder();
                    JSONArray xi = l.optJSONArray("xi");
                    for (int k = 0; xi != null && k < xi.length(); k++) {
                        if (k > 0) b.append(", ");
                        b.append(xi.optJSONObject(k).optString("name"));
                    }
                    String f = l.optString("formation");
                    notify(c, "match", (int) (id * 10 + 1), "Compo officielle" + (f.isEmpty() ? "" : " (" + f + ")"), b.toString());
                    p.edit().putBoolean("lineupSent" + id, true).apply();
                }
            }
        }

        if (!p.getBoolean("notifGoals", true)) return;

        // Buts (on ne notifie pas l'historique au premier relevé)
        if (old != null) {
            int before = countGoals(old), after = countGoals(neu);
            int scoreBefore = Math.max(0, old.optInt("gh")) + Math.max(0, old.optInt("ga"));
            int scoreAfter = Math.max(0, neu.optInt("gh")) + Math.max(0, neu.optInt("ga"));
            if (after > before || scoreAfter > scoreBefore) {
                JSONArray ev = neu.optJSONArray("events");
                int seen = 0;
                boolean any = false;
                for (int i = 0; ev != null && i < ev.length(); i++) {
                    JSONObject e = ev.optJSONObject(i);
                    if (!isGoal(e)) continue;
                    seen++;
                    if (seen <= before) continue;
                    boolean ours = e.optInt("team") == team;
                    String who = e.optString("player");
                    String min = e.optInt("min") + (e.optInt("extra") > 0 ? "+" + e.optInt("extra") : "") + "'";
                    String kind = "Own Goal".equals(e.optString("detail")) ? " (csc)" : "Penalty".equals(e.optString("detail")) ? " (pen.)" : "";
                    notify(c, "match", (int) (id * 100 + 10 + seen), (ours ? "BUT pour Lille ! " : "But adverse · ") + score, min + " " + who + kind);
                    any = true;
                }
                if (!any && scoreAfter > scoreBefore) notify(c, "match", (int) (id * 100 + 10 + scoreAfter), "But ! " + score, "");
            }
        }

        // Score final
        boolean wasDone = old != null && DONE.contains(old.optString("status"));
        if (!wasDone && DONE.contains(neu.optString("status")) && !p.getBoolean("ftSent" + id, false)) {
            notify(c, "match", (int) (id * 10 + 3), "Score final", score);
            p.edit().putBoolean("ftSent" + id, true).apply();
        }
    }

    static boolean isGoal(JSONObject e) {
        return "Goal".equals(e.optString("type")) && !"Missed Penalty".equals(e.optString("detail"));
    }

    static int countGoals(JSONObject m) {
        JSONArray ev = m.optJSONArray("events");
        int n = 0;
        for (int i = 0; ev != null && i < ev.length(); i++) if (isGoal(ev.optJSONObject(i))) n++;
        return n;
    }

    static String name(JSONObject m, String side) {
        JSONObject t = m.optJSONObject(side);
        return t != null ? t.optString("name") : "";
    }

    static String goals(JSONObject m, String k) {
        int g = m.optInt(k, -1);
        return g < 0 ? "0" : String.valueOf(g);
    }

    static void notify(Context c, String channel, int id, String title, String text) {
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel("match") == null) {
                nm.createNotificationChannel(new NotificationChannel("match", "Matchs (buts, compo, score final)", NotificationManager.IMPORTANCE_HIGH));
                nm.createNotificationChannel(new NotificationChannel("news", "Grosses actus", NotificationManager.IMPORTANCE_DEFAULT));
            }
            b = new Notification.Builder(c, channel);
        } else {
            b = new Notification.Builder(c);
            b.setPriority("match".equals(channel) ? Notification.PRIORITY_HIGH : Notification.PRIORITY_DEFAULT);
            b.setDefaults(Notification.DEFAULT_ALL);
        }
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setSmallIcon(R.drawable.ic_stat_dogues)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setColor(0xFFE2001A)
                .setAutoCancel(true)
                .setContentIntent(pi);
        nm.notify(id, b.build());
    }

    /* ------------------------------------------------------------------ utilitaire */

    static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (JSONException ignored) { }
    }
}
