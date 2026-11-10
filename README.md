# Liseuse

Transforme une tablette Android (Galaxy Tab S7 FE) en liseuse de mangas, BD, webtoons et livres, façon Kindle : la tablette s'allume directement sur la bibliothèque.

- Formats : CBZ, ZIP, PDF, EPUB, images (JPG, PNG, WEBP).
- Tout est stocké sur la tablette et se lit hors ligne. Aucun manga n'est fourni.
- L'interface se met à jour toute seule depuis ce repo.

## Installer sur la tablette

1. Sur la tablette, ouvre la page [Releases](../../releases/latest) et télécharge `liseuse.apk`.
2. Ouvre le fichier. Android demande d'autoriser l'installation depuis le navigateur : accepte, puis installe.
3. Appuie sur le bouton d'accueil. Android demande quel écran d'accueil utiliser : choisis **Liseuse**, puis **Toujours**.

Si la question n'apparaît pas : Réglages → Applications → Choisir les applications par défaut → Écran d'accueil → Liseuse.

## Utilisation

- **Bouton retour** : ferme le lecteur ou la fenêtre ouverte.
- **Bouton d'accueil** : revient à la bibliothèque.
- **Mode avancé** (Wi-Fi, réglages Android, écran d'accueil Samsung, mises à jour) : appui long de 3 secondes sur le logo « liseuse. » en haut à gauche.
- Les barres d'Android sont cachées ; un glissement depuis le bord de l'écran les fait réapparaître.

## Comment c'est construit

| Dossier | Rôle |
| --- | --- |
| `web/` | L'interface (HTML, bibliothèques JSZip et PDF.js, polices). Publiée sur GitHub Pages à chaque modification. |
| `android/` | La coque Android : écran d'accueil plein écran qui affiche l'interface, télécharge ses mises à jour et donne accès aux réglages. |
| `.github/workflows/` | `pages.yml` publie l'interface, `apk.yml` compile l'APK et le met dans Releases. |

Modifier `web/` met à jour la tablette au prochain lancement de la liseuse (ou via « Rechercher une mise à jour » dans le mode avancé). Modifier `android/` produit un nouvel APK à réinstaller.

La clé de signature (`android/liseuse.keystore`) est dans le repo pour que chaque APK puisse remplacer le précédent. C'est une appli perso installée à la main : ne pas réutiliser cette clé pour autre chose.
