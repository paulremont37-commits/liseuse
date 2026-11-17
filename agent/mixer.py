"""
Mixer : volume et sourdine de chaque appli qui joue du son sur le PC (comme le « mélangeur de volume » de Windows),
plus le volume général. La tablette ne peut que régler ces volumes : rien d'autre n'est accessible.

Les appels audio de Windows passent par COM : ils tournent dans un fil dédié, initialisé une fois.
"""
import concurrent.futures
import os
import sys

POOL = None
ERROR = ""

NAMES = {"discord": "Discord", "deezer": "Deezer", "spotify": "Spotify", "rocketleague": "Rocket League",
         "chrome": "Chrome", "msedge": "Edge", "firefox": "Firefox", "opera": "Opera", "brave": "Brave",
         "voicemod": "Voicemod", "voicemoddesktop": "Voicemod", "medal": "Medal", "steam": "Steam",
         "steamwebhelper": "Steam", "epicgameslauncher": "Epic Games", "obs64": "OBS", "vlc": "VLC",
         "teams": "Teams", "ms-teams": "Teams", "zoom": "Zoom", "applemusic": "Apple Music",
         "valorant-win64-shipping": "Valorant", "fortniteclient-win64-shipping": "Fortnite",
         "league of legends": "League of Legends", "javaw": "Minecraft", "explorer": "Explorateur"}


def _init():
    # COM en mode multi-fil (même mode que la lecture de la musique) avant d'importer comtypes
    sys.coinit_flags = 0
    import ctypes
    ctypes.windll.ole32.CoInitializeEx(None, 0)


def pool():
    global POOL
    if POOL is None:
        POOL = concurrent.futures.ThreadPoolExecutor(max_workers=1, thread_name_prefix="mixer", initializer=_init)
    return POOL


def _name(sess):
    if not sess.ProcessId:
        return "system", "Sons système"
    exe = ""
    try:
        exe = sess.Process.name() if sess.Process else ""
    except Exception:
        exe = ""
    key = os.path.splitext(exe)[0].lower() or f"pid{sess.ProcessId}"
    disp = NAMES.get(key)
    if not disp:
        dn = sess.DisplayName or ""
        disp = dn if dn and not dn.startswith("@") else (os.path.splitext(exe)[0] or "Appli")
        disp = disp[:1].upper() + disp[1:]
    return key, disp[:28]


def _master():
    from ctypes import POINTER, cast
    from comtypes import CLSCTX_ALL
    from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
    dev = AudioUtilities.GetSpeakers()
    iface = dev.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
    return cast(iface, POINTER(IAudioEndpointVolume))


def _sessions():
    from pycaw.pycaw import AudioUtilities
    groups = {}
    for s in AudioUtilities.GetAllSessions():
        try:
            if s.State == 2:  # session terminée
                continue
            key, name = _name(s)
            g = groups.setdefault(key, {"id": key, "name": name, "sessions": [], "active": False})
            g["sessions"].append(s)
            g["active"] = g["active"] or s.State == 1
        except Exception:
            continue
    return groups


def state():
    """État complet du mixer (appelé dans le fil COM)."""
    try:
        m = _master()
        master = {"vol": round(m.GetMasterVolumeLevelScalar() * 100), "mute": bool(m.GetMute())}
    except Exception:
        master = None  # pas de haut-parleur / casque par défaut : on affiche quand même les applis
    out = {"type": "mixer", "ok": True, "master": master, "apps": []}
    for g in _sessions().values():
        vols, mutes = [], []
        for s in g["sessions"]:
            try:
                v = s.SimpleAudioVolume
                vols.append(v.GetMasterVolume())
                mutes.append(bool(v.GetMute()))
            except Exception:
                pass
        if not vols:
            continue
        out["apps"].append({"id": g["id"], "name": g["name"], "vol": round(max(vols) * 100),
                            "mute": all(mutes), "active": g["active"]})
    # appli qui joue en ce moment d'abord, puis ordre alphabétique ; les sons système à la fin
    out["apps"].sort(key=lambda a: (a["id"] == "system", not a["active"], a["name"].lower()))
    return out


def apply(target, vol=None, mute=None):
    """Règle le volume (0-100) et/ou la sourdine du volume général ou d'une appli."""
    if target == "master":
        m = _master()
        if vol is not None:
            m.SetMasterVolumeLevelScalar(max(0.0, min(1.0, vol / 100)), None)
        if mute is not None:
            m.SetMute(1 if mute else 0, None)
        return
    g = _sessions().get(target)
    if not g:
        return
    for s in g["sessions"]:
        try:
            v = s.SimpleAudioVolume
            if vol is not None:
                v.SetMasterVolume(max(0.0, min(1.0, vol / 100)), None)
            if mute is not None:
                v.SetMute(1 if mute else 0, None)
        except Exception:
            pass


async def run(fn, *args):
    import asyncio
    return await asyncio.get_running_loop().run_in_executor(pool(), fn, *args)


def available():
    if sys.platform != "win32":
        return "Windows uniquement"
    import importlib.util
    # on ne fait que vérifier la présence (importer comtypes ici initialiserait COM dans le mauvais fil)
    return "" if importlib.util.find_spec("pycaw") else "module pycaw absent"
