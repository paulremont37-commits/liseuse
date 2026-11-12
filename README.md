# Liseuse

Transforme une tablette Android (Galaxy Tab S7 FE) en liseuse de mangas, BD, webtoons et livres, façon Kindle : la tablette s'allume directement sur la bibliothèque.

- Formats : CBZ, ZIP, PDF, EPUB, images (JPG, PNG, WEBP).
- Tout est stocké sur la tablette et se lit hors ligne. Aucun manga n'est fourni.
- L'interface se met à jour toute seule depuis ce repo.
- Extensions perso : un fichier `.js` sur GitHub ajoute une source en ligne au catalogue (lecture en ligne ou téléchargement). Voir [extensions/README.md](extensions/README.md).

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

## Stream Deck

La même coque fait aussi un Stream Deck : la tablette devient une grille de gros boutons qui envoient des raccourcis à ton PC Windows en Wi-Fi (Discord, Voicemod, Medal, musique, volume).

| Fichier (page Releases) | Où l'installer |
| --- | --- |
| `stream-deck.apk` | Sur la tablette |
| `StreamDeckPC.exe` | Sur le PC (Windows) |

1. Sur le PC, lance `StreamDeckPC.exe`. Windows peut afficher « Windows a protégé votre ordinateur » : clique sur **Informations complémentaires**, puis **Exécuter quand même**. Si le pare-feu demande, autorise les **réseaux privés**.
2. La fenêtre affiche une adresse et un code à 6 chiffres. Tape-les sur la tablette.
3. Pour assigner un bouton dans Discord, Voicemod ou Medal : clique sur le champ du raccourci dans le logiciel, puis appuie sur le bouton de la tablette.

Le programme PC n'exécute que des raccourcis clavier et des touches multimédia : aucune commande ni aucun fichier ne peut être lancé depuis la tablette, et il faut le code pour s'y connecter.

## Dogues (appli de fan LOSC, non officielle)

`dogues.apk` : actus du LOSC (titres de Google Actualités, mis à jour toutes les 10 min), prochain match avec compte à rebours, direct (score, buts, cartons, changements), compo officielle, calendrier, classement de Ligue 1, buteurs du LOSC et effectif. Notifications pour les buts, le score final, la compo et les grosses actus.

Les données de matchs viennent de l'API publique d'ESPN : gratuit, sans compte ni clé. Mise à jour toutes les 10 min, toutes les 4 min autour d'un match, toutes les 2 min pendant. Aucune source gratuite ne donne les blessés : l'infirmerie reprend les actus taguées blessure ou suspension.

Les articles s'ouvrent en mode lecture : la page du média est chargée en coulisse, le texte est extrait avec [Readability](https://github.com/mozilla/readability) de Mozilla (licence Apache 2.0, `losc/lib/`) puis remis en page sans pubs. Les articles réservés aux abonnés ne sont pas extraits : ils restent sur le site du média.

Aucun logo ni élément officiel du club n'est utilisé.
