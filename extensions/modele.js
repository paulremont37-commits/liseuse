// Modèle d'extension pour la liseuse.
// 1. Copie ce fichier, change id / name, puis remplis les fonctions pour ta source.
// 2. Mets-le sur GitHub et colle son lien dans Catalogue → Ajouter une extension.
// Mode d'emploi complet : extensions/README.md

liseuse.register({
  id: "mon-extension",            // unique, lettres/chiffres/._- (sert à reconnaître les mises à jour)
  name: "Mon extension",           // nom affiché dans le catalogue
  version: "1.0.0",                // change-le à chaque modif, il s'affiche dans la notif de mise à jour
  lang: "fr",
  kind: "manga",                   // type par défaut des titres : manga, bd, webtoon ou livre
  site: "https://example.com",
  icon: "",                        // lien d'une image carrée (optionnel)
  description: "Ce que propose cette source.",
  headers: {},                     // en-têtes ajoutés aux images (ex. { Referer: "https://example.com/" })

  // Liste des titres mis en avant. page commence à 1.
  // Renvoie { items: [{ id, title, cover, subtitle }], more: true/false }
  async popular(page) {
    const r = await liseuse.http("https://example.com/api/populaires?page=" + page);
    const data = await r.json();
    return {
      items: data.list.map(t => ({ id: t.slug, title: t.titre, cover: t.image })),
      more: data.list.length > 0
    };
  },

  // Optionnel : derniers ajouts, même format que popular.
  // async latest(page) { ... },

  // Optionnel : recherche, même format.
  async search(query, page) {
    const r = await liseuse.http("https://example.com/recherche?q=" + encodeURIComponent(query) + "&page=" + page);
    const doc = liseuse.html(await r.text(), r.url);   // page HTML → document qu'on peut parcourir
    const items = [...doc.querySelectorAll(".resultat")].map(el => ({
      id: el.querySelector("a").getAttribute("href"),
      title: el.querySelector("h3").textContent.trim(),
      cover: el.querySelector("img").src            // les liens sont complétés grâce à r.url
    }));
    return { items, more: !!doc.querySelector(".page-suivante") };
  },

  // Fiche d'un titre (item = un objet renvoyé par popular / latest / search).
  // Chaque chapitre a soit des pages (fonction pages ci-dessous), soit un fichier :
  //   { id, title, file: "https://…/tome1.cbz", format: "cbz" }   (cbz, pdf ou epub)
  async details(item) {
    const r = await liseuse.http("https://example.com/api/titre/" + item.id);
    const t = await r.json();
    return {
      title: t.titre,
      cover: t.image,
      author: t.auteur,
      description: t.resume,
      status: t.termine ? "Terminé" : "En cours",
      kind: "manga",
      chapters: t.chapitres.map(c => ({ id: c.id, title: "Chapitre " + c.numero, date: c.date }))
    };
  },

  // Pages d'un chapitre, dans l'ordre : une liste de liens d'images
  // (ou d'objets { url, headers } si une image a besoin d'en-têtes à elle).
  async pages(chapter, item) {
    const r = await liseuse.http("https://example.com/api/chapitre/" + chapter.id);
    const c = await r.json();
    return c.images;
  }
});
