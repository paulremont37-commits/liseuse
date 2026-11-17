"""
Musique en cours de lecture sur le PC (Deezer, Spotify, YouTube dans le navigateur…).

Lit les « contrôles multimédias » de Windows (ceux qui s'affichent avec les touches de volume) :
titre, artiste, pochette, lecture/pause, avancement. Rien n'est contrôlé ici : les boutons
précédent / lecture / suivant de la tablette passent par les touches multimédia habituelles.
"""
import asyncio
import base64
import datetime
import hashlib
import sys
import time

IMPORT_ERROR = ""
try:
    if sys.platform == "win32":
        # La lecture des contrôles multimédias passe par COM : on initialise le fil (sans effet s'il l'est déjà)
        import ctypes
        ctypes.windll.ole32.CoInitializeEx(None, 0)  # COINIT_MULTITHREADED
    from winrt.windows.media.control import (
        GlobalSystemMediaTransportControlsSessionManager as Manager,
        GlobalSystemMediaTransportControlsSessionPlaybackStatus as Status,
    )
    from winrt.windows.storage.streams import Buffer, InputStreamOptions
except Exception as e:  # autre système que Windows, ou paquet absent : pas de widget, le reste marche
    Manager = None
    IMPORT_ERROR = f"{type(e).__name__}: {e}"

APPS = {"deezer": "Deezer", "spotify": "Spotify", "chrome": "Chrome", "msedge": "Edge", "firefox": "Firefox",
        "zunemusic": "Lecteur multimédia", "music.ui": "Groove", "vlc": "VLC", "applemusic": "Apple Music",
        "tidal": "Tidal", "youtube": "YouTube", "opera": "Opera", "brave": "Brave"}


def app_name(aumid):
    low = (aumid or "").lower()
    for k, v in APPS.items():
        if k in low:
            return v
    name = (aumid or "").split("!")[0].split("\\")[-1].split(".")[0]
    return name[:20] or "PC"


async def read_art(ref):
    """Pochette → data URL (envoyée seulement quand le morceau change)."""
    if ref is None:
        return None
    try:
        stream = await ref.open_read_async()
        size = int(stream.size)
        if not size or size > 1_500_000:
            return None
        buf = Buffer(size)
        buf = await stream.read_async(buf, size, InputStreamOptions.READ_AHEAD)
        data = bytes(memoryview(buf))[: buf.length]
        mime = "image/png" if data[:4] == b"\x89PNG" else "image/jpeg"
        return f"data:{mime};base64,{base64.b64encode(data).decode()}"
    except Exception:
        return None


class Media:
    def __init__(self):
        self.mgr = None
        self.last = None        # dernier état envoyé (sans la pochette)
        self.art = None         # data URL de la pochette actuelle
        self.art_key = ""
        self.last_t = 0.0

    async def read(self):
        if Manager is None:
            return None
        if self.mgr is None:
            self.mgr = await Manager.request_async()
        s = self.mgr.get_current_session()
        if s is None:
            return {"type": "media", "active": False}
        props = await s.try_get_media_properties_async()
        info = s.get_playback_info()
        tl = s.get_timeline_properties()
        playing = info.playback_status == Status.PLAYING
        pos = dur = 0.0
        try:
            dur = max(0.0, (tl.end_time - tl.start_time).total_seconds())
            pos = tl.position.total_seconds()
            if playing and tl.last_updated_time:
                now = datetime.datetime.now(datetime.timezone.utc)
                upd = tl.last_updated_time
                if upd.tzinfo is None:
                    upd = upd.replace(tzinfo=datetime.timezone.utc)
                if upd.year > 2000:
                    pos += max(0.0, (now - upd).total_seconds())
            pos = min(pos, dur) if dur else 0.0
        except Exception:
            pos = dur = 0.0
        title, artist = props.title or "", props.artist or props.album_artist or ""
        key = hashlib.sha1(f"{title}|{artist}|{props.album_title}".encode()).hexdigest()[:12]
        if key != self.art_key:
            self.art_key = key
            self.art = await read_art(props.thumbnail)
        return {"type": "media", "active": bool(title), "title": title, "artist": artist,
                "album": props.album_title or "", "app": app_name(s.source_app_user_model_id),
                "playing": playing, "pos": round(pos, 1), "dur": round(dur, 1), "artId": self.art_key if self.art else ""}

    def changed(self, m):
        """Vrai si la tablette doit recevoir une mise à jour (hors avancement normal de la lecture)."""
        a = self.last
        if a is None:
            return True
        for k in ("active", "title", "artist", "playing", "artId", "app", "dur"):
            if a.get(k) != m.get(k):
                return True
        # saut dans le morceau (l'avancement normal est calculé sur la tablette)
        expected = (a.get("pos") or 0) + ((time.monotonic() - self.last_t) if a.get("playing") else 0)
        return bool(m.get("dur")) and abs((m.get("pos") or 0) - expected) > 3


async def loop(st, broadcast):
    """Toutes les secondes : si la musique a changé, on prévient les tablettes."""
    media = Media()
    st.media = media
    if Manager is None:
        st.media_info = "Indisponible : " + (IMPORT_ERROR or "Windows uniquement")
        st.event("warn", "Musique en cours indisponible (" + (IMPORT_ERROR or "Windows uniquement") + ")")
        return
    st.media_info = "Recherche de la musique…"
    tick, last_err = 0, ""
    while True:
        try:
            m = await media.read()
            tick += 1
            if m is not None:
                st.media_info = (f"{m['app']} · {m['title']} — {m['artist']}" + ("" if m["playing"] else " (en pause)")) if m.get("active") \
                    else "Rien en lecture : lance un morceau sur Deezer, Spotify ou le navigateur"
                last_err = ""
            if m is not None and (media.changed(m) or tick % 10 == 0):
                full = dict(m, art=media.art if (media.last or {}).get("artId") != m.get("artId") else None)
                media.last = m
                media.last_t = time.monotonic()
                await broadcast(full)
        except Exception as e:
            err = f"{type(e).__name__}: {e}"
            st.media_info = "Erreur : " + err
            if err != last_err:
                last_err = err
                st.event("warn", "Musique en cours : " + err)
            media.mgr = None  # on réessaiera (service multimédia redémarré…)
        await asyncio.sleep(1)


def current(st):
    """État complet (avec pochette) pour une tablette qui vient de se connecter."""
    media = getattr(st, "media", None)
    if media is None or media.last is None:
        return None
    return dict(media.last, art=media.art)
