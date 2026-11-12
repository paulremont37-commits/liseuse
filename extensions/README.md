# Extensions de la liseuse

Une extension apprend à la liseuse à lire une source en ligne : son catalogue, sa recherche, les chapitres d'un titre et leurs pages. C'est un seul fichier `.js`, hébergé sur GitHub (ou n'importe quel lien https).

À n'utiliser qu'avec des sources que tu as le droit de lire et de télécharger : ton propre serveur, des contenus libres, des sites qui le permettent.

## Installer

1. Mets ton fichier sur GitHub.
2. Sur la tablette : **Catalogue → Ajouter une extension**, colle le lien du fichier (le lien normal de GitHub marche, l'appli le transforme en lien « Raw »).
3. L'appli télécharge le fichier, vérifie qu'il s'enregistre bien, puis l'ajoute au catalogue.

Pour essayer, installe l'exemple : `https://github.com/paulremont37-commits/liseuse/blob/main/extensions/gutenberg.js` (livres du domaine public).

## Mises à jour

Toutes les 6 h, l'appli relit le lien de chaque extension. Si le fichier a changé, elle installe la nouvelle version et affiche une notification avec le numéro de `version`. Pour ne pas attendre : **Gérer → Mettre à jour**.

## Le format

```js
liseuse.register({
  id: "mon-extension",      // obligatoire, unique : lettres, chiffres, . _ -
  name: "Mon extension",     // obligatoire
  version: "1.0.0",
  lang: "fr",
  kind: "manga",             // manga, bd, webtoon ou livre (type par défaut des titres)
  site: "https://…",
  icon: "https://…/icone.png",
  description: "…",
  headers: { Referer: "https://…/" },   // ajoutés au téléchargement des images

  async popular(page) {},           // au moins une des trois
  async latest(page) {},
  async search(query, page) {},
  async details(item) {},           // obligatoire
  async pages(chapter, item) {}     // obligatoire si tes chapitres sont faits d'images
});
```

### `popular(page)`, `latest(page)`, `search(query, page)`

`page` commence à 1. Renvoie :

```js
{ items: [{ id: "abc", title: "Titre", cover: "https://…/couv.jpg", subtitle: "Auteur" }], more: true }
```

`more: true` affiche un bouton « Charger la suite ». Tu peux aussi renvoyer directement le tableau `items`.

### `details(item)`

`item` est l'objet que tu as renvoyé dans la liste (avec ses champs en plus si tu en as mis). Renvoie :

```js
{
  title: "Titre", cover: "https://…", author: "…", description: "…", status: "En cours",
  kind: "manga",
  chapters: [
    { id: "12", title: "Chapitre 12", date: "2026-11-01" },                 // pages via pages()
    { id: "t1", title: "Tome 1", file: "https://…/tome1.cbz", format: "cbz" } // fichier complet : cbz, pdf ou epub
  ]
}
```

### `pages(chapter, item)`

Renvoie la liste des images du chapitre, dans l'ordre : `["https://…/1.jpg", "https://…/2.jpg"]`. Une image qui a besoin d'en-têtes particuliers peut être un objet `{ url, headers }`.

## Les outils

- `await liseuse.http(url, { method, headers, body })` : fait une requête et renvoie `{ status, ok, url, text(), json() }`. Pas de blocage CORS : la requête part de l'appli, avec les cookies de la session.
- `liseuse.html(texte, baseUrl)` : transforme du HTML en document (`querySelector`, etc.). Avec `baseUrl`, les liens relatifs sont complétés (`img.src`, `a.href`).
- `liseuse.log(...)` : écrit dans le journal, visible dans **Gérer → Journal** sur la tablette.

## Sécurité

L'extension tourne dans un cadre isolé : elle n'a accès ni à ta bibliothèque, ni aux fichiers, ni au reste de l'appli. Elle ne peut qu'échanger avec la liseuse par les fonctions ci-dessus.

## Tester sur le Mac

Ouvre `web/index.html` dans Chrome avec un petit serveur local (`python3 -m http.server` à la racine du repo), puis installe ton extension avec un lien `http://localhost:8000/extensions/ton-fichier.js`. Hors de l'appli Android, les requêtes passent par le navigateur : seuls les sites qui autorisent CORS répondent.
