"""
Session Rocket League : victoires, défaites, série, stats cumulées, à partir de l'API officielle du jeu
(« Stats API », https://www.rocketleague.com/developer/stats-api).

Le jeu envoie les stats en local (WebSocket 127.0.0.1:49124) une fois l'API activée dans
<dossier du jeu>/TAGame/Config/TAStatsAPI.ini. Seuls les matchs en ligne comptent (pas l'entraînement
ni les matchs hors ligne). L'API ne donne pas le MMR.
"""
import asyncio
import glob
import json
import os
import sys
import time

WEB_PORT = 49124
SECTION = "[TAGame.MatchStatsExporter_TA]"
INI = f"{SECTION}\r\nPacketSendRate=2\r\nPort=49123\r\nWebPort={WEB_PORT}\r\n"

PLAYLISTS = {1: "Duel", 2: "Doubles", 3: "Standard", 4: "Chaos", 6: "Partie privée", 7: "Saison", 8: "Hors ligne",
             10: "Classé Duel", 11: "Classé Doubles", 13: "Classé Standard", 15: "Snow Day", 16: "Rocket Labs",
             17: "Hoops", 18: "Rumble", 22: "Tournoi", 23: "Dropshot", 27: "Classé Hoops", 28: "Classé Rumble",
             29: "Classé Dropshot", 30: "Classé Snow Day", 34: "Tournoi", 38: "Heatseeker", 41: "Gridiron",
             43: "Heatseeker", 47: "Knockout", 52: "Spike Rush"}

SESSION_GAP = 6 * 3600   # une pause de plus de 6 h démarre une nouvelle session


# --------------------------------------------------------------------------- activation de l'API dans le jeu

def find_install():
    """Dossier de Rocket League installé par Epic Games (manifestes du launcher), puis emplacements courants."""
    cands = []
    for f in glob.glob(r"C:\ProgramData\Epic\EpicGamesLauncher\Data\Manifests\*.item"):
        try:
            with open(f, encoding="utf-8") as h:
                m = json.load(h)
            if m.get("AppName") == "Sugar" or "rocket league" in str(m.get("DisplayName", "")).lower():
                cands.append(m.get("InstallLocation", ""))
        except (OSError, ValueError):
            continue
    cands += [r"C:\Program Files\Epic Games\rocketleague",
              r"C:\Program Files (x86)\Steam\steamapps\common\rocketleague"]
    for c in cands:
        if c and os.path.isdir(os.path.join(c, "TAGame", "Config")):
            return c
    return ""


def ini_path(install):
    return os.path.join(install, "TAGame", "Config", "TAStatsAPI.ini")


def api_status():
    """'on' si l'API est activée dans le fichier du jeu, 'off' sinon, 'nogame' si le jeu est introuvable."""
    inst = find_install()
    if not inst:
        return "nogame", ""
    for name in ("TAStatsAPI.ini", "DefaultStatsAPI.ini"):
        p = os.path.join(inst, "TAGame", "Config", name)
        if os.path.exists(p):
            try:
                with open(p, encoding="utf-8", errors="ignore") as h:
                    txt = h.read()
            except OSError:
                continue
            rate = 0.0
            for line in txt.splitlines():
                if line.strip().lower().startswith("packetsendrate"):
                    try:
                        rate = float(line.split("=", 1)[1])
                    except ValueError:
                        rate = 0.0
            web = "webport=0" not in txt.replace(" ", "").lower()
            return ("on" if rate > 0 and web else "off"), inst
    return "off", inst


def write_ini(install):
    with open(ini_path(install), "w", encoding="utf-8") as h:
        h.write(INI)


def enable_api():
    """Active l'API. Si Windows refuse (dossier protégé), relance ce programme en administrateur juste pour ça."""
    inst = find_install()
    if not inst:
        return "Rocket League introuvable (installé avec Epic Games ?)"
    try:
        write_ini(inst)
        return ""
    except PermissionError:
        if sys.platform != "win32" or not getattr(sys, "frozen", False):
            return "accès refusé au dossier du jeu"
        import ctypes
        r = ctypes.windll.shell32.ShellExecuteW(None, "runas", sys.executable, f'--enable-rl-api "{inst}"', None, 0)
        return "" if r > 32 else "autorisation administrateur refusée"
    except OSError as e:
        return f"{type(e).__name__}: {e}"


# --------------------------------------------------------------------------- session

class Session:
    def __init__(self, st):
        self.st = st
        cfg = st.cfg.get("rl") or {}
        self.name = cfg.get("name", "")           # pseudo réglé à la main (sinon détection automatique)
        self.matches = cfg.get("matches", [])      # matchs terminés de la session
        self.started = cfg.get("started", time.time())
        if self.matches and time.time() - self.matches[-1]["t"] > SESSION_GAP:
            self.reset(save=False)
        self.cur = None                            # match en cours
        self.connected = False
        self.status, self.install = api_status()

    # -- persistance
    def save(self):
        self.st.cfg["rl"] = {"name": self.name, "matches": self.matches[-200:], "started": self.started}
        self.st.save_cfg()

    def reset(self, save=True):
        self.matches, self.started = [], time.time()
        if save:
            self.save()

    # -- qui suis-je ?
    def me(self):
        if self.name:
            return self.name, "réglé"
        # caméra du joueur (Target) la plus suivie pendant les matchs
        votes = {}
        for m in self.matches + ([self.cur] if self.cur else []):
            for n, c in (m.get("targets") or {}).items():
                votes[n] = votes.get(n, 0) + c
        if votes:
            return max(votes, key=votes.get), "détecté"
        # sinon : le seul joueur présent dans tous les matchs
        sets = [set(p["name"] for p in m["players"]) for m in self.matches if m.get("players")]
        if len(sets) >= 2:
            common = set.intersection(*sets)
            if len(common) == 1:
                return common.pop(), "détecté"
        return "", ""

    # -- événements de l'API
    def on_message(self, ev, data):
        if ev == "UpdateState":
            guid = data.get("MatchGuid") or ""
            game = data.get("Game") or {}
            if not guid:          # entraînement / hors ligne : ignoré
                return False
            if not self.cur or self.cur["guid"] != guid:
                self.cur = {"guid": guid, "t0": time.time(), "targets": {}, "players": [], "teams": [], "playlist": 0}
            c = self.cur
            c["playlist"] = game.get("PlaylistId", c["playlist"])
            c["time"] = game.get("TimeSeconds", 0)
            c["ot"] = bool(game.get("bOvertime"))
            c["teams"] = [{"name": t.get("Name", ""), "score": t.get("Score", 0), "num": t.get("TeamNum", i),
                           "color": t.get("ColorPrimary", "")} for i, t in enumerate(game.get("Teams") or [])]
            c["players"] = [{"name": p.get("Name", ""), "team": p.get("TeamNum", 0), "score": p.get("Score", 0),
                             "goals": p.get("Goals", 0), "assists": p.get("Assists", 0), "saves": p.get("Saves", 0),
                             "shots": p.get("Shots", 0), "demos": p.get("Demos", 0)} for p in data.get("Players") or []]
            if game.get("bHasTarget") and isinstance(game.get("Target"), dict):
                n = game["Target"].get("Name")
                if n:
                    c["targets"][n] = c["targets"].get(n, 0) + 1
            return "live"
        if ev == "MatchEnded" and self.cur and (data.get("MatchGuid") or "") == self.cur["guid"]:
            c = self.cur
            m = {"guid": c["guid"], "t": time.time(), "playlist": c["playlist"], "winner": data.get("WinnerTeamNum", -1),
                 "teams": c["teams"], "players": c["players"], "targets": c["targets"], "ot": c.get("ot", False)}
            if not any(x["guid"] == m["guid"] for x in self.matches):
                if self.matches and m["t"] - self.matches[-1]["t"] > SESSION_GAP:
                    self.reset(save=False)
                self.matches.append(m)
                self.save()
            self.cur = None
            return "match"
        if ev == "MatchDestroyed":
            self.cur = None
            return "live"
        return False

    # -- résumé envoyé à la tablette et à la fenêtre
    def summary(self):
        me, how = self.me()
        res, tot = [], {"goals": 0, "assists": 0, "saves": 0, "shots": 0, "demos": 0, "score": 0}
        modes = {}
        for m in self.matches:
            p = next((x for x in m["players"] if x["name"] == me), None) if me else None
            win = None if p is None else (m["winner"] == p["team"])
            us = them = None
            if p is not None and len(m["teams"]) == 2:
                us = m["teams"][p["team"]]["score"] if p["team"] < 2 else None
                them = m["teams"][1 - p["team"]]["score"] if p["team"] < 2 else None
            if p:
                for k in tot:
                    tot[k] += p.get(k, 0)
            mode = PLAYLISTS.get(m["playlist"], f"Mode {m['playlist']}")
            md = modes.setdefault(mode, {"mode": mode, "w": 0, "l": 0})
            if win is True:
                md["w"] += 1
            elif win is False:
                md["l"] += 1
            res.append({"t": m["t"], "mode": mode, "win": win, "us": us, "them": them, "ot": m.get("ot", False),
                        "me": p})
        w = sum(1 for r in res if r["win"] is True)
        l = sum(1 for r in res if r["win"] is False)
        streak = 0
        for r in reversed(res):
            if r["win"] is None:
                continue
            if streak == 0:
                streak = 1 if r["win"] else -1
            elif (streak > 0) == r["win"]:
                streak += 1 if r["win"] else -1
            else:
                break
        live = None
        if self.cur and self.cur.get("teams"):
            c = self.cur
            p = next((x for x in c["players"] if x["name"] == me), None) if me else None
            live = {"mode": PLAYLISTS.get(c["playlist"], ""), "time": c.get("time", 0), "ot": c.get("ot", False),
                    "teams": c["teams"], "myTeam": p["team"] if p else None}
        return {"type": "rl", "status": self.status, "connected": self.connected, "me": me, "meHow": how,
                "started": self.started, "w": w, "l": l, "n": len(res), "streak": streak, "tot": tot,
                "modes": sorted(modes.values(), key=lambda x: -(x["w"] + x["l"])),
                "last": res[-12:][::-1], "live": live}


async def loop(st, broadcast):
    """Se connecte au jeu quand il tourne ; prévient les tablettes à chaque match terminé."""
    import websockets
    sess = Session(st)
    st.rl = sess
    while True:
        sess.status, sess.install = api_status()
        try:
            async with websockets.connect(f"ws://127.0.0.1:{WEB_PORT}", open_timeout=3, ping_interval=None,
                                          max_size=2 ** 22, compression=None) as ws:
                sess.connected = True
                st.event("info", "Rocket League connecté : la session est suivie.")
                await broadcast(sess.summary())
                async for raw in ws:
                    try:
                        msg = json.loads(raw)
                        data = msg.get("Data")
                        if isinstance(data, str):
                            data = json.loads(data)
                        kind = sess.on_message(msg.get("Event", ""), data or {})
                    except (ValueError, TypeError, KeyError):
                        continue
                    if kind == "match":
                        s = sess.summary()
                        last = s["last"][0] if s["last"] else None
                        if last and last["win"] is not None:
                            st.event("info", f"Rocket League : {'victoire' if last['win'] else 'défaite'} ({last['mode']}) — session {s['w']} V / {s['l']} D")
                        await broadcast(s)
                    # (pas d'envoi pendant le match : la tablette n'affiche que la session, et ça épargne sa batterie)
        except Exception:
            pass
        if sess.connected:
            sess.connected = False
            sess.cur = None
            await broadcast(sess.summary())
        await asyncio.sleep(5)


def current(st):
    s = getattr(st, "rl", None)
    return s.summary() if s else None
