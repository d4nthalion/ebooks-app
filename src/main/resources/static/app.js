"use strict";

/* =====================================================================
   Biblioteca: se piden todos los libros una vez y las vistas (inicio,
   sagas, autores, búsqueda) se montan aqui. La dirección (#/sagas,
   #/saga/<nombre>...) decide la vista, asi que Atras funciona.
   ===================================================================== */

const state = {
  books: [],
  byId: new Map(),
  sagas: [], // { key, name, authors, books (ordenados), hue }
  sagaByKey: new Map(),
  authors: [], // { key, name, books, sagas, hue }
  authorByKey: new Map(),
  loaded: false,
};

const $ = (id) => document.getElementById(id);
const main = $("main");

// --- Utilidades -------------------------------------------------------------

const keyOf = (text) => (text ?? "").trim().toLocaleLowerCase("es");
const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;

/** Tono estable (0-359) a partir de un texto: cada saga y autor tiene su color. */
function hueOf(text) {
  let h = 0;
  for (const ch of keyOf(text)) h = (h * 31 + ch.codePointAt(0)) >>> 0;
  return h % 360;
}

function formatSize(bytes) {
  if (!bytes) return "";
  const mb = bytes / (1024 * 1024);
  return mb >= 1 ? `${mb.toLocaleString("es", { maximumFractionDigits: 1 })} MB` : `${Math.round(bytes / 1024)} KB`;
}

const formatIndex = (index) => String(index).replace(".", ",");

function coverUrl(book) {
  // lastModified cambia al editar el epub: asi no se queda la portada vieja en cache
  return `/api/books/${book.id}/cover?v=${book.lastModified}`;
}

/** Crea un elemento: h("a", { href: "#", class: "x" }, hijo1, "texto"...) */
function h(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [name, value] of Object.entries(attrs)) {
    if (value == null || value === false) continue;
    if (name === "class") node.className = value;
    else if (name === "style") node.setAttribute("style", value);
    else if (name.startsWith("on")) node.addEventListener(name.slice(2), value);
    else node.setAttribute(name, value === true ? "" : value);
  }
  for (const child of children.flat()) {
    if (child == null || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

function icon(name) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("class", "icon");
  svg.setAttribute("aria-hidden", "true");
  const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
  use.setAttribute("href", `#i-${name}`);
  svg.append(use);
  return svg;
}

function announce(text) {
  const live = $("announcer");
  live.textContent = "";
  setTimeout(() => (live.textContent = text), 50);
}

function setStatus(node, text, kind) {
  node.textContent = text ?? "";
  node.classList.toggle("is-error", kind === "error");
  node.classList.toggle("is-ok", kind === "ok");
}

/** fetch que devuelve el JSON o lanza un Error con el mensaje del servidor. */
async function api(url, options) {
  let res;
  try {
    res = await fetch(url, options);
  } catch {
    throw new Error("No hay conexión con la biblioteca. Comprueba la red y vuelve a probar.");
  }
  if (res.redirected && res.url.includes("/login")) {
    location.href = "/login.html";
    throw new Error("La sesión ha caducado.");
  }
  const data = await res.json().catch(() => null);
  if (!res.ok) throw new Error(data?.error ?? `La biblioteca respondió con un error (${res.status}).`);
  if (data === null) throw new Error("Respuesta inesperada de la biblioteca. Recarga la página.");
  return data;
}

const sagaHref = (saga) => `#/saga/${encodeURIComponent(saga.key)}`;
const authorHref = (author) => `#/autor/${encodeURIComponent(author.key)}`;

// --- Datos ------------------------------------------------------------------

async function loadData() {
  const books = await api("/api/books");
  state.books = books;
  state.byId = new Map(books.map((b) => [b.id, b]));

  const sagas = new Map();
  const authors = new Map();
  for (const book of books) {
    if (book.series) {
      const key = keyOf(book.series);
      if (!sagas.has(key)) sagas.set(key, { key, names: new Map(), books: [] });
      const saga = sagas.get(key);
      saga.books.push(book);
      saga.names.set(book.series, (saga.names.get(book.series) ?? 0) + 1);
    }
    const authorKey = keyOf(book.author || "Desconocido");
    if (!authors.has(authorKey)) authors.set(authorKey, { key: authorKey, name: book.author || "Desconocido", books: [] });
    authors.get(authorKey).books.push(book);
  }

  state.sagas = [...sagas.values()].map((s) => {
    // el nombre mas repetido manda (por si hay mayusculas distintas)
    const name = [...s.names.entries()].sort((a, b) => b[1] - a[1])[0][0];
    const books = s.books.sort(byVolume);
    const authorNames = [...new Set(books.map((b) => b.author).filter(Boolean))];
    return { key: s.key, name, books, authors: authorNames, hue: hueOf(name), recent: Math.max(...books.map((b) => b.lastModified)) };
  });
  state.sagas.sort((a, b) => a.name.localeCompare(b.name, "es"));
  state.sagaByKey = new Map(state.sagas.map((s) => [s.key, s]));

  state.authors = [...authors.values()].map((a) => ({
    ...a,
    hue: hueOf(a.name),
    sagas: state.sagas.filter((s) => s.books.some((b) => keyOf(b.author || "Desconocido") === a.key)),
  }));
  state.authors.sort((a, b) => a.name.localeCompare(b.name, "es"));
  state.authorByKey = new Map(state.authors.map((a) => [a.key, a]));

  fillDatalist($("authorOptions"), state.authors.map((a) => a.name));
  fillDatalist($("seriesOptions"), state.sagas.map((s) => s.name));
  state.loaded = true;
}

function byVolume(a, b) {
  const ia = a.seriesIndex == null ? Infinity : Number(a.seriesIndex);
  const ib = b.seriesIndex == null ? Infinity : Number(b.seriesIndex);
  return ia - ib || a.title.localeCompare(b.title, "es");
}

function fillDatalist(list, values) {
  list.replaceChildren(...values.map((value) => h("option", { value })));
}

const sagaOf = (book) => (book.series ? state.sagaByKey.get(keyOf(book.series)) : null);
const authorOf = (book) => state.authorByKey.get(keyOf(book.author || "Desconocido"));

// --- Piezas -----------------------------------------------------------------

function coverEl(book) {
  const cover = h("div", { class: "cover" });
  if (book.hasCover) {
    cover.append(h("img", { src: coverUrl(book), alt: "", loading: "lazy", decoding: "async" }));
  } else {
    cover.classList.add("cover-made");
    cover.style.setProperty("--h", hueOf(book.author || book.title));
    cover.append(h("span", { class: "cm-title" }, book.title), h("span", { class: "cm-author" }, book.author ?? ""));
  }
  return cover;
}

function bookCard(book, { showAuthor = true } = {}) {
  return h(
    "button",
    { type: "button", class: "book", onclick: () => openBook(book.id) },
    coverEl(book),
    h("span", { class: "book-title" }, book.title),
    showAuthor ? h("span", { class: "book-author" }, book.author ?? "") : null
  );
}

/** Fila horizontal con flechas (las flechas son para raton; con teclado se recorre con Tab). */
function track(items, label) {
  const list = h("div", { class: "track", role: "list", "aria-label": label }, items.map((item) => h("div", { role: "listitem" }, item)));
  const prev = h("button", { class: "track-arrow prev", type: "button", tabindex: "-1", "aria-hidden": "true" }, icon("left"));
  const next = h("button", { class: "track-arrow next", type: "button", tabindex: "-1", "aria-hidden": "true" }, icon("right"));
  const step = (dir) => list.scrollBy({ left: dir * list.clientWidth * 0.8, behavior: "smooth" });
  prev.addEventListener("click", () => step(-1));
  next.addEventListener("click", () => step(1));
  const update = () => {
    prev.disabled = list.scrollLeft < 8;
    next.disabled = list.scrollLeft + list.clientWidth > list.scrollWidth - 8;
  };
  list.addEventListener("scroll", update, { passive: true });
  requestAnimationFrame(update);
  return h("div", { class: "track-wrap" }, prev, list, next);
}

function shelf(title, books, { link, meta } = {}) {
  return h(
    "section",
    { class: "shelf", "aria-label": title },
    h(
      "div",
      { class: "shelf-head" },
      h("h2", { class: "shelf-title" }, title),
      meta ? h("span", { class: "shelf-meta" }, meta) : null,
      link ? h("a", { class: "shelf-link", href: link.href }, link.text) : null
    ),
    track(books.map((b) => bookCard(b)), title)
  );
}

/** Volumenes de una saga en orden, con hueco visible si falta alguno. */
function sagaVolumes(saga) {
  const items = [];
  let expected = 1;
  for (const book of saga.books) {
    const index = book.seriesIndex == null ? null : Number(book.seriesIndex);
    if (index != null) {
      // los que faltan antes de este (un 3,5 deja ver antes el hueco del 3)
      if (expected < index) {
        // los seguidos que faltan van en un solo hueco: "Faltan del 1 al 4"
        const to = Number.isInteger(index) ? index - 1 : Math.floor(index);
        items.push({ gap: expected, gapTo: to });
        expected = to + 1;
      }
      if (Number.isInteger(index)) expected = index + 1;
    }
    items.push({ book, index });
  }
  return items;
}

const gapNumber = (item) => (item.gap === item.gapTo ? `${item.gap}` : `${item.gap}-${item.gapTo}`);
const gapText = (item) =>
  item.gap === item.gapTo ? `Falta el libro ${item.gap}` : `Faltan del ${item.gap} al ${item.gapTo}`;

function volumeItem(item, saga) {
  if (item.gap) {
    return h(
      "div",
      { class: "volume volume-gap" },
      h("span", { class: "volume-num" }, h("b", {}, gapNumber(item))),
      h("div", { class: "gap-slot" }, gapText(item))
    );
  }
  const card = bookCard(item.book, { showAuthor: saga.authors.length > 1 });
  if (item.index != null) {
    card.setAttribute("aria-label", `Libro ${formatIndex(item.index)}: ${item.book.title}`);
  }
  return h(
    "div",
    { class: "volume" },
    h(
      "span",
      { class: "volume-num", "aria-hidden": "true" },
      item.index != null ? h("b", {}, formatIndex(item.index)) : h("span", {}, "Sin número")
    ),
    card
  );
}

function sagaShelf(saga, { withAuthor = true } = {}) {
  const count = plural(saga.books.length, "libro", "libros");
  const meta = withAuthor && saga.authors.length ? `${saga.authors.join(", ")}, ${count}` : count;
  return h(
    "section",
    { class: "saga-shelf", style: `--h: ${saga.hue}`, "aria-label": `Saga ${saga.name}` },
    h(
      "div",
      { class: "shelf-head" },
      h("h3", { class: "shelf-title" }, h("a", { href: sagaHref(saga) }, saga.name)),
      h("span", { class: "shelf-meta" }, meta)
    ),
    track(sagaVolumes(saga).map((item) => volumeItem(item, saga)), `Libros de ${saga.name}`)
  );
}

function sagaChip(book, { link = true } = {}) {
  const saga = sagaOf(book);
  if (!saga) return null;
  const text = book.seriesIndex != null
    ? `Libro ${formatIndex(book.seriesIndex)} de ${saga.name}`
    : `De la saga ${saga.name}`;
  return link
    ? h("a", { class: "saga-chip", href: sagaHref(saga), style: `--h: ${saga.hue}` }, text)
    : h("span", { class: "saga-chip", style: `--h: ${saga.hue}` }, text);
}

function viewHead(title, sub, actions) {
  return h(
    "div",
    { class: "view-head" },
    h("div", {}, h("h1", { class: "view-title", tabindex: "-1" }, title), sub ? h("p", { class: "view-sub" }, sub) : null),
    actions ? h("div", { class: "actions" }, actions) : null
  );
}

function emptyState(title, text) {
  return h("div", { class: "empty-state" }, h("h2", { tabindex: "-1" }, title), h("p", {}, text));
}

// --- Vistas -----------------------------------------------------------------

function viewHome() {
  if (!state.books.length) {
    return [emptyState("Tu biblioteca está vacía", "Copia tus libros en la carpeta de ebooks del NAS y pulsa Actualizar.")];
  }
  const recent = [...state.books].sort((a, b) => b.lastModified - a.lastModified);
  const latest = recent[0];
  const parts = [feature(latest)];

  parts.push(shelf("Recientes", recent.slice(1, 15), { link: { href: "#/todos", text: "Ver todos" } }));

  // En Inicio solo las sagas con varios libros: una estanteria con un solo tomo no aporta
  const sagas = state.sagas.filter((s) => s.books.length > 1).sort((a, b) => b.recent - a.recent);
  if (sagas.length) {
    parts.push(
      h(
        "div",
        { class: "shelf-head" },
        h("h2", { class: "shelf-title" }, "Sagas"),
        h("span", { class: "shelf-meta" }, "primero las que tienen novedades"),
        h("a", { class: "shelf-link", href: "#/sagas" }, "Ver todas las sagas")
      ),
      sagas.slice(0, 8).map((s) => sagaShelf(s))
    );
  }

  const loose = recent.filter((b) => !b.series);
  if (loose.length) parts.push(shelf("Sin saga", loose.slice(0, 20)));
  return parts;
}

function feature(book) {
  const author = authorOf(book);
  const backdrop = h("div", { class: "feature-backdrop", "aria-hidden": "true" });
  if (book.hasCover) backdrop.style.backgroundImage = `url("${coverUrl(book)}")`;
  return h(
    "section",
    { class: "feature", "aria-labelledby": "featureTitle" },
    backdrop,
    h(
      "div",
      { class: "feature-cover" },
      h("button", { type: "button", onclick: () => openBook(book.id), "aria-label": `Abrir la ficha de ${book.title}` }, coverEl(book))
    ),
    h(
      "div",
      { class: "feature-text" },
      h("p", { class: "feature-when" }, "Lo último en llegar a tu biblioteca"),
      h("h1", { class: "feature-title", id: "featureTitle", tabindex: "-1" }, book.title),
      h("p", { class: "feature-author" }, author ? h("a", { href: authorHref(author) }, author.name) : book.author),
      sagaChip(book),
      h(
        "div",
        { class: "actions" },
        h("button", { type: "button", class: "btn btn-primary", onclick: () => openBook(book.id) }, "Ver ficha"),
        h("a", { class: "btn btn-secondary", href: `/api/books/${book.id}/download` }, icon("download"), "Descargar")
      )
    )
  );
}

function viewSagas() {
  if (!state.sagas.length) {
    return [viewHead("Sagas"), emptyState("Aún no hay sagas", "Abre un libro, pulsa Editar datos y escribe su saga y su número.")];
  }
  const full = state.sagas.filter((s) => s.books.length > 1);
  const single = state.sagas.filter((s) => s.books.length === 1);
  const parts = [viewHead("Sagas", `${plural(state.sagas.length, "saga", "sagas")} en tu biblioteca`)];
  if (full.length) parts.push(h("div", { class: "saga-grid" }, full.map(boxset)));
  if (single.length) {
    parts.push(
      h(
        "section",
        { class: "single-sagas", "aria-labelledby": "singleTitle" },
        h("h2", { class: "shelf-title", id: "singleTitle" }, "Con un solo libro"),
        h("p", { class: "view-sub" }, "Sagas de las que solo tienes un tomo, o carpetas que no son una saga. Puedes corregirlas desde la ficha del libro."),
        h(
          "ul",
          { class: "single-list" },
          single.map((saga) =>
            h(
              "li",
              {},
              h(
                "button",
                { type: "button", class: "single-item", style: `--h: ${saga.hue}`, onclick: () => openBook(saga.books[0].id) },
                coverEl(saga.books[0]),
                h(
                  "span",
                  {},
                  h("span", { class: "single-name" }, saga.name),
                  h("span", { class: "single-book" }, saga.books[0].title)
                )
              )
            )
          )
        )
      )
    );
  }
  return parts;
}

function boxset(saga) {
  return h(
    "a",
    { class: "boxset", href: sagaHref(saga), style: `--h: ${saga.hue}` },
    h("div", { class: "boxset-covers", "aria-hidden": "true" }, saga.books.slice(0, 3).map(coverEl)),
    h("span", { class: "boxset-name" }, saga.name),
    h(
      "span",
      { class: "boxset-meta" },
      h("span", { class: "boxset-count" }, plural(saga.books.length, "libro", "libros")),
      saga.authors.length ? `, ${saga.authors.join(", ")}` : ""
    )
  );
}

function viewSaga(key) {
  const saga = state.sagaByKey.get(key);
  if (!saga) return notFound("Esa saga ya no existe", "Puede que la hayas renombrado. Vuelve a la lista de sagas.");

  const volumes = sagaVolumes(saga);
  const gaps = volumes.filter((v) => v.gap);
  const sub = [
    saga.authors.join(", "),
    plural(saga.books.length, "libro", "libros"),
    gaps.length ? gaps.map((g) => gapText(g).toLocaleLowerCase("es")).join(", ") : null,
  ].filter(Boolean).join(", ");

  const header = h(
    "div",
    { class: "page-hue", style: `--h: ${saga.hue}` },
    viewHead(saga.name, sub, [
      h("button", { type: "button", class: "btn btn-quiet btn-sm", onclick: () => openRename("series", saga.name, saga.books) }, icon("edit"), "Renombrar saga"),
    ])
  );

  const list = h(
    "ol",
    { class: "volume-list", "aria-label": `Libros de ${saga.name} en orden` },
    volumes.map((item) => {
      if (item.gap) {
        return h(
          "li",
          { class: "volume-row is-gap", style: `--h: ${saga.hue}` },
          h("span", { class: "vr-num" }, gapNumber(item)),
          h("span", { class: "vr-cover", "aria-hidden": "true" }),
          h("span", {}, item.gap === item.gapTo ? `No tienes el libro ${item.gap} de esta saga.` : `No tienes los libros del ${item.gap} al ${item.gapTo} de esta saga.`)
        );
      }
      const { book, index } = item;
      return h(
        "li",
        { class: "volume-row", style: `--h: ${saga.hue}` },
        h("span", { class: "vr-num" }, index != null ? formatIndex(index) : h("small", {}, "Sin número")),
        coverEl(book),
        h(
          "button",
          { type: "button", class: "vr-open", onclick: () => openBook(book.id) },
          h("span", { class: "vr-title" }, book.title),
          h("span", { class: "vr-meta", style: "display:block" }, `${book.author ?? ""}${book.author ? ", " : ""}${book.extension.toUpperCase()}, ${formatSize(book.sizeBytes)}`)
        ),
        h("div", { class: "vr-actions" }, h("a", { class: "btn btn-quiet btn-sm", href: `/api/books/${book.id}/download`, "aria-label": `Descargar ${book.title}` }, icon("download"), "Descargar"))
      );
    })
  );
  return [header, list];
}

function viewAuthors() {
  return [
    viewHead("Autores", plural(state.authors.length, "autor", "autores")),
    h(
      "ul",
      { class: "author-list" },
      state.authors.map((author) =>
        h(
          "li",
          {},
          h(
            "a",
            { class: "author-row", href: authorHref(author), style: `--h: ${author.hue}` },
            h("span", { class: "author-initial", "aria-hidden": "true" }, author.name.trim().charAt(0).toLocaleUpperCase("es")),
            h(
              "span",
              {},
              h("span", { class: "author-name", style: "display:block" }, author.name),
              h(
                "span",
                { class: "author-meta" },
                [plural(author.books.length, "libro", "libros"), author.sagas.length ? plural(author.sagas.length, "saga", "sagas") : null].filter(Boolean).join(", ")
              )
            ),
            h("span", { class: "author-minis", "aria-hidden": "true" }, author.books.slice(0, 4).map(coverEl))
          )
        )
      )
    ),
  ];
}

function viewAuthor(key) {
  const author = state.authorByKey.get(key);
  if (!author) return notFound("Ese autor ya no está", "Puede que lo hayas renombrado o unificado con otro. Vuelve a la lista de autores.");

  const loose = author.books.filter((b) => !b.series).sort((a, b) => a.title.localeCompare(b.title, "es"));
  const sub = [plural(author.books.length, "libro", "libros"), author.sagas.length ? plural(author.sagas.length, "saga", "sagas") : null].filter(Boolean).join(", ");
  const parts = [
    h(
      "div",
      { class: "page-hue", style: `--h: ${author.hue}` },
      viewHead(author.name, sub, [
        h("button", { type: "button", class: "btn btn-quiet btn-sm", onclick: () => openRename("author", author.name, author.books) }, icon("edit"), "Renombrar autor"),
      ])
    ),
  ];
  if (author.sagas.length) {
    parts.push(h("h2", { class: "shelf-title", style: "padding: 0 var(--gutter); margin: 8px 0 16px" }, "Sagas"));
    parts.push(author.sagas.map((s) => sagaShelf(s, { withAuthor: s.authors.length > 1 })));
  }
  if (loose.length) {
    parts.push(
      h("h2", { class: "shelf-title", style: "padding: 0 var(--gutter); margin: 8px 0 16px" }, author.sagas.length ? "Otros libros" : "Libros"),
      h("div", { class: "grid" }, loose.map((b) => bookCard(b, { showAuthor: false })))
    );
  }
  return parts;
}

function viewAll() {
  return [
    viewHead("Todos los libros", `${plural(state.books.length, "libro", "libros")}, por autor y saga`),
    h("div", { class: "grid" }, state.books.map((b) => bookCard(b))),
  ];
}

function viewSearch(query) {
  const q = keyOf(query);
  const results = state.books.filter((b) =>
    [b.title, b.author, b.series].some((field) => keyOf(field).includes(q))
  );
  if (!results.length) {
    return [viewHead(`Búsqueda: ${query}`), emptyState("Sin resultados", "Prueba con otra palabra del título, el autor o la saga.")];
  }
  return [
    viewHead(`Búsqueda: ${query}`, plural(results.length, "libro encontrado", "libros encontrados")),
    h("div", { class: "grid" }, results.map((b) => bookCard(b))),
  ];
}

function notFound(title, text) {
  return [emptyState(title, text)];
}

// --- Rutas ------------------------------------------------------------------

function currentRoute() {
  const [section, param] = location.hash.replace(/^#\/?/, "").split("/");
  return { section: section || "", param: param ? decodeURIComponent(param) : "" };
}

function render({ focus = true } = {}) {
  if (!state.loaded) return;
  const { section, param } = currentRoute();
  const views = {
    "": viewHome,
    sagas: viewSagas,
    saga: () => viewSaga(param),
    autores: viewAuthors,
    autor: () => viewAuthor(param),
    todos: viewAll,
    buscar: () => viewSearch(param),
  };
  const view = views[section] ?? viewHome;
  main.replaceChildren(...[view()].flat(Infinity));

  const navKey = { "": "home", sagas: "sagas", saga: "sagas", autores: "authors", autor: "authors", todos: "all" }[section];
  document.querySelectorAll("[data-nav]").forEach((a) => {
    if (a.dataset.nav === navKey) a.setAttribute("aria-current", "page");
    else a.removeAttribute("aria-current");
  });
  if (section !== "buscar" && document.activeElement !== $("search")) $("search").value = "";

  const heading = main.querySelector("h1, h2");
  document.title = heading ? `${heading.textContent} - Biblioteca` : "Biblioteca";
  if (focus) {
    window.scrollTo(0, 0);
    if (section !== "buscar") heading?.focus({ preventScroll: true });
    announce(document.title);
  }
}

window.addEventListener("hashchange", () => render());

// Búsqueda: escribir muestra resultados sin llenar el historial
let searchTimer;
$("search").addEventListener("input", (e) => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    const q = e.target.value.trim();
    const target = q ? `#/buscar/${encodeURIComponent(q)}` : "#/";
    if (currentRoute().section === "buscar") history.replaceState(null, "", target);
    else history.pushState(null, "", target);
    render({ focus: false });
    if (q) announce(main.querySelector(".view-sub")?.textContent ?? "Sin resultados");
  }, 250);
});
$("searchForm").addEventListener("submit", (e) => {
  e.preventDefault();
  main.querySelector("h1")?.focus();
});

// --- Ficha del libro --------------------------------------------------------

let currentBook = null;
const bookDialog = $("bookDialog");

function openBook(id) {
  const book = state.byId.get(id);
  if (!book) return;
  fillBookDialog(book);
  $("editPanel").hidden = true;
  $("bdEdit").setAttribute("aria-expanded", "false");
  setStatus($("bdStatus"), "");
  if (!bookDialog.open) bookDialog.showModal();
  bookDialog.scrollTop = 0;
}

function fillBookDialog(book) {
  currentBook = book;
  const author = authorOf(book);
  $("bdTitle").textContent = book.title;
  $("bdAuthor").replaceChildren(author ? h("a", { href: authorHref(author), "data-nav-close": "" }, author.name) : book.author ?? "");
  const chip = sagaChip(book);
  chip?.setAttribute("data-nav-close", "");
  $("bdSaga").replaceChildren(chip ?? "");
  $("bdSaga").hidden = !chip;
  $("bdFile").textContent = `${book.extension.toUpperCase()}, ${formatSize(book.sizeBytes)}`;
  $("bdCover").replaceChildren(coverEl(book));
  $("bdBackdrop").style.backgroundImage = book.hasCover ? `url("${coverUrl(book)}")` : "none";
  $("bdDownload").href = `/api/books/${book.id}/download`;
  $("bdEditActions").hidden = book.extension !== "epub";

  const saga = sagaOf(book);
  const section = $("bdSiblingsSection");
  section.hidden = !saga || saga.books.length < 2;
  if (saga && saga.books.length > 1) {
    section.style.setProperty("--h", saga.hue);
    section.style.setProperty("--hue", `oklch(var(--hue-l) var(--hue-c) ${saga.hue})`);
    $("bdSiblingsTitle").textContent = `En la saga ${saga.name}`;
    $("bdSiblings").replaceChildren(
      ...saga.books.map((b) => {
        const item = volumeItem({ book: b, index: b.seriesIndex == null ? null : Number(b.seriesIndex) }, saga);
        if (b.id === book.id) {
          item.classList.add("is-current");
          item.querySelector("button").setAttribute("aria-current", "true");
        }
        return item;
      })
    );
  }
}

// Los enlaces de la ficha (autor, saga) cambian de vista y cierran el diálogo
bookDialog.addEventListener("click", (e) => {
  if (e.target.closest("[data-nav-close]")) bookDialog.close();
});

$("bdKindle").addEventListener("click", async () => {
  const button = $("bdKindle");
  button.disabled = true;
  setStatus($("bdStatus"), "Enviando a tu Kindle...");
  try {
    await api(`/api/books/${currentBook.id}/send-to-kindle`, { method: "POST" });
    setStatus($("bdStatus"), "Enviado. Llegará a tu Kindle en unos minutos.", "ok");
  } catch (err) {
    setStatus($("bdStatus"), err.message, "error");
  } finally {
    button.disabled = false;
  }
});

// Editar datos
$("bdEdit").addEventListener("click", () => {
  const panel = $("editPanel");
  const opening = panel.hidden;
  panel.hidden = !opening;
  $("bdEdit").setAttribute("aria-expanded", String(opening));
  if (!opening) return;
  $("editTitle").value = currentBook.title ?? "";
  $("editAuthor").value = currentBook.author ?? "";
  $("editSeries").value = currentBook.series ?? "";
  $("editSeriesIndex").value = currentBook.seriesIndex ? formatIndex(currentBook.seriesIndex) : "";
  setStatus($("editStatus"), "");
  $("editTitle").focus();
  panel.scrollIntoView({ block: "nearest", behavior: "smooth" });
});

$("editCancel").addEventListener("click", () => {
  $("editPanel").hidden = true;
  $("bdEdit").setAttribute("aria-expanded", "false");
  $("bdEdit").focus();
});

$("editPanel").addEventListener("submit", async (e) => {
  e.preventDefault();
  // Solo se envia lo que ha cambiado (null = no tocar)
  const changed = (value, original) => {
    const v = value.trim();
    return v === (original ?? "") ? null : v;
  };
  const index = $("editSeriesIndex").value.trim().replace(",", ".");
  const body = {
    title: changed($("editTitle").value, currentBook.title),
    author: changed($("editAuthor").value, currentBook.author),
    series: changed($("editSeries").value, currentBook.series),
    seriesIndex: index === (currentBook.seriesIndex ?? "") ? null : index,
  };
  if (Object.values(body).every((v) => v === null)) {
    setStatus($("editStatus"), "No has cambiado nada.");
    return;
  }
  $("editSave").disabled = true;
  setStatus($("editStatus"), "Guardando en el epub...");
  try {
    const book = await api(`/api/books/${currentBook.id}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    await refreshAfterEdit(book);
    $("editPanel").hidden = true;
    $("bdEdit").setAttribute("aria-expanded", "false");
    setStatus($("bdStatus"), "Datos guardados.", "ok");
    $("bdEdit").focus();
  } catch (err) {
    setStatus($("editStatus"), err.message, "error");
  } finally {
    $("editSave").disabled = false;
  }
});

async function refreshAfterEdit(book) {
  await loadData();
  render({ focus: false });
  if (book && bookDialog.open) fillBookDialog(state.byId.get(book.id) ?? book);
}

// --- Cambiar portada --------------------------------------------------------

const coverDialog = $("coverDialog");

$("bdCoverBtn").addEventListener("click", () => {
  $("coverLead").textContent = `Para «${currentBook.title}». Pulsa una portada para guardarla dentro del epub.`;
  $("coverQuery").value = [currentBook.title, currentBook.author].filter(Boolean).join(" ");
  $("coverResults").replaceChildren();
  $("coverFile").value = "";
  coverDialog.showModal();
  searchCovers();
});

async function searchCovers() {
  const q = $("coverQuery").value.trim();
  if (!q) return;
  $("coverResults").replaceChildren();
  setStatus($("coverStatus"), "Buscando portadas...");
  try {
    const candidates = await api(`/api/covers/search?q=${encodeURIComponent(q)}`);
    setStatus(
      $("coverStatus"),
      candidates.length
        ? `${plural(candidates.length, "portada encontrada", "portadas encontradas")}.`
        : "No hay portadas para esa búsqueda. Prueba solo con el título, o sube una imagen."
    );
    $("coverResults").replaceChildren(
      ...candidates.map((c) => {
        const lang = c.language === "spa" ? "en español" : c.language ? `idioma ${c.language}` : null;
        const description = [c.publisher, lang].filter(Boolean).join(", ");
        const img = h("img", { src: c.thumbnailUrl, alt: "", loading: "lazy" });
        const option = h(
          "button",
          {
            type: "button",
            class: "cover-option",
            "aria-label": `Usar la portada de ${c.title ?? "esta edición"}${description ? ", " + description : ""}`,
            onclick: () => applyCover(`/api/books/${currentBook.id}/cover/openlibrary/${c.coverId}`, { method: "POST" }),
          },
          h("div", { class: "cover" }, img),
          h("span", { "aria-hidden": "true" }, description || c.title || "")
        );
        // Open Library a veces devuelve una imagen vacia: se quita esa opcion
        img.addEventListener("load", () => img.naturalWidth < 10 && option.remove());
        img.addEventListener("error", () => option.remove());
        return option;
      })
    );
  } catch (err) {
    setStatus($("coverStatus"), err.message, "error");
  }
}

$("coverSearchForm").addEventListener("submit", (e) => {
  e.preventDefault();
  searchCovers();
});

$("coverFile").addEventListener("change", () => {
  const file = $("coverFile").files[0];
  if (!file) return;
  const form = new FormData();
  form.append("file", file);
  applyCover(`/api/books/${currentBook.id}/cover`, { method: "POST", body: form });
});

async function applyCover(url, options) {
  setStatus($("coverStatus"), "Guardando la portada en el epub...");
  $("coverResults").classList.add("busy");
  try {
    const book = await api(url, options);
    coverDialog.close();
    await refreshAfterEdit(book);
    setStatus($("bdStatus"), "Portada cambiada.", "ok");
  } catch (err) {
    setStatus($("coverStatus"), err.message, "error");
  } finally {
    $("coverResults").classList.remove("busy");
    $("coverFile").value = "";
  }
}

// --- Renombrar autor / saga en bloque ----------------------------------------

const renameDialog = $("renameDialog");
let renameTarget = null;

function openRename(kind, value, books) {
  renameTarget = { kind, value };
  const epubs = books.filter((b) => b.extension === "epub").length;
  const others = books.length - epubs;
  $("renameTitle").textContent = kind === "author" ? "Renombrar autor" : "Renombrar saga";
  $("renameInfo").textContent =
    `Se cambiará en ${plural(epubs, "libro", "libros")}. ` +
    (kind === "author"
      ? "Para unificar dos autores, elige de la lista el nombre que quieres conservar."
      : "Deja el nombre vacío para quitar la saga de esos libros.") +
    (others ? ` ${plural(others, "libro no es", "libros no son")} epub y no se pueden cambiar.` : "");
  $("renameInput").value = value;
  $("renameInput").setAttribute("list", kind === "author" ? "authorOptions" : "seriesOptions");
  setStatus($("renameStatus"), "");
  renameDialog.showModal();
  $("renameInput").select();
}

$("renameForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const to = $("renameInput").value.trim();
  if (to === renameTarget.value) {
    renameDialog.close();
    return;
  }
  if (!to && renameTarget.kind === "author") {
    setStatus($("renameStatus"), "Escribe el nombre del autor.", "error");
    return;
  }
  $("renameSave").disabled = true;
  setStatus($("renameStatus"), "Cambiando...");
  try {
    const url = renameTarget.kind === "author" ? "/api/authors/rename" : "/api/series/rename";
    const result = await api(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ from: renameTarget.value, to }),
    });
    await loadData();
    const parts = [`Cambiado en ${plural(result.updated, "libro", "libros")}.`];
    if (result.unchanged) parts.push(`${plural(result.unchanged, "ya lo tenía", "ya lo tenían")}.`);
    if (result.skipped.length) parts.push(`${plural(result.skipped.length, "no es epub", "no son epub")}.`);
    if (result.failed.length) parts.push(`No se pudieron cambiar: ${result.failed.join("; ")}`);
    const ok = !result.failed.length;
    setStatus($("renameStatus"), parts.join(" "), ok ? "ok" : "error");

    // La página en la que estabamos lleva el nombre viejo: se va a la del nombre nuevo
    const section = renameTarget.kind === "author" ? "autor" : "saga";
    if (currentRoute().section === section) {
      location.hash = to ? `#/${section}/${encodeURIComponent(keyOf(to))}` : "#/sagas";
    } else {
      render({ focus: false });
    }
    if (ok) setTimeout(() => renameDialog.open && renameDialog.close(), 1200);
  } catch (err) {
    setStatus($("renameStatus"), err.message, "error");
  } finally {
    $("renameSave").disabled = false;
  }
});

// --- Kindle -----------------------------------------------------------------

const kindleDialog = $("kindleDialog");

$("kindleBtn").addEventListener("click", async () => {
  setStatus($("kindleStatus"), "");
  kindleDialog.showModal();
  try {
    const { email } = await api("/api/me/kindle-email");
    $("kindleEmail").value = email;
  } catch (err) {
    setStatus($("kindleStatus"), err.message, "error");
  }
});

$("kindleForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  $("kindleSave").disabled = true;
  try {
    await api("/api/me/kindle-email", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: $("kindleEmail").value.trim() }),
    });
    setStatus($("kindleStatus"), "Dirección guardada.", "ok");
  } catch (err) {
    setStatus($("kindleStatus"), err.message, "error");
  } finally {
    $("kindleSave").disabled = false;
  }
});

// --- Diálogos: cerrar con el boton X o pulsando fuera ------------------------

document.querySelectorAll("dialog").forEach((dialog) => {
  dialog.addEventListener("click", (e) => {
    if (e.target.closest("[data-close]")) dialog.close();
    // clic en el fondo oscuro (fuera de la caja)
    if (e.target === dialog) {
      const r = dialog.getBoundingClientRect();
      const inside = e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom;
      if (!inside) dialog.close();
    }
  });
});

// --- Actualizar -------------------------------------------------------------

$("refreshBtn").addEventListener("click", async () => {
  const button = $("refreshBtn");
  button.disabled = true;
  button.classList.add("busy");
  announce("Leyendo la carpeta de libros...");
  try {
    await api("/api/index/refresh", { method: "POST" });
    await loadData();
    render({ focus: false });
    announce(`Biblioteca actualizada: ${plural(state.books.length, "libro", "libros")}.`);
  } catch (err) {
    announce(err.message);
  } finally {
    button.disabled = false;
    button.classList.remove("busy");
  }
});

// --- Arranque ---------------------------------------------------------------

loadData()
  .then(() => render({ focus: false }))
  .catch((err) => {
    main.replaceChildren(emptyState("No se pudo cargar la biblioteca", err.message));
  });
