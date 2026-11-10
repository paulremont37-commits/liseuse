"""
Stream Deck PC — reçoit les appuis de la tablette en Wi-Fi et envoie les raccourcis clavier.

Sécurité :
- n'accepte que les connexions qui donnent le bon code d'appairage (6 chiffres) ;
- n'exécute QUE des raccourcis clavier et des touches multimédia d'une liste fixe
  (aucune commande, aucun fichier, aucun programme lancé à distance).

Sous un autre système que Windows (ou avec DECK_DRYRUN=1), les touches sont seulement affichées.
"""
import asyncio
import json
import os
import secrets
import socket
import sys
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
        with open(p, "w", encoding="utf-8") as f:
            json.dump(cfg, f)
    if os.environ.get("DECK_CODE"):
        cfg["code"] = os.environ["DECK_CODE"]
    return cfg


def local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def log(msg):
    print(time.strftime("[%H:%M:%S] ") + msg, flush=True)


def startup_file():
    appdata = os.environ.get("APPDATA")
    if not appdata:
        return None
    return os.path.join(appdata, r"Microsoft\Windows\Start Menu\Programs\Startup", "StreamDeckPC.cmd")


def set_autostart(on):
    f = startup_file()
    if not f or not getattr(sys, "frozen", False):
        return False
    if on:
        with open(f, "w", encoding="utf-8") as h:
            h.write(f'@start "" /min "{sys.executable}"\r\n')
    elif os.path.exists(f):
        os.remove(f)
    return True

# --------------------------------------------------------------------------- serveur

CODE = None
FAILS = {}  # ip -> (nombre d'échecs, heure du dernier)


async def handler(ws):
    ip = ws.remote_address[0] if ws.remote_address else "?"
    n, last = FAILS.get(ip, (0, 0))
    if n >= 5 and time.time() - last < 60:
        await ws.send(json.dumps({"type": "error", "reason": "trop d'essais, réessaie dans une minute"}))
        return
    try:
        hello = json.loads(await asyncio.wait_for(ws.recv(), 10))
    except Exception:
        return
    if hello.get("type") != "hello" or not secrets.compare_digest(str(hello.get("code", "")), CODE):
        FAILS[ip] = (n + 1, time.time())
        log(f"Code refusé depuis {ip}")
        await ws.send(json.dumps({"type": "error", "reason": "code incorrect"}))
        return
    FAILS.pop(ip, None)
    await ws.send(json.dumps({"type": "ok", "name": socket.gethostname(), "version": 1}))
    log(f"Tablette connectée ({ip})")
    try:
        async for raw in ws:
            try:
                msg = json.loads(raw)
            except ValueError:
                continue
            if msg.get("type") == "ping":
                await ws.send(json.dumps({"type": "pong", "id": msg.get("id")}))
            elif msg.get("type") == "press":
                keys = validate(msg.get("keys"))
                label = str(msg.get("label", ""))[:40]
                if not keys:
                    await ws.send(json.dumps({"type": "result", "id": msg.get("id"), "ok": False, "reason": "touche inconnue"}))
                    continue
                try:
                    send_keys(keys)
                    log(f"{label or 'Bouton'} → {describe(keys)}")
                    await ws.send(json.dumps({"type": "result", "id": msg.get("id"), "ok": True}))
                except OSError as e:
                    log(f"Échec de l'envoi de {describe(keys)} : {e}")
                    await ws.send(json.dumps({"type": "result", "id": msg.get("id"), "ok": False, "reason": "envoi refusé par Windows"}))
    except websockets.ConnectionClosed:
        pass
    log(f"Tablette déconnectée ({ip})")


async def main():
    global CODE
    cfg = load_config()
    CODE = cfg["code"]
    ip = local_ip()
    if sys.platform == "win32":
        os.system(f"title {APP}")
    print("=" * 52)
    print(f"  {APP}")
    print("=" * 52)
    print(f"  Sur la tablette, entre :")
    print(f"    Adresse : {ip}")
    print(f"    Code    : {CODE}")
    print()
    print("  Laisse cette fenêtre ouverte (tu peux la réduire).")
    if DRYRUN:
        print("  [mode test : les touches sont affichées, pas envoyées]")
    print("=" * 52, flush=True)
    async with websockets.serve(handler, "0.0.0.0", PORT, ping_interval=20, ping_timeout=20, max_size=4096):
        log(f"En attente de la tablette sur le port {PORT}…")
        await asyncio.Future()


def first_run_questions():
    """Au premier lancement de l'exe : proposer le démarrage avec Windows."""
    if not getattr(sys, "frozen", False) or sys.platform != "win32":
        return
    f = startup_file()
    marker = os.path.join(os.path.dirname(config_path()), "asked")
    if os.path.exists(marker) or (f and os.path.exists(f)):
        return
    try:
        ans = input("Lancer Stream Deck PC automatiquement au démarrage de Windows ? (O/N) ").strip().lower()
    except EOFError:
        ans = "n"
    set_autostart(ans.startswith("o"))
    open(marker, "w").close()
    print("C'est noté." if ans.startswith("o") else "OK, tu le lanceras à la main.")


if __name__ == "__main__":
    if "--no-autostart" in sys.argv:
        print("Démarrage automatique désactivé." if set_autostart(False) else "Rien à faire.")
        sys.exit(0)
    first_run_questions()
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
    except OSError as e:
        print(f"Impossible d'ouvrir le port {PORT} : {e}. Stream Deck PC est peut-être déjà ouvert.")
        input("Appuie sur Entrée pour fermer.")
