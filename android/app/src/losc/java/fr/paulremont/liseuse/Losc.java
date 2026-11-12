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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Moteur de données de l'appli Dogues (appli de fan non officielle).
 * Tout est récupéré ici, côté Android, pour que les mises à jour et les notifications
 * marchent aussi quand l'appli est fermée. L'interface lit juste le fichier losc.json.
 *
 * Sources (gratuites, sans clé) :
 *  - actus : flux RSS de Google Actualités ;
 *  - matchs, direct, compos, classement, effectif : données publiques d'ESPN.
 */
final class Losc {
    private Losc() {}

    static final String PREFS = "losc";
    static final String ESPN = "https://site.api.espn.com/apis/site/v2/sports/soccer/";
    static final String ESPN_STANDINGS = "https://site.api.espn.com/apis/v2/sports/soccer/";
    /** Compétitions suivies : championnat, coupes d'Europe, coupe de France. */
    static final String[][] LEAGUES = {
            {"fra.1", "Ligue 1"},
            {"uefa.champions", "Ligue des champions"},
            {"uefa.europa", "Ligue Europa"},
            {"uefa.europa.conf", "Ligue Conférence"},
            {"fra.coupe_de_france", "Coupe de France"},
    };
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
                    .readTimeout(25, TimeUnit.SECONDS)
                    .build();
        }
        return http;
    }

    private static Set<String> set(String... v) {
        Set<String> s = new HashSet<>();
        Collections.addAll(s, v);
        return s;
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

            if (force || now - d.optLong("newsAt", 0) > 9 * MIN) {
                try {
                    fetchNews(c, d, p);
                    put(d, "newsAt", now);
                    errors.remove("news");
                } catch (Exception e) {
                    put(errors, "news", "Actus indisponibles pour le moment.");
                }
            }

            try {
                footData(c, d, p, now, force);
                errors.remove("api");
            } catch (Exception e) {
                put(errors, "api", "Impossible de joindre les données des matchs pour l'instant. Nouvel essai dans quelques minutes.");
            }

            delay = nextDelay(d, now);
            put(d, "errors", errors);
            put(d, "updatedAt", now);
            put(d, "nextAt", now + delay);
            save(c, d);
        }
        Listener l = listener;
        if (l != null) l.onUpdate();
        return delay;
    }

    static long nextDelay(JSONObject d, long now) {
        JSONObject m = d.optJSONObject("match");
        if (m != null && LIVE.contains(m.optString("status"))) return 2 * MIN;
        JSONObject next = nextFixture(d, now);
        if (next != null) {
            long ko = next.optLong("ts") * 1000;
            if (now >= ko - 90 * MIN && now <= ko + 150 * MIN) return 4 * MIN;
        }
        return 10 * MIN;
    }

    /* ------------------------------------------------------------------ ESPN */

    static JSONObject get(String url) throws IOException, JSONException {
        Request r = new Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android) Dogues").build();
        try (Response res = http().newCall(r).execute()) {
            if (!res.isSuccessful() || res.body() == null) throw new IOException("HTTP " + res.code());
            return new JSONObject(res.body().string());
        }
    }

    static String day(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(ms);
    }

    /** Début de saison : 1er juillet. */
    static long seasonStart(long now) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris"));
        cal.setTimeInMillis(now);
        int y = cal.get(Calendar.YEAR);
        if (cal.get(Calendar.MONTH) < Calendar.JULY) y--;
        cal.clear();
        cal.set(y, Calendar.JULY, 1);
        return cal.getTimeInMillis();
    }

    static void footData(Context c, JSONObject d, SharedPreferences p, long now, boolean force) throws Exception {
        // 1. Identifiant ESPN du club (une fois)
        String team = p.getString("espnTeam", "");
        if (team.isEmpty()) {
            JSONObject o = get(ESPN + "fra.1/teams");
            JSONArray teams = o.optJSONArray("sports") != null
                    ? o.optJSONArray("sports").optJSONObject(0).optJSONArray("leagues").optJSONObject(0).optJSONArray("teams") : null;
            for (int i = 0; teams != null && i < teams.length(); i++) {
                JSONObject t = teams.optJSONObject(i).optJSONObject("team");
                if (t == null) continue;
                String n = t.optString("displayName") + " " + t.optString("shortDisplayName") + " " + t.optString("name");
                if (n.contains("Lille") || "LIL".equals(t.optString("abbreviation"))) {
                    team = t.optString("id");
                    break;
                }
            }
            if (team.isEmpty()) throw new IOException("club introuvable");
            p.edit().putString("espnTeam", team).apply();
        }
        put(d, "team", Integer.parseInt(team));
        put(d, "source", "espn");

        // 2. Calendrier toutes compétitions : toutes les 3 h, ou après un match
        long fxAt = d.optLong("fixturesAt", 0);
        if (now - fxAt > 3 * HOUR || d.optBoolean("needsRefresh") || (force && now - fxAt > 20 * MIN)) {
            String range = day(seasonStart(now)) + "-" + day(now + 120L * 24 * HOUR);
            List<JSONObject> list = new ArrayList<>();
            int ok = 0;
            for (String[] lg : LEAGUES) {
                try {
                    JSONObject o = get(ESPN + lg[0] + "/scoreboard?dates=" + range + "&limit=1000");
                    ok++;
                    JSONArray ev = o.optJSONArray("events");
                    for (int i = 0; ev != null && i < ev.length(); i++) {
                        JSONObject f = slimEvent(ev.optJSONObject(i), lg);
                        if (f != null && involves(f, team)) list.add(f);
                    }
                } catch (Exception ignored) {
                    // une compétition indisponible ne bloque pas les autres
                }
            }
            if (ok == 0) throw new IOException("calendrier indisponible");
            Collections.sort(list, (x, y) -> Long.compare(x.optLong("ts"), y.optLong("ts")));
            put(d, "fixtures", new JSONArray(list));
            put(d, "fixturesAt", now);
            put(d, "needsRefresh", false);
        }

        // 3. Prochain match : direct, compo, fil du match
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
            boolean window = now >= ko - 100 * MIN && now <= ko + 150 * MIN;
            if (window || live) {
                long mAt = d.optLong("matchAt", 0);
                boolean due = (live || now >= ko - 2 * MIN) ? now - mAt >= 100_000L : now - mAt >= 8 * MIN;
                if (force && now - mAt >= 30_000L) due = true;
                if (due) {
                    JSONObject m = summary(next.optString("slug"), id, next);
                    if (m != null) {
                        notifyMatch(c, p, match, m, Integer.parseInt(team));
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
        }

        // 4. Classement de Ligue 1 : toutes les 2 h, et après chaque match
        if (now - d.optLong("standingsAt", 0) > 2 * HOUR) {
            JSONObject o = get(ESPN_STANDINGS + "fra.1/standings");
            JSONArray children = o.optJSONArray("children");
            JSONObject st = children != null && children.length() > 0 ? children.optJSONObject(0).optJSONObject("standings") : o.optJSONObject("standings");
            JSONArray entries = st != null ? st.optJSONArray("entries") : null;
            List<JSONObject> rows = new ArrayList<>();
            for (int i = 0; entries != null && i < entries.length(); i++) {
                JSONObject e = entries.optJSONObject(i);
                JSONObject t = e.optJSONObject("team");
                Map<String, Double> s = stats(e.optJSONArray("stats"));
                JSONObject note = e.optJSONObject("note");
                JSONObject r = new JSONObject();
                put(r, "id", t != null ? t.optInt("id") : 0);
                put(r, "name", t != null ? t.optString("displayName") : "");
                put(r, "rank", (int) val(s, "rank", i + 1));
                put(r, "pts", (int) val(s, "points", 0));
                put(r, "played", (int) val(s, "gamesPlayed", 0));
                put(r, "win", (int) val(s, "wins", 0));
                put(r, "draw", (int) val(s, "ties", 0));
                put(r, "lose", (int) val(s, "losses", 0));
                put(r, "gf", (int) val(s, "pointsFor", 0));
                put(r, "ga", (int) val(s, "pointsAgainst", 0));
                put(r, "diff", (int) val(s, "pointDifferential", val(s, "pointsFor", 0) - val(s, "pointsAgainst", 0)));
                put(r, "desc", note != null ? note.optString("description") : "");
                put(r, "color", note != null ? note.optString("color") : "");
                put(r, "form", "");
                rows.add(r);
            }
            Collections.sort(rows, (x, y) -> Integer.compare(x.optInt("rank"), y.optInt("rank")));
            put(d, "standings", new JSONArray(rows));
            put(d, "standingsAt", now);
        }

        // 5. Effectif : une fois par jour
        if (now - d.optLong("squadAt", 0) > 24 * HOUR) {
            JSONObject o = get(ESPN + "fra.1/teams/" + team + "/roster");
            JSONArray out = new JSONArray();
            JSONArray ath = o.optJSONArray("athletes");
            for (int i = 0; ath != null && i < ath.length(); i++) {
                JSONObject a = ath.optJSONObject(i);
                if (a.has("items")) { // format groupé par poste
                    JSONArray items = a.optJSONArray("items");
                    for (int k = 0; items != null && k < items.length(); k++) out.put(slimPlayer(items.optJSONObject(k)));
                } else {
                    out.put(slimPlayer(a));
                }
            }
            put(d, "squad", out);
            put(d, "squadAt", now);
        }

        // 6. Buteurs du LOSC : calculés à partir des matchs terminés (5 nouveaux matchs max par passage)
        scorers(d, team);
    }

    static boolean involves(JSONObject f, String team) {
        JSONObject h = f.optJSONObject("home"), a = f.optJSONObject("away");
        return (h != null && team.equals(String.valueOf(h.optInt("id")))) || (a != null && team.equals(String.valueOf(a.optInt("id"))));
    }

    static Map<String, Double> stats(JSONArray a) {
        Map<String, Double> m = new HashMap<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject s = a.optJSONObject(i);
            String n = s.optString("name", s.optString("type"));
            if (s.has("value") && !s.isNull("value")) m.put(n, s.optDouble("value"));
        }
        return m;
    }

    static double val(Map<String, Double> m, String k, double def) {
        Double v = m.get(k);
        if (v == null) v = m.get(k.toLowerCase(Locale.US));
        return v == null || v.isNaN() ? def : v;
    }

    static JSONObject slimPlayer(JSONObject a) {
        JSONObject o = new JSONObject();
        JSONObject pos = a.optJSONObject("position");
        String pn = pos != null ? (pos.optString("name") + " " + pos.optString("displayName") + " " + pos.optString("abbreviation")) : "";
        String mapped = pn.contains("Goal") || pn.trim().equals("G") ? "Goalkeeper"
                : pn.contains("Def") || pn.trim().equals("D") ? "Defender"
                : pn.contains("Mid") || pn.trim().equals("M") ? "Midfielder"
                : pn.contains("Forw") || pn.contains("Attack") || pn.trim().equals("F") ? "Attacker" : "Midfielder";
        put(o, "id", a.optString("id"));
        put(o, "name", a.optString("displayName", a.optString("fullName")));
        put(o, "age", a.optInt("age", 0));
        int jersey = 0;
        try { jersey = Integer.parseInt(a.optString("jersey", "0").trim()); } catch (NumberFormatException ignored) { }
        put(o, "number", jersey);
        put(o, "pos", mapped);
        return o;
    }

    /* ---- conversion d'un match ESPN dans le format de l'interface ---- */

    static long parseDate(String s) {
        String[] fmts = {"yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX"};
        for (String f : fmts) {
            try {
                SimpleDateFormat p = new SimpleDateFormat(f, Locale.US);
                p.setTimeZone(TimeZone.getTimeZone("UTC"));
                return p.parse(s).getTime();
            } catch (Exception ignored) { }
        }
        return 0;
    }

    /** Statut ESPN → codes courts (NS, 1H, HT, 2H, ET, P, FT, AET, PEN, PST, CANC…). */
    static String status(JSONObject st) {
        JSONObject t = st != null ? st.optJSONObject("type") : null;
        if (t == null) return "NS";
        String name = t.optString("name"), state = t.optString("state"), desc = (t.optString("description") + " " + t.optString("detail")).toLowerCase(Locale.US);
        if (name.contains("POSTPONED") || desc.contains("postponed")) return "PST";
        if (name.contains("CANCEL") || desc.contains("cancel")) return "CANC";
        if (name.contains("ABANDON") || desc.contains("abandon")) return "ABD";
        if (name.contains("SUSPEND")) return "SUSP";
        if ("pre".equals(state)) return "NS";
        if ("post".equals(state)) {
            if (name.contains("PEN") || desc.contains("pen")) return "PEN";
            if (name.contains("AET") || desc.contains("aet") || desc.contains("extra")) return "AET";
            return "FT";
        }
        if (name.contains("HALFTIME") || desc.contains("halftime") || desc.equals("ht")) return "HT";
        if (name.contains("SHOOTOUT") || desc.contains("shootout")) return "P";
        int period = st.optInt("period", 1);
        if (period >= 3 || name.contains("EXTRA")) return "ET";
        return period >= 2 ? "2H" : "1H";
    }

    static int minute(JSONObject st) {
        if (st == null) return -1;
        Matcher m = Pattern.compile("(\\d+)").matcher(st.optString("displayClock", ""));
        if (m.find()) return Integer.parseInt(m.group(1));
        double clock = st.optDouble("clock", -1);
        return clock >= 0 ? (int) (clock / 60) : -1;
    }

    static JSONObject slimEvent(JSONObject ev, String[] lg) {
        if (ev == null) return null;
        JSONArray comps = ev.optJSONArray("competitions");
        JSONObject comp = comps != null && comps.length() > 0 ? comps.optJSONObject(0) : null;
        if (comp == null) return null;
        JSONObject st = comp.optJSONObject("status") != null ? comp.optJSONObject("status") : ev.optJSONObject("status");
        JSONObject o = new JSONObject();
        put(o, "id", Long.parseLong(ev.optString("id", "0").replaceAll("\\D", "0")));
        put(o, "slug", lg[0]);
        put(o, "league", lg[1]);
        put(o, "round", "");
        put(o, "ts", parseDate(comp.optString("date", ev.optString("date"))) / 1000);
        String s = status(st);
        put(o, "status", s);
        put(o, "elapsed", LIVE.contains(s) ? minute(st) : -1);
        JSONObject venue = comp.optJSONObject("venue");
        put(o, "venue", venue != null ? venue.optString("fullName") : "");
        JSONObject addr = venue != null ? venue.optJSONObject("address") : null;
        put(o, "city", addr != null ? addr.optString("city") : "");
        JSONArray cs = comp.optJSONArray("competitors");
        for (int i = 0; cs != null && i < cs.length(); i++) {
            JSONObject cpt = cs.optJSONObject(i);
            JSONObject t = cpt.optJSONObject("team");
            JSONObject team = new JSONObject();
            put(team, "id", t != null ? t.optInt("id") : 0);
            put(team, "name", t != null ? t.optString("shortDisplayName", t.optString("displayName")) : "");
            boolean home = "home".equals(cpt.optString("homeAway"));
            put(o, home ? "home" : "away", team);
            int g = -1;
            if (!"NS".equals(s) && !"PST".equals(s) && !"CANC".equals(s)) {
                try { g = Integer.parseInt(cpt.optString("score", "").trim()); } catch (NumberFormatException ignored) { }
            }
            put(o, home ? "gh" : "ga", g);
        }
        if (!o.has("home") || !o.has("away")) return null;
        return o;
    }

    /** Détail d'un match : score, minute, fil du match, compos. */
    static JSONObject summary(String slug, long id, JSONObject base) throws Exception {
        JSONObject o = get(ESPN + (slug.isEmpty() ? "fra.1" : slug) + "/summary?event=" + id);
        JSONObject header = o.optJSONObject("header");
        JSONArray comps = header != null ? header.optJSONArray("competitions") : null;
        JSONObject comp = comps != null && comps.length() > 0 ? comps.optJSONObject(0) : null;
        JSONObject m = new JSONObject(base.toString());
        if (comp != null) {
            JSONObject ev = new JSONObject();
            put(ev, "id", String.valueOf(id));
            put(ev, "competitions", new JSONArray().put(comp));
            String[] lg = {base.optString("slug"), base.optString("league")};
            JSONObject s = slimEvent(ev, lg);
            if (s != null) {
                for (String k : new String[]{"status", "elapsed", "gh", "ga", "home", "away"}) put(m, k, s.opt(k));
                if (s.optLong("ts") > 0) put(m, "ts", s.optLong("ts"));
            }
        }
        // Fil du match
        JSONArray ke = o.optJSONArray("keyEvents");
        JSONArray evOut = new JSONArray();
        Pattern clk = Pattern.compile("(\\d+)'?\\s*(?:\\+\\s*(\\d+))?");
        for (int i = 0; ke != null && i < ke.length(); i++) {
            JSONObject e = ke.optJSONObject(i);
            JSONObject type = e.optJSONObject("type");
            String txt = type != null ? type.optString("text", type.optString("type")) : "";
            String lower = txt.toLowerCase(Locale.US);
            String kind, detail;
            if (lower.contains("own goal")) { kind = "Goal"; detail = "Own Goal"; }
            else if (lower.contains("penalty") && (lower.contains("miss") || lower.contains("saved"))) { kind = "Goal"; detail = "Missed Penalty"; }
            else if (lower.contains("penalty") && (lower.contains("scored") || lower.contains("goal"))) { kind = "Goal"; detail = "Penalty"; }
            else if (lower.startsWith("goal") || e.optBoolean("scoringPlay")) { kind = "Goal"; detail = "Normal Goal"; }
            else if (lower.contains("red card") || lower.contains("second yellow")) { kind = "Card"; detail = "Red Card"; }
            else if (lower.contains("yellow card")) { kind = "Card"; detail = "Yellow Card"; }
            else if (lower.contains("substitution")) { kind = "subst"; detail = "Substitution"; }
            else if (lower.contains("var")) { kind = "Var"; detail = ""; }
            else continue;
            JSONObject clock = e.optJSONObject("clock");
            int min = 0, extra = 0;
            Matcher mm = clk.matcher(clock != null ? clock.optString("displayValue") : "");
            if (mm.find()) {
                min = Integer.parseInt(mm.group(1));
                if (mm.group(2) != null) extra = Integer.parseInt(mm.group(2));
            }
            JSONArray parts = e.optJSONArray("participants");
            String p0 = athlete(parts, 0), p1 = athlete(parts, 1);
            JSONObject tm = e.optJSONObject("team");
            JSONObject x = new JSONObject();
            put(x, "min", min);
            put(x, "extra", extra);
            put(x, "team", tm != null ? tm.optInt("id") : 0);
            put(x, "player", p0);
            put(x, "assist", p1);
            put(x, "type", kind);
            put(x, "detail", detail);
            evOut.put(x);
        }
        put(m, "events", evOut);
        // Compos
        JSONArray rosters = o.optJSONArray("rosters");
        JSONArray luOut = new JSONArray();
        for (int i = 0; rosters != null && i < rosters.length(); i++) {
            JSONObject r = rosters.optJSONObject(i);
            JSONArray roster = r.optJSONArray("roster");
            if (roster == null || roster.length() == 0) continue;
            JSONObject tm = r.optJSONObject("team");
            String formation = r.optString("formation", "");
            List<JSONObject> xi = new ArrayList<>();
            JSONArray subs = new JSONArray();
            for (int k = 0; k < roster.length(); k++) {
                JSONObject pl = roster.optJSONObject(k);
                JSONObject a = pl.optJSONObject("athlete");
                JSONObject pos = pl.optJSONObject("position");
                JSONObject x = new JSONObject();
                int n = 0;
                try { n = Integer.parseInt(pl.optString("jersey", "0").trim()); } catch (NumberFormatException ignored) { }
                put(x, "n", n);
                put(x, "name", a != null ? a.optString("displayName", a.optString("fullName")) : "");
                put(x, "pos", pos != null ? pos.optString("abbreviation") : "");
                put(x, "place", pl.optInt("formationPlace", 0));
                if (pl.optBoolean("starter")) xi.add(x); else subs.put(x);
            }
            if (xi.isEmpty()) continue;
            gridFromFormation(xi, formation);
            JSONObject l = new JSONObject();
            put(l, "team", tm != null ? tm.optInt("id") : 0);
            put(l, "formation", formation);
            put(l, "coach", "");
            put(l, "xi", new JSONArray(xi));
            put(l, "subs", subs);
            luOut.put(l);
        }
        put(m, "lineups", luOut);
        return m;
    }

    static String athlete(JSONArray parts, int i) {
        if (parts == null || parts.length() <= i) return "";
        JSONObject a = parts.optJSONObject(i).optJSONObject("athlete");
        return a != null ? a.optString("displayName", a.optString("fullName")) : "";
    }

    /** Place les titulaires sur le terrain d'après le système (4-2-3-1…) : grille « ligne:colonne ». */
    static void gridFromFormation(List<JSONObject> xi, String formation) {
        String[] parts = formation.split("-");
        List<Integer> lines = new ArrayList<>();
        int sum = 1;
        try {
            for (String s : parts) { int v = Integer.parseInt(s.trim()); lines.add(v); sum += v; }
        } catch (NumberFormatException e) {
            return;
        }
        if (lines.isEmpty() || sum != xi.size()) return;
        boolean hasPlace = true;
        for (JSONObject x : xi) if (x.optInt("place") <= 0) hasPlace = false;
        if (hasPlace) Collections.sort(xi, (a, b) -> Integer.compare(a.optInt("place"), b.optInt("place")));
        // Le gardien en premier
        for (int i = 0; i < xi.size(); i++) {
            if ("G".equals(xi.get(i).optString("pos")) || "GK".equals(xi.get(i).optString("pos"))) {
                xi.add(0, xi.remove(i));
                break;
            }
        }
        int idx = 0;
        put(xi.get(idx++), "grid", "1:1");
        for (int row = 0; row < lines.size(); row++) {
            for (int col = 1; col <= lines.get(row); col++) put(xi.get(idx++), "grid", (row + 2) + ":" + col);
        }
    }

    /** Buteurs et passeurs du LOSC sur la saison, d'après le fil des matchs terminés. */
    static void scorers(JSONObject d, String team) {
        JSONArray fx = d.optJSONArray("fixtures");
        JSONObject done = d.optJSONObject("scorerMatches");
        if (done == null) done = new JSONObject();
        JSONObject tally = d.optJSONObject("scorerTally");
        if (tally == null) tally = new JSONObject();
        int teamId = Integer.parseInt(team);
        int fetched = 0;
        for (int i = 0; fx != null && i < fx.length() && fetched < 5; i++) {
            JSONObject f = fx.optJSONObject(i);
            if (!DONE.contains(f.optString("status"))) continue;
            String id = String.valueOf(f.optLong("id"));
            if (done.has(id)) continue;
            try {
                JSONObject m = summary(f.optString("slug"), f.optLong("id"), f);
                fetched++;
                JSONArray ev = m.optJSONArray("events");
                for (int k = 0; ev != null && k < ev.length(); k++) {
                    JSONObject e = ev.optJSONObject(k);
                    if (!"Goal".equals(e.optString("type")) || e.optInt("team") != teamId) continue;
                    String det = e.optString("detail");
                    if ("Own Goal".equals(det) || "Missed Penalty".equals(det)) continue;
                    bump(tally, e.optString("player"), 0);
                    if (!e.optString("assist").isEmpty()) bump(tally, e.optString("assist"), 1);
                }
                put(done, id, true);
            } catch (Exception ignored) {
                break;
            }
        }
        put(d, "scorerMatches", done);
        put(d, "scorerTally", tally);
        List<JSONObject> list = new ArrayList<>();
        for (java.util.Iterator<String> it = tally.keys(); it.hasNext(); ) {
            String name = it.next();
            JSONArray v = tally.optJSONArray(name);
            JSONObject o = new JSONObject();
            put(o, "name", name);
            put(o, "goals", v != null ? v.optInt(0) : 0);
            put(o, "assists", v != null ? v.optInt(1) : 0);
            put(o, "team", "Lille");
            put(o, "teamId", teamId);
            if (o.optInt("goals") > 0) list.add(o);
        }
        Collections.sort(list, (a, b) -> a.optInt("goals") != b.optInt("goals") ? b.optInt("goals") - a.optInt("goals") : b.optInt("assists") - a.optInt("assists"));
        put(d, "scorers", new JSONArray(list.size() > 12 ? list.subList(0, 12) : list));
        put(d, "scorersCount", done.length());
    }

    static void bump(JSONObject tally, String name, int idx) {
        if (name == null || name.isEmpty()) return;
        JSONArray v = tally.optJSONArray(name);
        if (v == null) { v = new JSONArray(); v.put(0); v.put(0); put(tally, name, v); }
        try { v.put(idx, v.optInt(idx) + 1); } catch (JSONException ignored) { }
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

    /* ------------------------------------------------------------------ actus */

    static final String[] NEWS_QUERIES = { "LOSC", "\"Lille OSC\" OR \"les Dogues\"" };

    static String utcDay() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(System.currentTimeMillis());
    }

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
                    notify(c, "match", (int) (id % 100000 * 10 + 1), "Compo officielle" + (f.isEmpty() ? "" : " (" + f + ")"), b.toString());
                    p.edit().putBoolean("lineupSent" + id, true).apply();
                }
            }
        }

        if (!p.getBoolean("notifGoals", true)) return;

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
                    String min = e.optInt("min") + (e.optInt("extra") > 0 ? "+" + e.optInt("extra") : "") + "'";
                    String kind = "Own Goal".equals(e.optString("detail")) ? " (csc)" : "Penalty".equals(e.optString("detail")) ? " (pen.)" : "";
                    notify(c, "match", (int) (id % 100000 * 100 + 10 + seen), (ours ? "BUT pour Lille ! " : "But adverse · ") + score, min + " " + e.optString("player") + kind);
                    any = true;
                }
                if (!any && scoreAfter > scoreBefore) notify(c, "match", (int) (id % 100000 * 100 + 10 + scoreAfter), "But ! " + score, "");
            }
        }

        boolean wasDone = old != null && DONE.contains(old.optString("status"));
        if (!wasDone && DONE.contains(neu.optString("status")) && !p.getBoolean("ftSent" + id, false)) {
            notify(c, "match", (int) (id % 100000 * 10 + 3), "Score final", score);
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
                .setColor(0xFFD6102A)
                .setAutoCancel(true)
                .setContentIntent(pi);
        nm.notify(id, b.build());
    }

    /* ------------------------------------------------------------------ utilitaire */

    static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (JSONException ignored) { }
    }
}
