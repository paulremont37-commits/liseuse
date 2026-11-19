"""
Stream Deck PC — reçoit les appuis de la tablette en Wi-Fi et envoie les raccourcis clavier.

Sécurité :
- n'accepte que les connexions qui donnent le bon code d'appairage (6 chiffres) ;
- n'exécute QUE des raccourcis clavier et des touches multimédia d'une liste fixe
  (aucune commande, aucun fichier, aucun programme lancé à distance).

Sous un autre système que Windows (ou avec DECK_DRYRUN=1), les touches sont seulement affichées.

Lancement : fenêtre (gui.py) par défaut, icône près de l'horloge quand on la ferme.
  --tray      démarre directement réduit près de l'horloge (démarrage avec Windows)
  --console   ancienne version dans l'invite de commande
"""
import asyncio
import collections
import json
import os
import secrets
import socket
import sys
import threading
import time

import websockets

PORT = int(os.environ.get("DECK_PORT", "8765"))
APP = "Stream Deck PC"
DRYRUN = os.environ.get("DECK_DRYRUN") == "1" or sys.platform != "win32"

# --------------------------------------------------------------------------- touches

VK = {"ctrl": 0x11, "alt": 0x12, "shift": 0x10, "win": 0x5B}
MODIFIERS = ("ctrl", "alt", "shift", "win")
for n in range(1, 25):
    VK[f"f{n}"] = 0x70 + n - 1          # F1 = 0x70 … F24 = 0x87
for c in "abcdefghijklmnopqrstuvwxyz":
    VK[c] = ord(c.upper())
for d in "0123456789":
    VK[d] = ord(d)
VK.update({
    "space": 0x20, "enter": 0x0D, "tab": 0x09, "esc": 0x1B,
    "media_play_pause": 0xB3, "media_next": 0xB0, "media_prev": 0xB1, "media_stop": 0xB2,
    "volume_up": 0xAF, "volume_down": 0xAE, "volume_mute": 0xAD,
})
EXTENDED = {0xB3, 0xB0, 0xB1, 0xB2, 0xAF, 0xAE, 0xAD, 0x5B}
KEY_NAMES = {"ctrl": "Ctrl", "alt": "Alt", "shift": "Maj", "win": "Win",
             "media_play_pause": "Lecture/Pause", "media_next": "Suivant", "media_prev": "Précédent",
             "media_stop": "Stop", "volume_up": "Volume +", "volume_down": "Volume -", "volume_mute": "Couper le son"}


def describe(keys):
    return " + ".join(KEY_NAMES.get(k, k.upper()) for k in keys)


def validate(keys):
    """Une combinaison valide : 0 à 4 modificateurs + exactement une touche de la liste."""
    if not isinstance(keys, list) or not 1 <= len(keys) <= 5:
        return None
    keys = [str(k).lower() for k in keys]
    if any(k not in VK for k in keys):
        return None
    mods = [k for k in keys if k in MODIFIERS]
    main = [k for k in keys if k not in MODIFIERS]
    if len(main) != 1 or len(set(mods)) != len(mods):
        return None
    return [m for m in MODIFIERS if m in mods] + main


if not DRYRUN:
    import ctypes
    from ctypes import wintypes

    user32 = ctypes.WinDLL("user32", use_last_error=True)
    INPUT_KEYBOARD, KEYEVENTF_EXTENDEDKEY, KEYEVENTF_KEYUP = 1, 0x0001, 0x0002
    MAPVK_VK_TO_VSC = 0
    ULONG_PTR = ctypes.c_size_t

    class KEYBDINPUT(ctypes.Structure):
        _fields_ = (("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                    ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR))

    class MOUSEINPUT(ctypes.Structure):
        _fields_ = (("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
                    ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR))

    class HARDWAREINPUT(ctypes.Structure):
        _fields_ = (("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD), ("wParamH", wintypes.WORD))

    class _INPUTUNION(ctypes.Union):
        _fields_ = (("ki", KEYBDINPUT), ("mi", MOUSEINPUT), ("hi", HARDWAREINPUT))

    class INPUT(ctypes.Structure):
        _anonymous_ = ("u",)
        _fields_ = (("type", wintypes.DWORD), ("u", _INPUTUNION))

    def _key(vk, up):
        flags = (KEYEVENTF_KEYUP if up else 0) | (KEYEVENTF_EXTENDEDKEY if vk in EXTENDED else 0)
        scan = user32.MapVirtualKeyW(vk, MAPVK_VK_TO_VSC)
        return INPUT(type=INPUT_KEYBOARD, ki=KEYBDINPUT(wVk=vk, wScan=scan, dwFlags=flags, time=0, dwExtraInfo=0))

    def send_keys(keys):
        vks = [VK[k] for k in keys]
        seq = [_key(v, False) for v in vks] + [_key(v, True) for v in reversed(vks)]
        arr = (INPUT * len(seq))(*seq)
        sent = user32.SendInput(len(seq), arr, ctypes.sizeof(INPUT))
        if sent != len(seq):
            raise OSError(ctypes.get_last_error())
else:
    def send_keys(keys):
        pass

# --------------------------------------------------------------------------- réglages

def config_path():
    base = os.environ.get("APPDATA") or os.path.expanduser("~/.config")
    folder = os.path.join(base, "StreamDeckPC")
    os.makedirs(folder, exist_ok=True)
    return os.path.join(folder, "config.json")


def load_config():
    p = config_path()
    try:
        with open(p, encoding="utf-8") as f:
            cfg = json.load(f)
    except (OSError, ValueError):
        cfg = {}
    if not str(cfg.get("code", "")).isdigit() or len(str(cfg.get("code"))) != 6:
        cfg["code"] = f"{secrets.randbelow(10**6):06d}"
        save_config(cfg)
    if os.environ.get("DECK_CODE"):
        cfg["code"] = os.environ["DECK_CODE"]
    return cfg


def save_config(cfg):
    try:
        with open(config_path(), "w", encoding="utf-8") as f:
            json.dump(cfg, f)
    except OSError:
        pass


def local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def startup_file():
    appdata = os.environ.get("APPDATA")
    if not appdata:
        return None
    return os.path.join(appdata, r"Microsoft\Windows\Start Menu\Programs\Startup", "StreamDeckPC.cmd")


def autostart_on():
    f = startup_file()
    return bool(f and os.path.exists(f))


def set_autostart(on):
    f = startup_file()
    if not f or not getattr(sys, "frozen", False):
        return False
    if on:
        with open(f, "w", encoding="utf-8") as h:
            # --tray : au démarrage de Windows, on se range directement près de l'horloge
            h.write(f'@start "" "{sys.executable}" --tray\r\n')
    elif os.path.exists(f):
        os.remove(f)
    return True

# --------------------------------------------------------------------------- état partagé (serveur ↔ fenêtre)

class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.cfg = load_config()
        self.code = self.cfg["code"]
        self.ip = local_ip()
        self.port = PORT
        self.error = ""
        self.listening = False
        self.clients = {}            # websocket -> {"ip", "since", "latency"}
        self.events = collections.deque(maxlen=60)
        self.seq = 0
        self.presses = 0
        self.loop = None
        self.media = None           # musique en cours (media.py)
        self.media_info = ""
        self.rl = None              # session Rocket League (rl.py)
        self.push = lambda msg: None  # envoie un message à toutes les tablettes (remplacé par serve)

    def event(self, kind, text, **extra):
        with self.lock:
            self.seq += 1
            self.events.append({"seq": self.seq, "t": time.time(), "kind": kind, "text": text, **extra})
        if sys.stdout:
            try:
                print(time.strftime("[%H:%M:%S] ") + text, flush=True)
            except (OSError, ValueError):
                pass

    def snapshot(self, since=0):
        with self.lock:
            tablets = []
            for ws, c in self.clients.items():
                lat = getattr(ws, "latency", 0) or 0
                tablets.append({"ip": c["ip"], "since": c["since"], "latency": round(lat * 1000) if lat else None})
            return {
                "app": APP, "ip": self.ip, "port": self.port, "code": self.code, "error": self.error,
                "listening": self.listening, "dryrun": DRYRUN, "host": socket.gethostname(),
                "tablets": tablets, "presses": self.presses, "music": self.media_info,
                "rl": self.rl.summary() if self.rl else None,
                "events": [e for e in self.events if e["seq"] > since], "seq": self.seq,
                "autostart": autostart_on(), "canAutostart": bool(getattr(sys, "frozen", False) and sys.platform == "win32"),
                "askAutostart": not self.cfg.get("askedAutostart") and bool(getattr(sys, "frozen", False) and sys.platform == "win32"),
            }

    def new_code(self):
        """Nouveau code : les tablettes déjà reliées sont déconnectées et doivent entrer le nouveau."""
        with self.lock:
            self.code = f"{secrets.randbelow(10**6):06d}"
            self.cfg["code"] = self.code
            save_config(self.cfg)
            clients = list(self.clients)
        if self.loop:
            for ws in clients:
                asyncio.run_coroutine_threadsafe(ws.close(1000, "nouveau code"), self.loop)
        self.event("info", "Nouveau code créé : entre-le sur la tablette.")
        return self.code

    def save_cfg(self):
        with self.lock:
            save_config(self.cfg)

    def autostart(self):
        return autostart_on()

    def answer_autostart(self, on):
        set_autostart(on)
        self.cfg["askedAutostart"] = True
        save_config(self.cfg)

STATE = None

# --------------------------------------------------------------------------- latence

def no_delay(ws):
    """TCP_NODELAY : les petits messages partent tout de suite (déjà le cas par défaut avec asyncio)."""
    try:
        sock = ws.transport.get_extra_info("socket")
        if sock is not None:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    except (OSError, AttributeError):
        pass


def tune_windows():
    """Réglages Windows pour que les appuis passent sans attendre."""
    if sys.platform != "win32":
        return
    import ctypes
    k32 = ctypes.WinDLL("kernel32", use_last_error=True)
    # 1. Mode console : désactive la « sélection rapide », un clic dans la fenêtre mettait tout en pause.
    try:
        h = k32.GetStdHandle(-10)  # STD_INPUT_HANDLE
        mode = ctypes.c_uint32()
        if h and k32.GetConsoleMode(h, ctypes.byref(mode)):
            ENABLE_QUICK_EDIT, ENABLE_EXTENDED_FLAGS = 0x0040, 0x0080
            k32.SetConsoleMode(h, (mode.value & ~ENABLE_QUICK_EDIT) | ENABLE_EXTENDED_FLAGS)
    except OSError:
        pass
    # 2. Priorité « supérieure à la normale » : passe devant les tâches de fond quand un jeu charge le processeur.
    try:
        ABOVE_NORMAL_PRIORITY_CLASS = 0x8000
        k32.SetPriorityClass(k32.GetCurrentProcess(), ABOVE_NORMAL_PRIORITY_CLASS)
    except OSError:
        pass

# --------------------------------------------------------------------------- serveur

FAILS = {}  # ip -> (nombre d'échecs, heure du dernier)


async def handler(ws):
    st = STATE
    ip = ws.remote_address[0] if ws.remote_address else "?"
    n, last = FAILS.get(ip, (0, 0))
    if n >= 5 and time.time() - last < 60:
        await ws.send(json.dumps({"type": "error", "reason": "trop d'essais, réessaie dans une minute"}))
        return
    try:
        hello = json.loads(await asyncio.wait_for(ws.recv(), 10))
    except Exception:
        return
    if hello.get("type") != "hello" or not secrets.compare_digest(str(hello.get("code", "")), st.code):
        FAILS[ip] = (n + 1, time.time())
        st.event("warn", f"Code refusé depuis {ip}")
        await ws.send(json.dumps({"type": "error", "reason": "code incorrect"}))
        return
    FAILS.pop(ip, None)
    no_delay(ws)
    await ws.send(json.dumps({"type": "ok", "name": socket.gethostname(), "version": 1}))
    with st.lock:
        st.clients[ws] = {"ip": ip, "since": time.time()}
    st.event("on", f"Tablette connectée ({ip})")
    for mod in ("media", "rl"):
        try:
            m = __import__(mod).current(st)
            if m:
                await ws.send(json.dumps(m))
        except Exception:
            pass
    try:
        async for raw in ws:
            try:
                msg = json.loads(raw)
            except ValueError:
                continue
            if msg.get("type") == "ping":
                await ws.send(json.dumps({"type": "pong", "id": msg.get("id")}))
            elif msg.get("type") in ("mixer_get", "mixer_set"):
                await mixer_message(ws, msg)
            elif msg.get("type") in ("rl_get", "rl_reset"):
                if st.rl:
                    if msg["type"] == "rl_reset":
                        st.rl.reset()
                        st.event("info", "Session Rocket League remise à zéro depuis la tablette.")
                        await broadcast(st, st.rl.summary())
                    else:
                        await ws.send(json.dumps(st.rl.summary()))
            elif msg.get("type") == "press":
                keys = validate(msg.get("keys"))
                label = str(msg.get("label", ""))[:40]
                if not keys:
                    await ws.send(json.dumps({"type": "result", "id": msg.get("id"), "ok": False, "reason": "touche inconnue"}))
                    continue
                try:
                    send_keys(keys)
                    # Réponse d'abord, journal ensuite : l'affichage ne retarde jamais rien
                    await ws.send(json.dumps({"type": "result", "id": msg.get("id"), "ok": True}))
                    st.presses += 1
                    st.event("press", f"{label or 'Bouton'} → {describe(keys)}", label=label or "Bouton", keys=describe(keys))
                except OSError as e:
                    st.event("warn", f"Windows a refusé {describe(keys)} ({e})")
                    await ws.send(json.dumps({"type": "result", "id": msg.get("id"), "ok": False, "reason": "envoi refusé par Windows"}))
    except websockets.ConnectionClosed:
        pass
    finally:
        with st.lock:
            st.clients.pop(ws, None)
    st.event("off", f"Tablette déconnectée ({ip})")


async def mixer_message(ws, msg):
    """Mixer : la tablette lit ou règle le volume général et celui de chaque appli (rien d'autre)."""
    import mixer as mixer_mod
    why = mixer_mod.available()
    if why:
        await ws.send(json.dumps({"type": "mixer", "ok": False, "reason": why}))
        return
    try:
        if msg["type"] == "mixer_set":
            target = str(msg.get("id", ""))[:80]
            vol = msg.get("vol")
            vol = max(0, min(100, float(vol))) if isinstance(vol, (int, float)) and not isinstance(vol, bool) else None
            mute = msg.get("mute") if isinstance(msg.get("mute"), bool) else None
            await mixer_mod.run(mixer_mod.apply, target, vol, mute)
            if mute is None and not msg.get("reply"):
                return  # pendant qu'on glisse un curseur : pas besoin de renvoyer tout l'état
        await ws.send(json.dumps(await mixer_mod.run(mixer_mod.state)))
    except Exception as e:
        await ws.send(json.dumps({"type": "mixer", "ok": False, "reason": f"{type(e).__name__}: {e}"}))


async def broadcast(st, msg):
    raw = json.dumps(msg)
    with st.lock:
        clients = list(st.clients)
    for ws in clients:
        try:
            await ws.send(raw)
        except Exception:
            pass


async def serve(st):
    st.loop = asyncio.get_running_loop()
    st.push = lambda msg: asyncio.run_coroutine_threadsafe(broadcast(st, msg), st.loop)
    try:  # widget « musique en cours » (Windows uniquement)
        import media as media_mod
        asyncio.get_running_loop().create_task(media_mod.loop(st, lambda m: broadcast(st, m)))
    except Exception as e:
        st.media_info = f"Indisponible : {type(e).__name__}: {e}"
    try:  # session Rocket League (API officielle du jeu)
        import rl as rl_mod
        asyncio.get_running_loop().create_task(rl_mod.loop(st, lambda m: broadcast(st, m)))
    except Exception as e:
        st.event("warn", f"Stats Rocket League indisponibles : {type(e).__name__}: {e}")
        st.event("warn", "Musique en cours indisponible : " + st.media_info)
    # compression=None : messages minuscules, la compression ne ferait qu'ajouter du travail
    async with websockets.serve(handler, "0.0.0.0", st.port, ping_interval=20, ping_timeout=20, max_size=4096, compression=None):
        st.listening = True
        st.event("info", f"Prêt : en attente de la tablette sur le port {st.port}.")
        while True:
            await asyncio.sleep(30)
            ip = local_ip()          # l'adresse peut changer (box redémarrée, Wi-Fi ↔ câble)
            if ip != st.ip:
                st.ip = ip
                st.event("info", f"Nouvelle adresse du PC : {ip}")


def run_server(st):
    try:
        asyncio.run(serve(st))
    except OSError as e:
        st.listening = False
        st.error = (f"Le port {st.port} est déjà pris : Stream Deck PC est sûrement déjà ouvert "
                    f"(regarde près de l'horloge). Détail : {e}")
        st.event("warn", st.error)


def start(st):
    global STATE
    STATE = st
    t = threading.Thread(target=run_server, args=(st,), daemon=True, name="serveur")
    t.start()
    return t

# --------------------------------------------------------------------------- lancement

def console_main():
    if sys.platform == "win32" and getattr(sys, "frozen", False) and sys.stdout is None:
        # l'exe est une appli fenêtrée : on ouvre une console pour cette version
        import ctypes
        ctypes.windll.kernel32.AllocConsole()
        sys.stdout = open("CONOUT$", "w", encoding="utf-8", buffering=1)
        sys.stdin = open("CONIN$", encoding="utf-8")
    st = State()
    if sys.platform == "win32":
        os.system(f"title {APP}")
    print("=" * 52)
    print(f"  {APP}")
    print("=" * 52)
    print("  Sur la tablette, entre :")
    print(f"    Adresse : {st.ip}")
    print(f"    Code    : {st.code}")
    print()
    print("  Laisse cette fenêtre ouverte (tu peux la réduire).")
    if DRYRUN:
        print("  [mode test : les touches sont affichées, pas envoyées]")
    print("=" * 52, flush=True)
    t = start(st)
    try:
        while t.is_alive():
            t.join(0.5)
    except KeyboardInterrupt:
        pass
    if st.error and sys.stdin:
        try:
            input("Appuie sur Entrée pour fermer.")
        except EOFError:
            pass


if __name__ == "__main__":
    if "--no-autostart" in sys.argv:
        set_autostart(False)
        sys.exit(0)
    if "--enable-rl-api" in sys.argv:
        # relancé en administrateur par rl.enable_api() : écrit juste le fichier de réglage du jeu
        import rl as rl_mod
        try:
            rl_mod.write_ini(sys.argv[sys.argv.index("--enable-rl-api") + 1])
        except (OSError, IndexError):
            sys.exit(1)
        sys.exit(0)
    if "--selftest" in sys.argv:
        # Vérification automatique de l'exe (compilation GitHub) : modules présents et lecture de la musique
        res = {}
        try:
            import media as media_mod
            res["media_import"] = media_mod.IMPORT_ERROR or "ok"

            async def _read():
                try:
                    return await media_mod.Media().read()
                except Exception as e:
                    return f"{type(e).__name__}: {e}"
            res["media_read"] = asyncio.run(_read()) if media_mod.Manager else None
        except Exception as e:
            res["media_import"] = f"{type(e).__name__}: {e}"
        try:
            import mixer as mixer_mod
            res["mixer_import"] = mixer_mod.available() or "ok"
            if res["mixer_import"] == "ok":
                try:
                    res["mixer_state"] = mixer_mod.pool().submit(mixer_mod.state).result(10)
                except Exception as e:
                    res["mixer_state"] = f"{type(e).__name__}: {e}"
        except Exception as e:
            res["mixer_import"] = f"{type(e).__name__}: {e}"
        try:
            import rl as rl_mod
            res["rl_import"] = "ok"
            res["rl_status"] = rl_mod.api_status()[0]
        except Exception as e:
            res["rl_import"] = f"{type(e).__name__}: {e}"
        try:
            import gui  # noqa: F401
            res["gui_import"] = "ok"
        except Exception as e:
            res["gui_import"] = f"{type(e).__name__}: {e}"
        with open("selftest.txt", "w", encoding="utf-8") as f:
            json.dump(res, f, default=str)
        sys.exit(0)
    tune_windows()
    if autostart_on():
        set_autostart(True)  # met à jour un ancien lancement automatique (ajoute --tray)
    if "--console" in sys.argv:
        console_main()
    else:
        try:
            import gui
        except ImportError:
            gui = None
        if gui is None:
            console_main()
        else:
            gui.run(State(), start, tray_only="--tray" in sys.argv)
