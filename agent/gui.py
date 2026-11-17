"""
Fenêtre de Stream Deck PC (même DA néon que la tablette) et icône près de l'horloge.

Fermer la fenêtre la range près de l'horloge : le Stream Deck continue de marcher.
« Quitter » (dans la fenêtre ou en clic droit sur l'icône) l'arrête vraiment.
"""
import base64
import os
import sys
import threading
import webbrowser

import webview

try:
    import pystray
    from PIL import Image
except ImportError:  # pas d'icône près de l'horloge : la fenêtre se réduit simplement
    pystray = None

GUIDE = "https://github.com/paulremont37-commits/liseuse#stream-deck"


def res(name):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, name)


def page():
    with open(res("ui.html"), encoding="utf-8") as f:
        html = f.read()
    try:
        with open(res("bricolage-800.woff2"), "rb") as f:
            font = base64.b64encode(f.read()).decode()
        html = html.replace("/*FONT*/", f"url(data:font/woff2;base64,{font}) format('woff2'),")
    except OSError:
        html = html.replace("/*FONT*/", "")
    return html


class Api:
    """Fonctions appelées par la page (pywebview.api.…). Attributs privés : pywebview n'expose que le public."""

    def __init__(self, app):
        self._app = app

    def state(self, since=0):
        return self._app.st.snapshot(int(since or 0))

    def new_code(self):
        return self._app.st.new_code()

    def set_autostart(self, on):
        self._app.st.answer_autostart(bool(on))
        self._app.refresh_tray()
        return self._app.st.snapshot()["autostart"]

    def open_guide(self):
        webbrowser.open(GUIDE)

    def hide(self):
        self._app.hide()

    def quit(self):
        threading.Thread(target=self._app.quit, daemon=True).start()


class App:
    def __init__(self, st, tray_only):
        self.st = st
        self.quitting = False
        self.told = False
        self.icon = None
        self.window = webview.create_window(
            st.snapshot()["app"], html=page(), js_api=Api(self),
            width=980, height=640, min_size=(760, 540), background_color="#000000",
            hidden=tray_only and pystray is not None,
        )
        self.window.events.closing += self.on_closing

    # --- fenêtre
    def on_closing(self):
        if self.quitting or pystray is None:
            return True
        self.hide()
        return False  # on annule la fermeture : la fenêtre se range près de l'horloge

    def hide(self):
        self.window.hide()
        if self.icon and not self.told:
            self.told = True
            try:
                self.icon.notify("Le Stream Deck continue de marcher. Clique sur l'icône près de l'horloge pour rouvrir.", "Stream Deck PC")
            except Exception:
                pass

    def show(self, *_):
        self.window.show()
        self.window.restore()

    def quit(self, *_):
        self.quitting = True
        if self.icon:
            try:
                self.icon.stop()
            except Exception:
                pass
        self.window.destroy()

    # --- icône près de l'horloge
    def tray_title(self):
        s = self.st.snapshot()
        n = len(s["tablets"])
        return "Stream Deck PC · " + ("tablette connectée" if n == 1 else f"{n} tablettes connectées" if n else "en attente de la tablette")

    def refresh_tray(self):
        if self.icon:
            try:
                self.icon.title = self.tray_title()
                self.icon.update_menu()
            except Exception:
                pass

    def start_tray(self):
        if pystray is None:
            return
        def toggle(_icon, _item):
            self.st.answer_autostart(not self.st.autostart())
            self.window.evaluate_js("window.refreshNow && window.refreshNow()")

        menu = pystray.Menu(
            pystray.MenuItem("Ouvrir Stream Deck PC", self.show, default=True),
            pystray.MenuItem("Lancer avec Windows", toggle, checked=lambda _i: self.st.autostart()),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Quitter", self.quit),
        )
        self.icon = pystray.Icon("StreamDeckPC", Image.open(res("icon.ico")), self.tray_title(), menu)
        self.icon.run_detached()

        def keep_title():
            last = None
            while not self.quitting:
                t = self.tray_title()
                if t != last:
                    last = t
                    self.refresh_tray()
                threading.Event().wait(2)

        threading.Thread(target=keep_title, daemon=True).start()


def message_box(text):
    if sys.platform == "win32":
        import ctypes
        ctypes.windll.user32.MessageBoxW(None, text, "Stream Deck PC", 0x10)
    else:
        print(text)


def run(st, start_server, tray_only=False):
    start_server(st)
    try:
        app = App(st, tray_only)
        app.start_tray()
        webview.start()
    except Exception as e:  # WebView2 absent, etc. : on le dit au lieu de rien afficher
        message_box("Impossible d'ouvrir la fenêtre de Stream Deck PC.\n\n"
                    f"{e}\n\nLance StreamDeckPC.exe --console pour utiliser l'ancienne version.")
