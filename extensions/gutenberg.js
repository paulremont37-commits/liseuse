// Extension d'exemple pour la liseuse : les livres du domaine public du Projet Gutenberg,
// via l'API publique gratuite Gutendex (https://gutendex.com). Les livres arrivent en EPUB.
liseuse.register({
  id: "gutenberg",
  name: "Projet Gutenberg",
  version: "1.0.0",
  lang: "fr",
  kind: "livre",
  site: "https://www.gutenberg.org",
  icon: "https://www.gutenberg.org/gutenberg/pg-logo-129x80.png",
  description: "Plus de 70 000 livres du domaine public, en EPUB. Catalogue en français par défaut.",

  api: "https://gutendex.com/books/",

  async list(params) {
    const q = new URLSearchParams(params);
    const r = await liseuse.http(this.api + "?" + q);
    if (!r.ok) throw new Error("Gutendex répond " + r.status);
    const data = await r.json();
    return {
      items: data.results.map(b => ({
        id: b.id,
        title: b.title,
        cover: b.formats["image/jpeg"] || "",
        subtitle: (b.authors[0] && b.authors[0].name) || ""
      })),
      more: !!data.next
    };
  },

  popular(page) { return this.list({ languages: "fr", page }); },
  latest(page) { return this.list({ languages: "fr", sort: "descending", page }); },
  search(query, page) { return this.list({ search: query, page }); },

  async details(item) {
    const r = await liseuse.http(this.api + item.id + "/");
    const b = await r.json();
    const epub = b.formats["application/epub3+zip"] || b.formats["application/epub+zip"];
    return {
      title: b.title,
      cover: b.formats["image/jpeg"] || item.cover,
      author: b.authors.map(a => a.name).join(", "),
      description: (b.summaries && b.summaries[0]) || (b.subjects || []).slice(0, 6).join(" · "),
      kind: "livre",
      chapters: epub ? [{ id: "epub", title: "Livre complet (EPUB)", file: epub, format: "epub" }] : []
    };
  }
});
