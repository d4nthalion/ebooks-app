"use strict";

/* =====================================================================
   BiblioLocal: se piden los libros, los universos y quien soy una vez,
   y las vistas (inicio, ficha, sagas, universos, autores, peticiones,
   administracion) se montan aqui. La direccion (#/libro/<id>,
   #/saga/<nombre>...) decide la vista, asi que Atras funciona.
   ===================================================================== */

const state = {
  me: { username: "", admin: false, updates: [] },
  books: [],
  byId: new Map(),
  sagas: [], // { key, name, authors, books (ordenados), hue, recent }
  sagaByKey: new Map(),
  authors: [], // { key, name, books, sagas, hue }
  authorByKey: new Map(),
  universes: [], // { id, name, hue, sagas, books (sueltos), all }
  universeById: new Map(),
  loaded: false,
  reqHint: null, // saga y numero cuando se pide desde un hueco de una saga
  dlContext: null, // peticion para la que se busca en aMule
};

const $ = (id) => document.getElementById(id);
const main = $("main");
const HERO_MS = 8000;
const reduceMotion = matchMedia("(prefers-reduced-motion: reduce)").matches;

// --- Utilidades -------------------------------------------------------------

const keyOf = (text) => (text ?? "").trim().toLocaleLowerCase("es");
/** Clave sin tildes ni signos: «El Archivo de las Tormentas» = «el archivo de las tormentas» */
const normKey = (text) => (text ?? "").normalize("NFD").replace(/\p{M}/gu, "").toLowerCase().replace(/[^a-z0-9]+/g, "");
const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
const formatIndex = (index) => String(index).replace(".", ",");

/** Tono estable (0-359) a partir de un texto: cada saga, autor y universo tiene su color. */
function hueOf(text) {
  let h = 0;
  for (const ch of normKey(text)) h = (h * 31 + ch.codePointAt(0)) >>> 0;
  return h % 360;
}

function formatSize(bytes) {
  if (!bytes) return "";
  const mb = bytes / (1024 * 1024);
  return mb >= 1 ? `${mb.toLocaleString("es", { maximumFractionDigits: 1 })} MB` : `${Math.round(bytes / 1024)} KB`;
}

function timeAgo(ms) {
  if (!ms) return "";
  const s = Math.max(0, (Date.now() - ms) / 1000);
  if (s < 60) return "ahora mismo";
  if (s < 3600) return `hace ${plural(Math.round(s / 60), "minuto", "minutos")}`;
  if (s < 86400) return `hace ${plural(Math.round(s / 3600), "hora", "horas")}`;
  const d = Math.round(s / 86400);
  if (d === 1) return "ayer";
  if (d < 30) return `hace ${d} días`;
  return new Date(ms).toLocaleDateString("es", { day: "numeric", month: "long", year: "numeric" });
}

function whenShort(ms) {
  const d = new Date(ms);
  const today = new Date();
  const yesterday = new Date(Date.now() - 86400000);
  const hm = d.toLocaleTimeString("es", { hour: "2-digit", minute: "2-digit" });
  if (d.toDateString() === today.toDateString()) return `Hoy ${hm}`;
  if (d.toDateString() === yesterday.toDateString()) return `Ayer ${hm}`;
  return `${d.toLocaleDateString("es", { day: "numeric", month: "short" })} ${hm}`;
}

const coverUrl = (book) => `/api/books/${book.id}/cover?v=${book.lastModified}`;
const olCover = (id, size = "M") => `https://covers.openlibrary.org/b/id/${id}-${size}.jpg`;

/** Crea un elemento: h("a", { href: "#", class: "x" }, hijo1, "texto"...) */
function h(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [name, value] of Object.entries(attrs)) {
    if (value == null || value === false) continue;
    if (name === "class") node.className = value;
    else if (name === "style") node.setAttribute("style", value);
    else if (name.startsWith("on") && typeof value === "function") node.addEventListener(name.slice(2), value);
    else node.setAttribute(name, value === true ? "" : value);
  }
  for (const child of children.flat(Infinity)) {
    if (child == null || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

function icon(name, size) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("class", "icon");
  svg.setAttribute("aria-hidden", "true");
  if (size) svg.setAttribute("style", `width:${size}px;height:${size}px`);
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
  if (!node) return;
  node.textContent = text ?? "";
  node.classList.toggle("is-error", kind === "error");
  node.classList.toggle("is-ok", kind === "ok");
}

/** Aviso flotante. kind: ok | error | info */
function toast(title, text, kind = "ok", link) {
  const el = h(
    "div",
    { class: `toast ${kind}`, role: kind === "error" ? "alert" : "status" },
    h("span", { class: "ti" }, icon(kind === "error" ? "close" : kind === "ok" ? "check" : "info", 16)),
    h("div", {}, h("b", {}, title), text ? h("span", {}, text) : null, link ? h("div", {}, h("a", { href: link.href }, link.text)) : null)
  );
  $("toasts").append(el);
  setTimeout(() => {
    el.classList.add("out");
    setTimeout(() => el.remove(), 300);
  }, kind === "error" ? 7000 : 4500);
}

/** fetch que devuelve el JSON o lanza un Error con el mensaje del servidor. */
async function api(url, options = {}) {
  let res;
  try {
    res = await fetch(url, options);
  } catch {
    throw new Error("No hay conexión con la biblioteca. Comprueba la red y vuelve a probar.");
  }
  if (res.status === 401 || (res.redirected && res.url.includes("/login"))) {
    location.href = "/login.html";
    throw new Error("La sesión ha caducado.");
  }
  if (res.status === 204) return null;
  const data = await res.json().catch(() => null);
  if (res.status === 403) throw new Error("Eso solo lo pueden hacer los administradores.");
  if (!res.ok) throw new Error(data?.error ?? `La biblioteca respondió con un error (${res.status}).`);
  return data;
}
const json = (method, body) => ({ method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

const sagaHref = (saga) => `#/saga/${encodeURIComponent(saga.key)}`;
const authorHref = (author) => `#/autor/${encodeURIComponent(author.key)}`;
const universeHref = (u) => `#/universo/${encodeURIComponent(u.id)}`;
const bookHref = (book) => `#/libro/${encodeURIComponent(book.id)}`;

// --- Datos ------------------------------------------------------------------

async function loadData() {
  const [books, universes, me, order] = await Promise.all([api("/api/books"), api("/api/universes"), api("/api/me"), api("/api/saga-order")]);
  state.me = me;
  state.order = order ?? {};
  state.books = books;
  state.byId = new Map(books.map((b) => [b.id, b]));

  const sagas = new Map();
  const authors = new Map();
  for (const book of books) {
    if (book.series) {
      const key = normKey(book.series);
      if (!sagas.has(key)) sagas.set(key, { key, names: new Map(), books: [] });
      const saga = sagas.get(key);
      saga.books.push(book);
      saga.names.set(book.series, (saga.names.get(book.series) ?? 0) + 1);
    }
    const authorKey = normKey(book.author || "Desconocido");
    if (!authors.has(authorKey)) authors.set(authorKey, { key: authorKey, name: book.author || "Desconocido", books: [] });
    authors.get(authorKey).books.push(book);
  }

  state.sagas = [...sagas.values()].map((s) => {
    // el nombre mas repetido manda (por si hay mayusculas distintas)
    const name = [...s.names.entries()].sort((a, b) => b[1] - a[1])[0][0];
    const list = s.books.sort(byVolume);
    const authorNames = [...new Set(list.map((b) => b.author).filter(Boolean))];
    return { key: s.key, name, books: list, authors: authorNames, hue: hueOf(name), recent: Math.max(...list.map((b) => b.lastModified)) };
  });
  state.sagas.sort((a, b) => a.name.localeCompare(b.name, "es"));
  state.sagaByKey = new Map(state.sagas.map((s) => [s.key, s]));

  state.authors = [...authors.values()].map((a) => ({
    ...a,
    books: a.books.sort((x, y) => y.lastModified - x.lastModified),
    hue: hueOf(a.name),
    sagas: state.sagas.filter((s) => s.books.some((b) => normKey(b.author || "Desconocido") === a.key)),
  }));
  state.authors.sort((a, b) => a.name.localeCompare(b.name, "es"));
  state.authorByKey = new Map(state.authors.map((a) => [a.key, a]));

  state.universes = universes.map((u) => {
    const uSagas = u.sagas.map((name) => state.sagaByKey.get(normKey(name))).filter(Boolean);
    const loose = u.books.map((id) => state.byId.get(id)).filter(Boolean);
    const all = [...new Set([...uSagas.flatMap((s) => s.books), ...loose])];
    return { id: u.id, name: u.name, raw: u, hue: hueOf(u.name), sagas: uSagas, books: loose, all, recent: Math.max(0, ...all.map((b) => b.lastModified)) };
  });
  state.universeById = new Map(state.universes.map((u) => [u.id, u]));

  fillDatalist($("authorOptions"), state.authors.map((a) => a.name));
  fillDatalist($("seriesOptions"), state.sagas.map((s) => s.name));
  updateChrome();
  state.loaded = true;
}

/**
 * Posicion de un libro en su saga: su numero o, si no tiene, la que se le dio
 * arrastrandolo (2,5 = entre el 2 y el 3). Sin ninguna, al final.
 */
function volumePos(book) {
  if (book.seriesIndex != null) return Number(book.seriesIndex);
  const manual = state.order?.[book.id];
  return manual && manual.saga === normKey(book.series) ? manual.pos : Infinity;
}

function byVolume(a, b) {
  return volumePos(a) - volumePos(b) || (a.seriesIndex == null) - (b.seriesIndex == null) || a.title.localeCompare(b.title, "es");
}

function fillDatalist(list, values) {
  list.replaceChildren(...values.map((value) => h("option", { value })));
}

const sagaOf = (book) => (book.series ? state.sagaByKey.get(normKey(book.series)) : null);
const authorOf = (book) => state.authorByKey.get(normKey(book.author || "Desconocido"));
const universeOfSaga = (saga) => state.universes.find((u) => u.sagas.includes(saga));
const universeOfBook = (book) => {
  const saga = sagaOf(book);
  return (saga && universeOfSaga(saga)) || state.universes.find((u) => u.books.includes(book));
};

/** Lo que cambia con quien eres: menus de admin, avisos, iniciales. */
function updateChrome() {
  const me = state.me;
  $("navAdmin").hidden = !me.admin;
  $("refreshBtn").hidden = !me.admin;
  $("navUniverses").hidden = !state.universes.length;
  $("meBtn").textContent = (me.username || "·").charAt(0).toUpperCase();
  $("meBtn").setAttribute("aria-label", `Tu cuenta: ${me.username}`);
  $("meName").textContent = me.username;
  $("meRole").textContent = me.admin ? "Administrador" : "Lector";
  const pending = me.pendingRequests ?? 0;
  $("adminBadge").hidden = !pending;
  $("adminBadge").textContent = pending;
  const updates = me.updates?.length ?? 0;
  $("reqBadge").hidden = !updates;
  $("reqBadge").textContent = updates;
}

// --- Piezas -----------------------------------------------------------------

function coverEl(book, extraClass = "") {
  const cover = h("div", { class: `cover ${extraClass}` });
  if (book.hasCover) {
    cover.append(h("img", { src: coverUrl(book), alt: "", loading: "lazy", decoding: "async" }));
  } else {
    cover.classList.add("cover-made");
    cover.style.setProperty("--h", hueOf(book.author || book.title));
    cover.append(h("span", { class: "cm-title" }, book.title), h("span", { class: "cm-author" }, book.author ?? ""));
  }
  return cover;
}

/** Portada de un libro que aun no esta en la biblioteca (Open Library o generada). */
function remoteCover(title, author, coverId, cls = "mini") {
  if (coverId) return h("div", { class: cls }, h("img", { src: olCover(coverId, "S"), alt: "", loading: "lazy" }));
  return h("div", { class: `${cls} cover-made`, style: `--h: ${hueOf(author || title)}` }, h("span", { class: "cm-title" }, title));
}

function bookCard(book, { showAuthor = true, i = 0 } = {}) {
  return h(
    "a",
    { class: "book", href: bookHref(book), style: `--i: ${i}` },
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

function shelf(title, items, { link, meta, hue, titleHref } = {}) {
  return h(
    "section",
    { class: `shelf reveal ${hue != null ? "hue" : ""}`, style: hue != null ? `--h: ${hue}` : null, "aria-label": title },
    h(
      "div",
      { class: "shelf-head" },
      h("h2", { class: "shelf-title" }, titleHref ? h("a", { href: titleHref, class: hue != null ? "saga-chip" : null }, title) : title),
      meta ? h("span", { class: "shelf-meta" }, meta) : null,
      link ? h("a", { class: "shelf-link", href: link.href }, link.text, " ›") : null
    ),
    track(items, title)
  );
}

/** Volumenes de una saga en orden, con hueco visible si falta alguno. */
function sagaVolumes(saga) {
  const items = [];
  let expected = 1;
  for (const book of saga.books) {
    const index = book.seriesIndex == null ? null : Number(book.seriesIndex);
    if (index != null) {
      if (expected < index) {
        // los seguidos que faltan van en un solo hueco: "Faltan del 1 al 4"
        const to = Number.isInteger(index) ? index - 1 : Math.floor(index);
        if (to >= expected) items.push({ gap: expected, gapTo: to });
        expected = to + 1;
      }
      if (Number.isInteger(index)) expected = index + 1;
    }
    items.push({ book, index });
  }
  return items;
}

const gapNumber = (item) => (item.gap === item.gapTo ? `${item.gap}` : `${item.gap}-${item.gapTo}`);
const gapText = (item) => (item.gap === item.gapTo ? `Falta el libro ${item.gap}` : `Faltan del ${item.gap} al ${item.gapTo}`);

/** Pedir el libro que falta en una saga: abre Peticiones con la busqueda y la saga apuntadas. */
function askForGap(saga, number) {
  state.reqHint = { series: saga.name, seriesIndex: String(number) };
  location.hash = `#/peticiones/${encodeURIComponent(`${saga.name} ${saga.authors[0] ?? ""}`.trim())}`;
}

function volumeItem(item, saga, i, currentId) {
  if (item.gap) {
    return h(
      "div",
      { class: "volume" },
      h("span", { class: "volume-num" }, h("b", {}, gapNumber(item))),
      h(
        "div",
        { class: "gap-slot" },
        h("span", {}, gapText(item)),
        h("button", { type: "button", class: "pill", onclick: () => askForGap(saga, item.gap) }, icon("plus", 15), "Pedirlo")
      )
    );
  }
  const card = bookCard(item.book, { showAuthor: saga.authors.length > 1, i });
  if (item.index != null) card.setAttribute("aria-label", `Libro ${formatIndex(item.index)}: ${item.book.title}`);
  if (item.book.id === currentId) card.setAttribute("aria-current", "true");
  return h(
    "div",
    { class: `volume ${item.book.id === currentId ? "is-current" : ""}` },
    h("span", { class: "volume-num", "aria-hidden": "true" }, item.index != null ? h("b", {}, formatIndex(item.index)) : h("span", {}, "Sin número")),
    card
  );
}

function sagaRow(saga, { withAuthor = true, currentId } = {}) {
  const count = plural(saga.books.length, "libro", "libros");
  const meta = withAuthor && saga.authors.length ? `${saga.authors.join(", ")} · ${count}` : count;
  return shelf(saga.name, sagaVolumes(saga).map((item, i) => volumeItem(item, saga, i, currentId)), {
    meta,
    hue: saga.hue,
    titleHref: sagaHref(saga),
  });
}

function sagaChip(book) {
  const saga = sagaOf(book);
  if (!saga) return null;
  const text = book.seriesIndex != null ? `Libro ${formatIndex(book.seriesIndex)} de ${saga.name}` : `De la saga ${saga.name}`;
  return h("a", { class: "saga-chip hue", href: sagaHref(saga), style: `--h: ${saga.hue}` }, text);
}

function boxset(saga, i = 0) {
  return h(
    "a",
    { class: "boxset hue", href: sagaHref(saga), style: `--h: ${saga.hue}; --i: ${i}` },
    h("div", { class: "fan", "aria-hidden": "true" }, saga.books.slice(0, 3).map((b) => coverEl(b))),
    h("span", { class: "boxset-name" }, saga.name),
    h("span", { class: "boxset-meta" }, h("span", { class: "boxset-count" }, plural(saga.books.length, "libro", "libros")), saga.authors.length ? ` · ${saga.authors.join(", ")}` : "")
  );
}

function universeCard(u, i = 0) {
  const covers = u.all.filter((b) => b.hasCover).slice(0, 4);
  const angles = [-8, -2, 4, 10];
  return h(
    "a",
    { class: "universe-card hue", href: universeHref(u), style: `--h: ${u.hue}; --i: ${i}` },
    h("div", { class: "uc-covers", "aria-hidden": "true" }, covers.map((b, j) => {
      const c = coverEl(b);
      c.style.setProperty("--r", `${angles[j]}deg`);
      return c;
    })),
    h("span", { class: "uc-kicker" }, "Universo"),
    h("span", { class: "uc-name" }, u.name),
    h("span", { class: "uc-meta" }, [u.sagas.length ? plural(u.sagas.length, "saga", "sagas") : null, plural(u.all.length, "libro", "libros")].filter(Boolean).join(" · "))
  );
}

function authorChip(author, i = 0) {
  const withCover = author.books.find((b) => b.hasCover);
  return h(
    "a",
    { class: "author-chip hue", href: authorHref(author), style: `--h: ${author.hue}; --i: ${i}` },
    h("span", { class: "author-ring", "aria-hidden": "true" }, withCover ? h("img", { src: coverUrl(withCover), alt: "", loading: "lazy" }) : null, h("span", {}, author.name.trim().charAt(0).toLocaleUpperCase("es"))),
    h("span", { class: "an" }, author.name),
    h("span", { class: "ac" }, plural(author.books.length, "libro", "libros"))
  );
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

function backButton() {
  return h("button", { type: "button", class: "back-btn", onclick: () => (history.length > 1 && state.navCount > 1 ? history.back() : (location.hash = "#/")) }, icon("left"), "Volver");
}

/** Fondo de una pagina con hero: la portada difuminada o, sin portada, el color. */
function heroBg(book, hue) {
  const bg = h("div", { class: "bg", "aria-hidden": "true" });
  if (book?.hasCover) bg.style.backgroundImage = `url("${coverUrl(book)}")`;
  else {
    bg.classList.add("tint");
    bg.style.setProperty("--h", hue ?? 260);
  }
  return bg;
}

// --- Inicio: hero rotativo y filas ----------------------------------------------

let cleanups = [];
const onLeave = (fn) => cleanups.push(fn);

function viewHome() {
  if (!state.books.length) {
    return [h("div", { style: "height: var(--topbar-h)" }), emptyState("Tu biblioteca está vacía", "Copia tus libros en la carpeta de ebooks del NAS y pulsa Actualizar.")];
  }
  const recent = [...state.books].sort((a, b) => b.lastModified - a.lastModified);
  const featured = [...recent.filter((b) => b.hasCover), ...recent.filter((b) => !b.hasCover)].slice(0, 6);
  const parts = [hero(featured)];

  const rows = [shelf("Recientes", recent.slice(0, 18).map((b, i) => bookCard(b, { i })), { link: { href: "#/todos", text: "Ver todos" } })];
  if (state.universes.length) {
    rows.push(shelf("Universos", [...state.universes].sort((a, b) => b.recent - a.recent).map(universeCard), { link: { href: "#/universos", text: "Ver todos" }, meta: "sagas que comparten mundo" }));
  }
  const sagas = state.sagas.filter((s) => s.books.length > 1).sort((a, b) => b.recent - a.recent);
  if (sagas.length) rows.push(shelf("Sagas", sagas.slice(0, 14).map(boxset), { link: { href: "#/sagas", text: "Ver todas" }, meta: "primero las que tienen novedades" }));
  rows.push(shelf("Autores", [...state.authors].sort((a, b) => b.books.length - a.books.length).slice(0, 16).map(authorChip), { link: { href: "#/autores", text: "Ver todos" } }));
  const loose = recent.filter((b) => !b.series);
  if (loose.length) rows.push(shelf("Libros sin saga", loose.slice(0, 20).map((b, i) => bookCard(b, { i }))));
  parts.push(h("div", { class: "rows" }, rows));
  return parts;
}

function hero(books) {
  let index = 0;
  let timer = null;
  let userPaused = reduceMotion;
  let hoverPaused = false;
  const slides = books.map((b, i) => {
    const s = h("div", { class: `hero-slide ${i === 0 ? "on" : ""}` });
    if (b.hasCover) s.style.backgroundImage = `url("${coverUrl(b)}")`;
    else s.style.background = `radial-gradient(60% 60% at 70% 40%, oklch(50% 0.14 ${hueOf(b.author || b.title)}), transparent 70%)`;
    return s;
  });
  const content = h("div", { class: "hero-content" });
  const coverBox = h("div", { class: "hero-cover" });
  const dots = books.map((b, i) => h("button", { type: "button", class: "hero-dot", "aria-label": `Libro ${i + 1} de ${books.length}: ${b.title}`, onclick: () => go(i, true) }, h("i")));
  const pauseBtn = h("button", { type: "button", class: "hero-pause", onclick: () => setPaused(!userPaused) });
  const section = h(
    "section",
    { class: "hero", "aria-roledescription": "carrusel", "aria-label": "Novedades", style: `--hero-ms: ${HERO_MS}ms` },
    h("div", { class: "hero-bg", "aria-hidden": "true" }, slides),
    content,
    coverBox,
    books.length > 1 ? h("div", { class: "hero-dots" }, dots, pauseBtn) : null
  );

  function fill(book, animate) {
    const author = authorOf(book);
    const chips = [
      sagaChip(book),
      h("span", {}, `${book.extension.toUpperCase()} · ${formatSize(book.sizeBytes)}`),
      h("span", {}, `Añadido ${timeAgo(book.lastModified)}`),
    ].filter(Boolean);
    content.replaceChildren(
      h("div", { class: "kicker" }, "Nuevo en tu biblioteca"),
      h(index === 0 && !animate ? "h1" : "h2", { class: "hero-title", tabindex: "-1" }, book.title),
      h("p", { class: "hero-author" }, author ? h("a", { href: authorHref(author) }, author.name) : book.author),
      h("div", { class: "meta-line" }, chips.flatMap((c, i) => (i ? [h("span", { class: "dot" }), c] : [c]))),
      h(
        "div",
        { class: "actions" },
        h("a", { class: "btn btn-white btn-lg", href: bookHref(book) }, icon("book"), "Ver ficha"),
        h("a", { class: "btn btn-ghost btn-lg", href: `/api/books/${book.id}/download` }, icon("download"), "Descargar"),
        h("button", { type: "button", class: "round", "aria-label": `Enviar ${book.title} a tu Kindle`, title: "Enviar a Kindle", onclick: () => sendToKindle(book) }, icon("mail"))
      )
    );
    coverBox.replaceChildren(h("a", { href: bookHref(book), "aria-label": `Abrir la ficha de ${book.title}`, tabindex: "-1" }, coverEl(book)));
    if (animate) {
      for (const el of [content, coverBox]) {
        el.classList.remove("swap");
        void el.offsetWidth;
        el.classList.add("swap");
      }
    }
  }

  function go(i, manual) {
    index = (i + books.length) % books.length;
    slides.forEach((s, j) => {
      s.classList.toggle("on", j === index);
      if (j === index) {
        s.style.animation = "none";
        void s.offsetWidth;
        s.style.animation = "";
      }
    });
    dots.forEach((d, j) => {
      d.setAttribute("aria-current", String(j === index));
      const bar = d.firstChild;
      bar.style.animation = "none";
      void bar.offsetWidth;
      bar.style.animation = "";
    });
    fill(books[index], true);
    schedule();
    if (manual) announce(`${books[index].title}, de ${books[index].author ?? ""}`);
  }

  function schedule() {
    clearTimeout(timer);
    const running = !userPaused && !hoverPaused && books.length > 1 && !document.hidden;
    section.classList.toggle("playing", running);
    section.classList.toggle("paused", !running);
    if (running) timer = setTimeout(() => go(index + 1), HERO_MS);
  }

  function setPaused(value) {
    userPaused = value;
    pauseBtn.replaceChildren(icon(value ? "play" : "pause", 15));
    pauseBtn.setAttribute("aria-label", value ? "Reanudar el carrusel" : "Pausar el carrusel");
    schedule();
  }

  section.addEventListener("mouseenter", () => { hoverPaused = true; schedule(); });
  section.addEventListener("mouseleave", () => { hoverPaused = false; schedule(); });
  section.addEventListener("focusin", () => { hoverPaused = true; schedule(); });
  section.addEventListener("focusout", (e) => { if (!section.contains(e.relatedTarget)) { hoverPaused = false; schedule(); } });
  const onVisibility = () => schedule();
  document.addEventListener("visibilitychange", onVisibility);
  onLeave(() => { clearTimeout(timer); document.removeEventListener("visibilitychange", onVisibility); });

  dots.forEach((d, j) => d.setAttribute("aria-current", String(j === 0)));
  fill(books[0], false);
  setPaused(userPaused);
  return section;
}

// --- Ficha del libro -------------------------------------------------------------

let currentBook = null;

function viewBook(id) {
  const book = state.byId.get(id);
  if (!book) return notFound("Ese libro ya no está", "Puede que se haya movido o renombrado en la carpeta. Vuelve al inicio.");
  currentBook = book;
  const author = authorOf(book);
  const saga = sagaOf(book);
  const universe = universeOfBook(book);
  const admin = state.me.admin;
  const editable = admin && book.extension === "epub";

  const chips = [
    h("span", { class: "chip" }, book.extension.toUpperCase()),
    h("span", { class: "chip" }, formatSize(book.sizeBytes)),
    h("span", { class: "chip" }, `Añadido ${timeAgo(book.lastModified)}`),
    universe ? h("a", { class: "chip hue-chip hue", href: universeHref(universe), style: `--h: ${universe.hue}` }, `Universo ${universe.name}`) : null,
  ];

  const parts = [
    h(
      "section",
      { class: "page-hero" },
      heroBg(book, hueOf(book.author || book.title)),
      backButton(),
      h(
        "div",
        { class: "detail" },
        h("div", { class: "detail-cover" }, coverEl(book)),
        h(
          "div",
          { class: "detail-text" },
          saga ? h("div", { style: "margin-bottom: 14px" }, sagaChip(book)) : null,
          h("h1", { class: "detail-title", tabindex: "-1" }, book.title),
          h("p", { class: "detail-author" }, author ? h("a", { href: authorHref(author) }, author.name) : book.author ?? ""),
          h("div", { class: "chips" }, chips),
          h(
            "div",
            { class: "actions" },
            h("a", { class: "btn btn-white btn-lg", href: `/api/books/${book.id}/download` }, icon("download"), "Descargar"),
            h("button", { type: "button", class: "btn btn-ghost btn-lg", onclick: () => sendToKindle(book) }, icon("mail"), "Enviar a Kindle"),
            admin ? h("button", { type: "button", class: "round", "aria-label": "Editar datos", title: "Editar datos", "aria-expanded": "false", "aria-controls": "editPanel", id: "editToggle", onclick: toggleEdit }, icon("edit")) : null,
            editable ? h("button", { type: "button", class: "round", "aria-label": "Cambiar portada", title: "Cambiar portada", onclick: () => openCoverDialog(book) }, icon("image")) : null
          )
        )
      )
    ),
  ];
  if (admin) parts.push(editPanel(book));
  if (saga && saga.books.length > 0) {
    const row = sagaRow(saga, { currentId: book.id, withAuthor: saga.authors.length > 1 });
    row.querySelector(".shelf-title a").textContent = `En la saga ${saga.name}`;
    parts.push(row);
  }
  if (universe) {
    const others = universe.all.filter((b) => b.id !== book.id && (!saga || sagaOf(b) !== saga)).slice(0, 18);
    if (others.length) parts.push(shelf(`Más del universo ${universe.name}`, others.map((b, i) => bookCard(b, { i })), { hue: universe.hue, titleHref: universeHref(universe) }));
  }
  if (author) {
    const more = author.books.filter((b) => b.id !== book.id && (!saga || sagaOf(b) !== saga)).slice(0, 18);
    if (more.length) parts.push(shelf(`Más de ${author.name}`, more.map((b, i) => bookCard(b, { showAuthor: false, i })), { link: { href: authorHref(author), text: "Ver autor" } }));
  }
  return parts;
}

/**
 * Campo combinado: se escribe a mano o se elige de la lista (con el boton se ve
 * entera; al escribir se filtra). Patron combobox de ARIA, con teclado.
 */
function combo({ id, label, value, options, placeholder, onInput }) {
  const input = h("input", {
    id, class: "input", type: "text", value: value ?? "", autocomplete: "off", placeholder,
    role: "combobox", "aria-autocomplete": "list", "aria-expanded": "false", "aria-controls": `${id}-list`,
  });
  const list = h("ul", { class: "combo-list", id: `${id}-list`, role: "listbox", "aria-label": typeof label === "string" ? label : id, hidden: true });
  const toggle = h("button", { type: "button", class: "combo-toggle", tabindex: "-1", "aria-label": "Ver todas las opciones" }, icon("right", 16));
  let active = -1;
  let shown = [];

  const close = () => {
    list.hidden = true;
    input.setAttribute("aria-expanded", "false");
    input.removeAttribute("aria-activedescendant");
    active = -1;
  };
  const open = (all) => {
    const q = normKey(input.value);
    shown = all || !q ? options : options.filter((o) => normKey(o).includes(q));
    const exact = options.some((o) => normKey(o) === q);
    list.replaceChildren(
      ...[
        ...shown.map((o, i) => h("li", { id: `${id}-opt-${i}`, role: "option", "aria-selected": String(normKey(o) === q), onmousedown: (e) => { e.preventDefault(); pick(o); } }, o)),
        q && !exact ? h("li", { class: "combo-new", role: "presentation" }, `«${input.value.trim()}» se creará al guardar`) : null,
        !shown.length && !q ? h("li", { class: "combo-new", role: "presentation" }, "Aún no hay ninguna: escribe el nombre") : null,
      ].filter(Boolean)
    );
    // Solo una lista abierta a la vez
    document.querySelectorAll(".combo-list:not([hidden])").forEach((other) => {
      if (other !== list) {
        other.hidden = true;
        other.parentElement.querySelector("[role=combobox]")?.setAttribute("aria-expanded", "false");
      }
    });
    list.hidden = false;
    input.setAttribute("aria-expanded", "true");
    active = -1;
  };
  const pick = (o) => {
    input.value = o;
    close();
    onInput?.(o);
  };
  const highlight = (i) => {
    const items = list.querySelectorAll('[role="option"]');
    if (!items.length) return;
    active = (i + items.length) % items.length;
    items.forEach((el, j) => el.classList.toggle("active", j === active));
    input.setAttribute("aria-activedescendant", items[active].id);
    items[active].scrollIntoView({ block: "nearest" });
  };

  input.addEventListener("input", () => { open(false); onInput?.(input.value); });
  input.addEventListener("focus", () => open(false));
  input.addEventListener("blur", () => setTimeout(close, 120));
  input.addEventListener("keydown", (e) => {
    if (e.key === "ArrowDown") { e.preventDefault(); if (list.hidden) open(false); highlight(active + 1); }
    else if (e.key === "ArrowUp") { e.preventDefault(); highlight(active - 1); }
    else if (e.key === "Enter" && !list.hidden && active >= 0) { e.preventDefault(); pick(shown[active]); }
    else if (e.key === "Escape" && !list.hidden) { e.preventDefault(); e.stopPropagation(); close(); }
  });
  toggle.addEventListener("mousedown", (e) => e.preventDefault());
  toggle.addEventListener("click", () => {
    if (list.hidden) { input.focus(); open(true); } else close();
  });
  return h("div", { class: "field" }, h("label", { for: id }, label), h("div", { class: "combo" }, input, toggle, list));
}

function editPanel(book) {
  const epub = book.extension === "epub";
  const saga = sagaOf(book);
  const field = (id, label, value, attrs = {}) =>
    h("div", { class: "field" }, h("label", { for: id }, label), h("input", { id, class: "input", type: "text", value: value ?? "", ...attrs }));
  const status = h("p", { class: "status", role: "status", id: "editStatus" });
  const save = h("button", { class: "btn btn-grad", type: "submit" }, "Guardar cambios");
  const universeHint = h("span", { class: "hint" });
  // A que se aplica el universo: a la saga entera si tiene saga, o solo al libro
  const updateHint = () => {
    const name = epub ? $("editSeries")?.value.trim() : book.series;
    universeHint.textContent = name ? ` (se aplica a toda la saga «${name}»)` : " (solo este libro; vacío si no tiene)";
  };
  const universeField = combo({
    id: "editUniverse",
    label: h("span", {}, "Universo", universeHint),
    value: universeOfBook(book)?.name ?? "",
    options: [...state.universes].map((u) => u.name).sort((a, b) => a.localeCompare(b, "es")),
    placeholder: "Ninguno",
  });
  const form = h(
    "form",
    { class: "panel edit-panel", id: "editPanel", hidden: true, onsubmit: (e) => saveEdit(e, book, save, status) },
    h("div", { class: "panel-head" }, h("h2", { class: "grow" }, "Editar datos"), h("span", { class: "hint" }, epub ? "Título, autor y saga se guardan dentro del epub" : "Este libro no es epub: solo se puede cambiar su universo")),
    h(
      "div",
      { class: "form-grid" },
      epub ? field("editTitle", "Título", book.title, { required: true }) : null,
      epub ? field("editAuthor", h("span", {}, "Autor ", h("span", { class: "hint" }, "(elige uno de la lista para que coincida)")), book.author, { list: "authorOptions", required: true }) : null,
      epub
        ? h(
            "div",
            { class: "field-row" },
            combo({ id: "editSeries", label: h("span", {}, "Saga ", h("span", { class: "hint" }, "(vacío si no tiene)")), value: book.series, options: state.sagas.map((s) => s.name), placeholder: "Sin saga", onInput: updateHint }),
            field("editSeriesIndex", "Número", book.seriesIndex ? formatIndex(book.seriesIndex) : "", { inputmode: "decimal" })
          )
        : null,
      universeField
    ),
    h("div", { class: "form-actions" }, save, h("button", { class: "btn btn-quiet", type: "button", onclick: toggleEdit }, "Cancelar")),
    status
  );
  queueMicrotask(updateHint);
  if (saga && !epub) universeHint.textContent = ` (se aplica a toda la saga «${saga.name}»)`;
  return form;
}

function toggleEdit() {
  const panel = $("editPanel");
  const opening = panel.hidden;
  panel.hidden = !opening;
  $("editToggle")?.setAttribute("aria-expanded", String(opening));
  if (opening) {
    ($("editTitle") ?? $("editUniverse")).focus();
    panel.scrollIntoView({ block: "center", behavior: reduceMotion ? "auto" : "smooth" });
  }
}

async function saveEdit(e, book, button, status) {
  e.preventDefault();
  const changed = (value, original) => {
    const v = value.trim();
    return v === (original ?? "") ? null : v;
  };
  let body = null;
  if (book.extension === "epub") {
    const index = $("editSeriesIndex").value.trim().replace(",", ".");
    body = {
      title: changed($("editTitle").value, book.title),
      author: changed($("editAuthor").value, book.author),
      series: changed($("editSeries").value, book.series),
      seriesIndex: index === (book.seriesIndex ?? "") ? null : index,
    };
    if (Object.values(body).every((v) => v === null)) body = null;
  }
  const universeName = $("editUniverse").value.trim();
  const universeChanged = normKey(universeName) !== normKey(universeOfBook(book)?.name ?? "");
  if (!body && !universeChanged) {
    setStatus(status, "No has cambiado nada.");
    return;
  }
  button.disabled = true;
  try {
    if (body) {
      setStatus(status, "Guardando en el epub…");
      await api(`/api/books/${book.id}`, json("PATCH", body));
      await loadData();
    }
    if (universeChanged) {
      // Con la saga ya guardada: el universo va a la saga (si tiene) o al libro
      const fresh = state.byId.get(book.id) ?? book;
      const saga = sagaOf(fresh);
      const target = saga ? { kind: "saga", saga } : { kind: "book", book: fresh };
      const existing = state.universes.find((u) => normKey(u.name) === normKey(universeName));
      await setUniverse(target, universeName ? (existing ? { id: existing.id } : { name: universeName }) : null);
    }
    await reloadAndRender();
    toast("Datos guardados", book.title);
  } catch (err) {
    setStatus(status, err.message, "error");
    button.disabled = false;
  }
}

async function reloadAndRender() {
  const y = scrollY;
  await loadData();
  render({ focus: false });
  scrollTo(0, y);
}

async function sendToKindle(book) {
  toast("Enviando a tu Kindle…", book.title, "info");
  try {
    await api(`/api/books/${book.id}/send-to-kindle`, { method: "POST" });
    toast("Enviado a tu Kindle", `${book.title} llegará en unos minutos.`);
  } catch (err) {
    if (/configurado/.test(err.message)) {
      toast("Falta tu dirección de Kindle", "Añádela y vuelve a probar.", "error");
      openKindleDialog();
    } else toast("No se pudo enviar", err.message, "error");
  }
}

// --- Sagas, universos, autores ---------------------------------------------------

function viewSagas() {
  if (!state.sagas.length) {
    return [viewHead("Sagas"), emptyState("Aún no hay sagas", "Abre un libro, pulsa Editar datos y escribe su saga y su número.")];
  }
  const full = state.sagas.filter((s) => s.books.length > 1);
  const single = state.sagas.filter((s) => s.books.length === 1);
  const parts = [viewHead("Sagas", `${plural(state.sagas.length, "saga", "sagas")} en tu biblioteca`)];
  if (state.universes.length) {
    parts.push(shelf("Universos", state.universes.map(universeCard), { link: { href: "#/universos", text: "Ver todos" }, meta: "sagas que comparten mundo" }));
  }
  if (full.length) parts.push(h("div", { class: "saga-grid" }, full.map(boxset)));
  if (single.length) {
    parts.push(
      h(
        "section",
        { class: "section-pad reveal", style: "margin-top: 56px", "aria-labelledby": "singleTitle" },
        h("h2", { class: "shelf-title", id: "singleTitle" }, "Con un solo libro"),
        h("p", { class: "view-sub", style: "margin-bottom: 18px" }, "Sagas de las que solo tienes un tomo. Desde un hueco de la saga puedes pedir los que faltan."),
        h("div", { class: "grid" }, single.map((s, i) => bookCard(s.books[0], { i })))
      )
    );
  }
  return parts;
}

function viewUniverses() {
  const admin = state.me.admin;
  const head = viewHead("Universos", "Sagas que comparten mundo: el Cosmere, la Tierra Media…", admin ? [h("button", { type: "button", class: "btn btn-quiet btn-sm", onclick: createUniverse }, icon("plus"), "Universo nuevo")] : null);
  if (!state.universes.length) return [head, emptyState("Aún no hay universos", admin ? "Crea uno y añádele sagas desde la página de cada saga." : "Los administradores pueden agrupar sagas en universos.")];
  return [head, h("div", { class: "universe-grid" }, [...state.universes].sort((a, b) => a.name.localeCompare(b.name, "es")).map(universeCard))];
}

function viewUniverse(id) {
  const u = state.universeById.get(id);
  if (!u) return notFound("Ese universo ya no existe", "Puede que se haya borrado. Vuelve a la lista de universos.");
  const lead = u.all.find((b) => b.hasCover);
  const covers = u.all.filter((b) => b.hasCover).slice(0, 5);
  const admin = state.me.admin;
  const parts = [
    h(
      "section",
      { class: "page-hero short hue", style: `--h: ${u.hue}` },
      heroBg(lead, u.hue),
      backButton(),
      h(
        "div",
        { class: "detail" },
        covers.length ? h("div", { class: "stack-covers", "aria-hidden": "true" }, covers.map((b, i) => {
          const c = coverEl(b);
          c.style.setProperty("--r", `${(i - 2) * 4}deg`);
          c.style.setProperty("--i", i);
          return c;
        })) : null,
        h(
          "div",
          { class: "detail-text" },
          h("div", { class: "kicker" }, "Universo"),
          h("h1", { class: "detail-title", tabindex: "-1" }, u.name),
          h("p", { class: "detail-author" }, [u.sagas.length ? plural(u.sagas.length, "saga", "sagas") : null, plural(u.all.length, "libro", "libros"), [...new Set(u.all.map((b) => b.author).filter(Boolean))].join(", ")].filter(Boolean).join(" · ")),
          admin
            ? h(
                "div",
                { class: "actions" },
                h("button", { type: "button", class: "btn btn-ghost btn-sm", onclick: () => renameUniverse(u) }, icon("edit"), "Renombrar"),
                h("button", { type: "button", class: "btn btn-ghost btn-sm", onclick: () => deleteUniverse(u) }, icon("trash"), "Borrar universo")
              )
            : null
        )
      )
    ),
  ];
  parts.push(...u.sagas.map((s) => sagaRow(s)));
  // Libros apuntados uno a uno: los que tienen saga (que no esta entera en el
  // universo) van con su saga; "sueltos" son solo los que no tienen ninguna
  const partial = new Map();
  for (const b of u.books) {
    const s = sagaOf(b);
    if (!s || u.sagas.includes(s)) continue;
    if (!partial.has(s)) partial.set(s, []);
    partial.get(s).push(b);
  }
  for (const [s, books] of partial) {
    books.sort(byVolume);
    const meta = books.length === s.books.length ? plural(books.length, "libro", "libros") : `${books.length} de los ${s.books.length} libros de la saga`;
    parts.push(shelf(s.name, books.map((b, i) => bookCard(b, { i, showAuthor: s.authors.length > 1 })), { hue: s.hue, titleHref: sagaHref(s), meta }));
  }
  const loose = u.books.filter((b) => !sagaOf(b));
  if (loose.length) parts.push(shelf("Libros sueltos de este universo", loose.map((b, i) => bookCard(b, { i })), { hue: u.hue }));
  if (!u.all.length) parts.push(emptyState("Este universo está vacío", "Añádele sagas desde la página de cada saga, o libros sueltos desde su ficha."));
  return parts;
}

function viewSaga(key) {
  const saga = state.sagaByKey.get(key);
  if (!saga) return notFound("Esa saga ya no existe", "Puede que la hayas renombrado. Vuelve a la lista de sagas.");
  const volumes = sagaVolumes(saga);
  const gaps = volumes.filter((v) => v.gap);
  const universe = universeOfSaga(saga);
  const admin = state.me.admin;
  const lead = saga.books.find((b) => b.hasCover);
  const sub = [saga.authors.join(", "), plural(saga.books.length, "libro", "libros"), gaps.length ? gaps.map((g) => gapText(g).toLocaleLowerCase("es")).join(", ") : null].filter(Boolean).join(" · ");

  const header = h(
    "section",
    { class: "page-hero short hue", style: `--h: ${saga.hue}` },
    heroBg(lead, saga.hue),
    backButton(),
    h(
      "div",
      { class: "detail" },
      h("div", { class: "stack-covers", "aria-hidden": "true" }, saga.books.slice(0, 5).map((b, i) => {
        const c = coverEl(b);
        c.style.setProperty("--r", `${(i - 2) * 4}deg`);
        c.style.setProperty("--i", i);
        return c;
      })),
      h(
        "div",
        { class: "detail-text" },
        universe ? h("a", { class: "saga-chip hue", href: universeHref(universe), style: `--h: ${universe.hue}; margin-bottom: 12px` }, `Universo ${universe.name}`) : h("div", { class: "kicker" }, "Saga"),
        h("h1", { class: "detail-title", tabindex: "-1" }, saga.name),
        h("p", { class: "detail-author" }, sub),
        admin
          ? h(
              "div",
              { class: "actions" },
              h("button", { type: "button", class: "btn btn-ghost btn-sm", onclick: () => openRename("series", saga.name, saga.books) }, icon("edit"), "Renombrar saga"),
              h("button", { type: "button", class: "btn btn-ghost btn-sm", onclick: () => openUniverseDialog({ kind: "saga", saga }) }, icon("globe"), universe ? "Cambiar universo" : "Añadir a un universo")
            )
          : null
      )
    )
  );

  const list = h(
    "ol",
    { class: "volume-list", "aria-label": `Libros de ${saga.name} en orden` },
    volumes.map((item, i) => {
      if (item.gap) {
        return h(
          "li",
          { class: "volume-row is-gap hue", style: `--h: ${saga.hue}; --i: ${i}` },
          h("span", { class: "vr-num" }, gapNumber(item)),
          h("span", { class: "vr-cover", "aria-hidden": "true" }),
          h("span", {}, item.gap === item.gapTo ? `No tienes el libro ${item.gap} de esta saga.` : `No tienes los libros del ${item.gap} al ${item.gapTo}.`),
          h("div", { class: "vr-actions" }, h("button", { type: "button", class: "btn btn-quiet btn-sm", onclick: () => askForGap(saga, item.gap) }, icon("plus"), "Pedirlo"))
        );
      }
      const { book, index } = item;
      // Los que no tienen numero se colocan a mano: arrastrando o con subir/bajar
      const movable = admin && index == null;
      const at = saga.books.indexOf(book);
      return h(
        "li",
        { class: `volume-row hue ${movable ? "movable" : ""}`, style: `--h: ${saga.hue}; --i: ${i}`, "data-id": book.id, draggable: movable ? "true" : null },
        h("span", { class: "vr-num" }, index != null ? formatIndex(index) : movable ? h("span", { class: "drag-handle", title: "Arrastra para colocarlo" }, icon("grip", 22)) : h("small", {}, "Sin número")),
        h("a", { href: bookHref(book), tabindex: "-1", "aria-hidden": "true", draggable: "false" }, coverEl(book)),
        h("a", { class: "vr-open", href: bookHref(book), style: "text-decoration: none", draggable: "false" }, h("span", { class: "vr-title" }, book.title), h("span", { class: "vr-meta" }, `${movable ? "Sin número · " : ""}${book.author ?? ""}${book.author ? " · " : ""}${book.extension.toUpperCase()} · ${formatSize(book.sizeBytes)}`)),
        h(
          "div",
          { class: "vr-actions" },
          movable ? h("button", { type: "button", class: "icon-btn", "aria-label": `Subir ${book.title}`, title: "Subir", disabled: at === 0 ? true : null, onclick: () => moveInSaga(saga, book.id, at - 1) }, icon("up", 16)) : null,
          movable ? h("button", { type: "button", class: "icon-btn", "aria-label": `Bajar ${book.title}`, title: "Bajar", disabled: at === saga.books.length - 1 ? true : null, onclick: () => moveInSaga(saga, book.id, at + 1) }, icon("down", 16)) : null,
          h("a", { class: "btn btn-quiet btn-sm", href: `/api/books/${book.id}/download`, "aria-label": `Descargar ${book.title}` }, icon("download"), "Descargar")
        )
      );
    })
  );
  if (admin && saga.books.some((b) => b.seriesIndex == null)) enableDrag(list, saga);
  const note = admin && saga.books.some((b) => b.seriesIndex == null)
    ? h("p", { class: "status section-pad", style: "max-width: 1000px; margin-bottom: 14px" }, "Los libros sin número se pueden arrastrar a cualquier sitio de la saga, o moverlos con las flechas. Los numerados van por su número: para moverlos, cámbiale el número en su ficha.")
    : null;
  return [header, note, list];
}

/**
 * Coloca un libro sin numero en la posicion {@code to} de la saga (entre los
 * demas, numerados o no) y guarda las posiciones de todos los sin numero.
 */
async function moveInSaga(saga, id, to) {
  const books = saga.books.filter((b) => b.id !== id);
  const moved = saga.books.find((b) => b.id === id);
  books.splice(Math.max(0, Math.min(to, books.length)), 0, moved);
  // Cada tramo de libros sin numero se reparte entre el numerado anterior y el siguiente
  const positions = {};
  let i = 0;
  let prev = 0;
  while (i < books.length) {
    if (books[i].seriesIndex != null) {
      prev = Number(books[i].seriesIndex);
      i++;
      continue;
    }
    const run = [];
    while (i < books.length && books[i].seriesIndex == null) run.push(books[i++]);
    const next = i < books.length ? Number(books[i].seriesIndex) : null;
    run.forEach((b, k) => {
      positions[b.id] = next != null && next > prev ? prev + ((next - prev) * (k + 1)) / (run.length + 1) : prev + k + 1;
    });
  }
  try {
    state.order = await api("/api/admin/saga-order", json("PUT", { saga: saga.name, positions }));
    const y = scrollY;
    await loadData();
    render({ focus: false });
    scrollTo(0, y);
    const row = main.querySelector(`[data-id="${id}"]`);
    row?.classList.add("just-moved");
    announce(`${moved.title} colocado en la posición ${books.indexOf(moved) + 1} de ${books.length}`);
  } catch (err) {
    toast("No se pudo guardar el orden", err.message, "error");
  }
}

/** Arrastrar y soltar las filas sin numero de la lista de una saga. */
function enableDrag(list, saga) {
  let dragId = null;
  const clear = () => list.querySelectorAll(".drop-before, .drop-after").forEach((r) => r.classList.remove("drop-before", "drop-after"));
  list.addEventListener("dragstart", (e) => {
    const row = e.target.closest(".volume-row.movable");
    if (!row) return;
    dragId = row.dataset.id;
    row.classList.add("dragging");
    e.dataTransfer.effectAllowed = "move";
    e.dataTransfer.setData("text/plain", dragId);
  });
  list.addEventListener("dragend", (e) => {
    e.target.closest(".volume-row")?.classList.remove("dragging");
    clear();
    dragId = null;
  });
  list.addEventListener("dragover", (e) => {
    if (!dragId) return;
    const row = e.target.closest(".volume-row[data-id]");
    if (!row || row.dataset.id === dragId) return;
    e.preventDefault();
    const r = row.getBoundingClientRect();
    const after = e.clientY > r.top + r.height / 2;
    clear();
    row.classList.add(after ? "drop-after" : "drop-before");
  });
  list.addEventListener("drop", (e) => {
    if (!dragId) return;
    e.preventDefault();
    const row = list.querySelector(".drop-before, .drop-after");
    if (!row) return;
    const others = saga.books.filter((b) => b.id !== dragId);
    let to = others.findIndex((b) => b.id === row.dataset.id);
    if (row.classList.contains("drop-after")) to++;
    clear();
    moveInSaga(saga, dragId, to);
  });
}

function viewAuthors() {
  return [viewHead("Autores", plural(state.authors.length, "autor", "autores")), h("div", { class: "author-grid" }, state.authors.map(authorChip))];
}

function viewAuthor(key) {
  const author = state.authorByKey.get(key);
  if (!author) return notFound("Ese autor ya no está", "Puede que lo hayas renombrado o unificado con otro. Vuelve a la lista de autores.");
  const loose = author.books.filter((b) => !b.series).sort((a, b) => a.title.localeCompare(b.title, "es"));
  const lead = author.books.find((b) => b.hasCover);
  const sub = [plural(author.books.length, "libro", "libros"), author.sagas.length ? plural(author.sagas.length, "saga", "sagas") : null].filter(Boolean).join(" · ");
  const parts = [
    h(
      "section",
      { class: "page-hero short hue", style: `--h: ${author.hue}` },
      heroBg(lead, author.hue),
      backButton(),
      h(
        "div",
        { class: "detail" },
        h("span", { class: "author-ring", style: "width: 150px; height: 150px; font-size: 60px; flex: none", "aria-hidden": "true" }, lead ? h("img", { src: coverUrl(lead), alt: "" }) : null, h("span", {}, author.name.charAt(0).toLocaleUpperCase("es"))),
        h(
          "div",
          { class: "detail-text" },
          h("div", { class: "kicker" }, "Autor"),
          h("h1", { class: "detail-title", tabindex: "-1" }, author.name),
          h("p", { class: "detail-author" }, sub),
          state.me.admin ? h("div", { class: "actions" }, h("button", { type: "button", class: "btn btn-ghost btn-sm", onclick: () => openRename("author", author.name, author.books) }, icon("edit"), "Renombrar autor")) : null
        )
      )
    ),
  ];
  parts.push(...author.sagas.map((s) => sagaRow(s, { withAuthor: s.authors.length > 1 })));
  if (loose.length) {
    parts.push(h("h2", { class: "shelf-title section-pad", style: "margin: 8px 0 16px" }, author.sagas.length ? "Otros libros" : "Libros"), h("div", { class: "grid" }, loose.map((b, i) => bookCard(b, { showAuthor: false, i }))));
  }
  return parts;
}

function viewAll() {
  return [viewHead("Todos los libros", `${plural(state.books.length, "libro", "libros")}, por autor y saga`), h("div", { class: "grid" }, state.books.map((b, i) => bookCard(b, { i })))];
}

function viewSearch(query) {
  const q = normKey(query);
  const results = state.books.filter((b) => [b.title, b.author, b.series].some((field) => normKey(field).includes(q)));
  const universes = state.universes.filter((u) => normKey(u.name).includes(q));
  const ask = h("a", { class: "btn btn-grad btn-sm", href: `#/peticiones/${encodeURIComponent(query)}` }, icon("plus"), "Pedir un libro");
  if (!results.length && !universes.length) {
    return [viewHead(`Búsqueda: ${query}`), h("div", { class: "empty-state" }, h("h2", { tabindex: "-1" }, "No está en la biblioteca"), h("p", {}, "Prueba con otra palabra del título, el autor o la saga, o pídelo."), ask)];
  }
  const parts = [viewHead(`Búsqueda: ${query}`, plural(results.length, "libro encontrado", "libros encontrados"), [ask])];
  if (universes.length) parts.push(shelf("Universos", universes.map(universeCard)));
  parts.push(h("div", { class: "grid" }, results.map((b, i) => bookCard(b, { i }))));
  return parts;
}

function notFound(title, text) {
  return [h("div", { style: "height: var(--topbar-h)" }), emptyState(title, text)];
}

// --- Peticiones (usuario) --------------------------------------------------------

function viewRequests(query) {
  const results = h("div", { class: "panel", style: "padding: 8px" }, h("p", { class: "status", style: "margin: 12px" }, "Escribe el título o el autor del libro que quieres."));
  const mine = h("div", {}, h("p", { class: "status" }, "Cargando tus peticiones…"));
  const input = h("input", { type: "search", id: "reqQuery", value: query ?? "", placeholder: "El camino de los reyes…", autocomplete: "off", "aria-label": "Buscar un libro para pedirlo" });
  const hint = state.reqHint ? h("p", { class: "plan-chip", style: "margin: 0 0 12px" }, `Para la saga ${state.reqHint.series}, libro ${formatIndex(state.reqHint.seriesIndex)}`, h("button", { type: "button", "aria-label": "Quitar", onclick: (e) => { state.reqHint = null; e.currentTarget.parentElement.remove(); } }, icon("close", 14))) : null;

  let timer;
  let seq = 0;
  const search = async () => {
    const q = input.value.trim();
    history.replaceState(null, "", q ? `#/peticiones/${encodeURIComponent(q)}` : "#/peticiones");
    if (q.length < 2) return;
    const mySeq = ++seq;
    results.replaceChildren(h("p", { class: "status", style: "margin: 12px" }, "Buscando en Open Library…"));
    try {
      const found = await api(`/api/requests/lookup?q=${encodeURIComponent(q)}`);
      if (mySeq !== seq) return;
      renderLookup(results, found, () => loadMine(mine));
    } catch (err) {
      if (mySeq === seq) results.replaceChildren(h("p", { class: "status is-error", style: "margin: 12px" }, err.message));
    }
  };
  input.addEventListener("input", () => {
    clearTimeout(timer);
    timer = setTimeout(search, 450);
  });
  if (query) setTimeout(search, 0);
  loadMine(mine);

  return [
    h("div", { style: "height: 24px" }),
    h(
      "div",
      { class: "two-col" },
      h(
        "section",
        {},
        h("h1", { class: "view-title", tabindex: "-1", style: "margin-bottom: 8px" }, "Pide un libro"),
        h("p", { class: "view-sub", style: "margin: 0 0 22px" }, "Busca el libro que te falta. Los administradores lo verán y te avisaremos cuando esté en la biblioteca."),
        hint,
        h("label", { class: "big-search" }, icon("search"), input, h("span", { class: "hint", style: "font-size: 13px" }, "Open Library")),
        h("div", { style: "height: 16px" }),
        results
      ),
      h("section", { class: "panel" }, h("div", { class: "panel-head" }, h("h2", { class: "grow" }, "Tus peticiones")), mine)
    ),
  ];
}

function renderLookup(container, found, onChange) {
  if (!found.length) {
    container.replaceChildren(h("p", { class: "status", style: "margin: 12px" }, "No aparece nada. Prueba solo con el título o con el apellido del autor."));
    return;
  }
  container.replaceChildren(
    ...found.map((f, i) => {
      const meta = [f.publisher, f.year, f.spanish ? "edición en español" : "sin edición en español", f.originalTitle ? `«${f.originalTitle}»` : null].filter(Boolean).join(" · ");
      let action;
      if (f.bookId) action = h("a", { class: "btn btn-quiet btn-sm", href: `#/libro/${encodeURIComponent(f.bookId)}` }, icon("check"), "Ya lo tienes");
      else if (f.requested === "me") action = h("span", { class: "st st-pending" }, "Ya lo has pedido");
      else action = h("button", { type: "button", class: "btn btn-grad btn-sm", onclick: (e) => openNote(e.currentTarget.closest(".result-row"), f, onChange) }, icon("plus", 16), f.requested === "others" ? "Pedirlo también" : "Pedir");
      return h(
        "div",
        { class: "result-row", style: `--i: ${i}` },
        remoteCover(f.title, f.author, f.coverId),
        h("div", { class: "result-main" }, h("div", { class: "result-title" }, f.title), h("div", { style: "color: var(--text-2); font-size: 14px" }, f.author), h("div", { class: "result-meta" }, meta)),
        action
      );
    })
  );
}

function openNote(row, found, onChange) {
  row.parentElement.querySelectorAll(".note-form").forEach((n) => n.remove());
  row.parentElement.querySelectorAll(".result-row.open").forEach((r) => r.classList.remove("open"));
  row.classList.add("open");
  const note = h("input", { class: "input", id: "reqNote", type: "text", maxlength: "300", placeholder: "Por ejemplo: en español, la edición de Nova si puede ser" });
  const status = h("p", { class: "status", role: "status" });
  const send = h("button", { type: "submit", class: "btn btn-grad btn-sm" }, "Enviar petición");
  const form = h(
    "form",
    {
      class: "note-form",
      onsubmit: async (e) => {
        e.preventDefault();
        send.disabled = true;
        setStatus(status, "Enviando…");
        try {
          await api("/api/requests", json("POST", { olKey: found.olKey, title: found.title, author: found.author, year: found.year, coverId: found.coverId, series: state.reqHint?.series, seriesIndex: state.reqHint?.seriesIndex, note: note.value }));
          state.reqHint = null;
          form.remove();
          row.classList.remove("open");
          row.querySelector(".btn-grad")?.replaceWith(h("span", { class: "st st-pending" }, "Pedido"));
          toast("Petición enviada", `Te avisaremos cuando «${found.title}» esté en la biblioteca.`);
          onChange();
        } catch (err) {
          setStatus(status, err.message, "error");
          send.disabled = false;
        }
      },
    },
    h("label", { class: "label", for: "reqNote" }, "Nota para los administradores ", h("span", { class: "hint" }, "(opcional)")),
    note,
    h("div", { class: "form-actions", style: "margin-top: 12px" }, send, h("button", { type: "button", class: "btn btn-quiet btn-sm", onclick: () => { form.remove(); row.classList.remove("open"); } }, "Cancelar")),
    status
  );
  row.after(form);
  note.focus();
}

const STATUS_TEXT = { pending: "Pendiente", done: "Conseguido", rejected: "Rechazada" };

async function loadMine(container) {
  try {
    const list = await api("/api/requests");
    state.me.updates = [];
    updateChrome();
    if (!list.length) {
      container.replaceChildren(h("p", { class: "status" }, "Aún no has pedido nada."));
      return;
    }
    container.replaceChildren(
      ...list.map((r, i) => {
        const downloading = r.status === "pending" && r.downloading != null;
        const st = downloading ? h("span", { class: "st st-downloading" }, `Descargando ${Math.round(r.downloading * 100)} %`) : h("span", { class: `st st-${r.status}` }, STATUS_TEXT[r.status]);
        const when = [`Pedida ${timeAgo(r.at)}`, r.count > 1 ? `la habéis pedido ${r.count}` : null, r.resolvedAt ? `${STATUS_TEXT[r.status].toLocaleLowerCase("es")} ${timeAgo(r.resolvedAt)}` : null].filter(Boolean).join(" · ");
        return h(
          "article",
          { class: "req-card", style: `--i: ${i}` },
          remoteCover(r.title, r.author, r.coverId),
          h(
            "div",
            { style: "flex: 1; min-width: 0" },
            h("div", { style: "display: flex; gap: 10px; align-items: flex-start" }, h("div", { style: "flex: 1" }, h("div", { class: "result-title", style: "font-size: 16px" }, r.title), h("div", { style: "color: var(--text-2); font-size: 13px" }, r.author)), st),
            h("div", { class: "result-meta", style: "margin-top: 8px" }, when),
            r.note ? h("div", { class: "result-meta" }, `Tu nota: ${r.note}`) : null,
            r.reply ? h("p", { class: "reply" }, h("b", {}, "Respuesta: "), r.reply) : null,
            r.status === "done" && r.bookId && state.byId.has(r.bookId) ? h("a", { href: `#/libro/${encodeURIComponent(r.bookId)}`, style: "display: inline-block; margin-top: 10px; color: var(--accent); font-weight: 600" }, "Ya está en la biblioteca · abrir ›") : null,
            r.status === "pending"
              ? h("button", { type: "button", class: "link-btn", style: "margin-top: 10px", onclick: async () => {
                  try {
                    await api(`/api/requests/${r.id}`, { method: "DELETE" });
                    toast("Petición retirada", r.title);
                    loadMine(container);
                  } catch (err) {
                    toast("No se pudo retirar", err.message, "error");
                  }
                } }, "Retirar petición")
              : null
          )
        );
      })
    );
  } catch (err) {
    container.replaceChildren(h("p", { class: "status is-error" }, err.message));
  }
}

// --- Administracion --------------------------------------------------------------

function adminTabs(active) {
  const me = state.me;
  const tab = (id, href, label, extra) => h("a", { href, "aria-current": active === id ? "page" : null }, label, extra);
  return [
    h("div", { class: "view-head", style: "padding-bottom: 18px" }, h("div", {}, h("h1", { class: "view-title", tabindex: "-1" }, "Administración"), h("p", { class: "view-sub" }, "Solo lo ven las cuentas del grupo de administradores."))),
    h(
      "nav",
      { class: "admin-tabs", "aria-label": "Administración" },
      tab("requests", "#/admin/peticiones", "Peticiones", me.pendingRequests ? h("span", { class: "count hot" }, me.pendingRequests) : null),
      tab("downloads", "#/admin/descargas", "Descargas", me.activeDownloads ? h("span", { class: "count" }, `${me.activeDownloads} en curso`) : null),
      tab("log", "#/admin/registro", "Registro")
    ),
  ];
}

function viewAdmin(tab) {
  if (!state.me.admin) return notFound("Solo para administradores", "Tu cuenta no está en el grupo de administradores de BiblioLocal.");
  if (tab === "descargas") return [...adminTabs("downloads"), adminDownloads()];
  if (tab === "registro") return [...adminTabs("log"), adminLog()];
  return [...adminTabs("requests"), adminRequests()];
}

/* Peticiones de todos */
function adminRequests() {
  const box = h("div", {}, h("p", { class: "status section-pad" }, "Cargando peticiones…"));
  let filter = "pending";
  let list = [];
  const draw = () => {
    const pending = list.filter((r) => r.status === "pending");
    const downloading = pending.filter((r) => r.downloading != null);
    const done30 = list.filter((r) => r.status === "done" && r.resolvedAt > Date.now() - 30 * 86400000);
    const shown = filter === "pending" ? pending.filter((r) => r.downloading == null) : filter === "downloading" ? downloading : list.filter((r) => r.status !== "pending");
    const chip = (id, label) => h("button", { type: "button", class: "fchip", "aria-pressed": String(filter === id), onclick: () => { filter = id; draw(); } }, label);
    box.replaceChildren(
      h("div", { class: "stats" },
        h("div", { class: "stat", style: "--i: 0" }, h("b", { style: "color: var(--warn)" }, pending.length - downloading.length), h("span", {}, "pendientes")),
        h("div", { class: "stat", style: "--i: 1" }, h("b", { style: "color: var(--accent)" }, downloading.length), h("span", {}, "descargándose")),
        h("div", { class: "stat", style: "--i: 2" }, h("b", { style: "color: var(--ok)" }, done30.length), h("span", {}, "conseguidas en 30 días")),
        h("div", { class: "stat", style: "--i: 3" }, h("b", {}, new Set(list.flatMap((r) => r.users.map((u) => u.name))).size), h("span", {}, "personas han pedido algo"))
      ),
      h("div", { class: "chip-row section-pad", style: "margin-bottom: 14px" }, chip("pending", `Pendientes · ${pending.length - downloading.length}`), chip("downloading", `Descargando · ${downloading.length}`), chip("resolved", "Resueltas")),
      shown.length
        ? h("div", { class: "section-pad" }, h("div", { class: "table-wrap" }, h("table", { class: "table" },
            h("thead", {}, h("tr", {}, h("th", { colspan: "2" }, "Libro"), h("th", {}, "Quién lo pide"), h("th", {}, "Cuándo"), h("th", { style: "text-align: right" }, "Acciones"))),
            h("tbody", {}, shown.map((r) => requestRow(r, () => load())))
          )))
        : h("p", { class: "status section-pad" }, filter === "pending" ? "No hay peticiones pendientes." : "Nada por aquí.")
    );
  };
  const load = async () => {
    try {
      list = await api("/api/requests?all=true");
      state.me.pendingRequests = list.filter((r) => r.status === "pending").length;
      updateChrome();
      draw();
    } catch (err) {
      box.replaceChildren(h("p", { class: "status is-error section-pad" }, err.message));
    }
  };
  load();
  return box;
}

function requestRow(r, reload) {
  const who = h("div", {}, r.users.map((u) => h("div", { style: "font-size: 14px" }, h("b", {}, u.name), u.note ? h("span", { style: "color: var(--text-2); font-style: italic" }, ` · “${u.note}”`) : null)));
  const status = r.status === "pending"
    ? r.downloading != null ? h("span", { class: "st st-downloading" }, `Descargando ${Math.round(r.downloading * 100)} %`) : null
    : h("span", { class: `st st-${r.status}` }, STATUS_TEXT[r.status]);
  const resolve = async (st, reply) => {
    try {
      await api(`/api/admin/requests/${r.id}`, json("POST", { status: st, reply }));
      toast(st === "done" ? "Marcada como conseguida" : st === "rejected" ? "Petición rechazada" : "Otra vez pendiente", r.title);
      reload();
    } catch (err) {
      toast("No se pudo cambiar", err.message, "error");
    }
  };
  const actions = h("div", { class: "actions", style: "justify-content: flex-end" });
  if (r.status === "pending") {
    actions.append(
      h("button", { type: "button", class: "btn btn-grad btn-sm", onclick: () => { state.dlContext = { requestId: r.id, title: r.title, author: r.author, series: r.series, seriesIndex: r.seriesIndex, users: r.users.map((u) => u.name) }; location.hash = "#/admin/descargas"; } }, icon("search", 16), "Buscar en aMule"),
      h("button", { type: "button", class: "icon-btn", "aria-label": `Marcar «${r.title}» como conseguida`, title: "Conseguida", onclick: () => resolve("done") }, icon("check", 16)),
      h("button", { type: "button", class: "icon-btn", "aria-label": `Rechazar «${r.title}»`, title: "Rechazar", onclick: (e) => openReject(e.currentTarget.closest("tr"), r, resolve) }, icon("close", 16))
    );
  } else {
    actions.append(h("button", { type: "button", class: "btn btn-quiet btn-sm", onclick: () => resolve("pending") }, "Volver a pendiente"));
  }
  return h(
    "tr",
    {},
    h("td", { style: "width: 70px" }, remoteCover(r.title, r.author, r.coverId)),
    h("td", {}, h("div", { class: "result-title", style: "font-size: 16px" }, r.title), h("div", { style: "color: var(--text-2)" }, r.author), r.series ? h("div", { class: "result-meta" }, `Libro ${formatIndex(r.seriesIndex ?? "?")} de ${r.series}`) : null, status ? h("div", { style: "margin-top: 6px" }, status) : null, r.reply ? h("div", { class: "result-meta" }, `Respuesta: ${r.reply}`) : null),
    h("td", {}, who),
    h("td", { style: "color: var(--text-2); white-space: nowrap" }, timeAgo(r.at)),
    h("td", {}, actions)
  );
}

function openReject(tr, r, resolve) {
  tr.parentElement.querySelectorAll(".reject-row").forEach((x) => x.remove());
  const input = h("input", { class: "input", type: "text", id: "rejectReply", maxlength: "300", placeholder: "Por ejemplo: no lo encuentro en español" });
  const row = h(
    "tr",
    { class: "reject-row bad" },
    h(
      "td",
      { colspan: "5" },
      h("form", { style: "display: flex; flex-wrap: wrap; gap: 10px; align-items: flex-end", onsubmit: (e) => { e.preventDefault(); resolve("rejected", input.value); } },
        h("div", { class: "field", style: "flex: 1; min-width: 260px" }, h("label", { for: "rejectReply" }, `Rechazar «${r.title}» · respuesta para quien lo pidió `, h("span", { class: "hint" }, "(opcional)")), input),
        h("button", { type: "submit", class: "btn btn-danger" }, "Rechazar"),
        h("button", { type: "button", class: "btn btn-quiet", onclick: () => row.remove() }, "Cancelar")
      )
    )
  );
  tr.after(row);
  input.focus();
}

/* Descargas con aMule */
function adminDownloads() {
  const statusBar = h("div", { class: "status-bar" }, "Conectando con aMule…");
  const active = h("div", {});
  const pending = h("div", {});
  const placed = h("div", {});
  const results = h("div", {});
  const ctx = state.dlContext;
  const defaultQuery = ctx ? `${ctx.title} ${(ctx.author ?? "").split(/\s+/).pop()}`.trim() : "";
  const input = h("input", { type: "search", class: "input", id: "dlQuery", value: defaultQuery, placeholder: "Título y autor", "aria-label": "Buscar en aMule" });
  const network = h("select", { class: "input", style: "width: 150px", "aria-label": "Red" }, h("option", { value: "global" }, "Servidores"), h("option", { value: "kad" }, "Kad"));
  const searchStatus = h("p", { class: "status", role: "status" });
  const planChip = ctx
    ? h("span", { class: "plan-chip" }, `Para la petición: ${ctx.title}${ctx.users?.length ? ` · ${ctx.users.join(", ")}` : ""}`, h("button", { type: "button", "aria-label": "Quitar la petición", onclick: (e) => { state.dlContext = null; e.currentTarget.parentElement.remove(); } }, icon("close", 14)))
    : null;

  let pollResults = null;
  const runSearch = async (e) => {
    e?.preventDefault();
    const q = input.value.trim();
    if (!q) return;
    clearInterval(pollResults);
    results.replaceChildren();
    setStatus(searchStatus, "Buscando…");
    try {
      await api("/api/admin/downloads/search", json("POST", { q, network: network.value }));
      let ticks = 0;
      const poll = async () => {
        ticks++;
        const r = await api("/api/admin/downloads/search").catch((err) => ({ error: err.message }));
        if (r.error) {
          setStatus(searchStatus, r.error, "error");
          clearInterval(pollResults);
          return;
        }
        drawResults(results, r.results);
        const done = r.done || ticks > 40;
        setStatus(searchStatus, r.expired ? "La búsqueda ha caducado: vuelve a buscar." : `${done ? "" : "Buscando… "}${plural(r.results.length, "resultado", "resultados")}`);
        if (done) clearInterval(pollResults);
      };
      pollResults = setInterval(poll, 2000);
      setTimeout(poll, 800);
    } catch (err) {
      setStatus(searchStatus, err.message, "error");
    }
  };

  const drawSnapshot = (s) => {
    if (!s.ok) {
      statusBar.replaceChildren(h("span", {}, h("span", { class: "led bad" }), h("b", {}, s.code === "unconfigured" ? "aMule sin configurar" : "aMule no responde")), h("span", {}, s.error ?? ""));
    } else {
      const st = s.status;
      const ed = { high: "ID alta", low: "ID baja", connecting: "conectando", off: "desconectado" }[st.server];
      const kad = { ok: "correcto", firewalled: "con cortafuegos", connecting: "conectando", off: "desconectado" }[st.kad];
      statusBar.replaceChildren(
        h("span", {}, h("span", { class: `led ${st.server === "high" ? "ok" : st.server === "off" ? "bad" : "warn"}` }), h("b", {}, "aMule conectado")),
        h("span", {}, "eD2k: ", h("b", {}, ed), st.serverName ? ` · ${st.serverName}` : ""),
        h("span", {}, "Kad: ", h("b", {}, kad)),
        h("span", { class: "mono" }, `↓ ${formatSize(st.down)}/s · ↑ ${formatSize(st.up)}/s`),
        h("span", { style: "margin-left: auto" }, "Se guardan en ", h("b", {}, s.library), st.freeBytes != null ? ` · ${formatSize(st.freeBytes)} libres en aMule` : "")
      );
    }
    active.replaceChildren(
      s.downloads.length
        ? h("div", {}, s.downloads.map((it) => downloadItem(it)))
        : h("p", { class: "status" }, "No hay libros descargándose.")
    );
    pending.closest(".panel").hidden = !s.pending.length;
    pending.replaceChildren(...s.pending.map((p) => pendingItem(p, refreshSnap)));
    placed.replaceChildren(
      s.placed.length
        ? h("div", {}, s.placed.map((p) => h("div", { class: "dl-item", style: "display: flex; gap: 12px; align-items: center" },
            h("span", { style: "color: var(--ok)" }, icon("check", 18)),
            h("div", { style: "flex: 1; min-width: 0" }, p.bookId && state.byId.has(p.bookId) ? h("a", { href: `#/libro/${encodeURIComponent(p.bookId)}`, style: "font-weight: 600" }, p.title) : h("b", {}, p.title), h("div", { class: "dest" }, p.dest, p.requestId ? " · petición atendida" : "")),
            h("span", { class: "hint", style: "font-size: 13px; white-space: nowrap" }, timeAgo(p.at))
          )))
        : h("p", { class: "status" }, "Aún no se ha colocado nada.")
    );
  };
  const refreshSnap = async () => {
    try {
      const s = await api("/api/admin/downloads");
      drawSnapshot(s);
      state.me.activeDownloads = s.downloads.length;
    } catch (err) {
      statusBar.replaceChildren(h("span", {}, h("span", { class: "led bad" }), err.message));
    }
  };
  refreshSnap();
  const snapTimer = setInterval(refreshSnap, 3000);
  onLeave(() => { clearInterval(snapTimer); clearInterval(pollResults); });
  if (defaultQuery) setTimeout(runSearch, 0);

  return h(
    "div",
    {},
    statusBar,
    h(
      "div",
      { class: "two-col" },
      h(
        "section",
        { class: "panel" },
        h("div", { class: "panel-head" }, h("h2", { class: "grow" }, "Buscar"), planChip),
        h("form", { style: "display: flex; gap: 10px; flex-wrap: wrap", onsubmit: runSearch }, h("div", { style: "flex: 1; min-width: 200px" }, input), network, h("button", { class: "btn btn-grad", type: "submit" }, "Buscar")),
        searchStatus,
        results
      ),
      h(
        "div",
        { class: "stack" },
        h("section", { class: "panel" }, h("div", { class: "panel-head" }, h("h2", { class: "grow" }, "En curso")), active),
        h("section", { class: "panel", style: "border-color: color-mix(in srgb, var(--warn) 40%, transparent)", hidden: true }, h("div", { class: "panel-head" }, h("h2", { class: "grow" }, "Pendiente de colocar")), h("p", { class: "status", style: "margin-top: -8px" }, "Terminadas que no se sabe con seguridad de qué libro son."), pending),
        h("section", { class: "panel" }, h("div", { class: "panel-head" }, h("h2", { class: "grow" }, "Colocadas"), h("button", { type: "button", class: "link-btn", onclick: async () => { await api("/api/admin/downloads/placed", { method: "DELETE" }).catch(() => {}); refreshSnap(); } }, "Vaciar lista")), placed)
      )
    )
  );
}

const FORMAT = (name) => (name.match(/\.([a-z0-9]{2,5})$/i)?.[1] ?? "").toLowerCase();
const EBOOK = /^(epub|mobi|azw3?|pdf|fb2|cbz|cbr)$/;

function drawResults(container, list) {
  if (!list.length) {
    container.replaceChildren();
    return;
  }
  container.replaceChildren(
    h("div", { class: "res-row", style: "color: var(--text-3); font-size: 12px; font-weight: 700; letter-spacing: .08em; text-transform: uppercase" }, h("span", {}, "Archivo"), h("span", {}, "Tamaño"), h("span", {}, "Fuentes"), h("span")),
    ...list.slice(0, 80).map((r) => {
      const fmt = FORMAT(r.name);
      const btn = h("button", { type: "button", class: "btn btn-quiet btn-sm" }, icon("download", 16), "Descargar");
      btn.addEventListener("click", async () => {
        btn.disabled = true;
        const ctx = state.dlContext ?? {};
        try {
          await api("/api/admin/downloads", json("POST", { hash: r.hash, requestId: ctx.requestId, title: ctx.title, author: ctx.author, series: ctx.series, seriesIndex: ctx.seriesIndex }));
          toast("Descarga iniciada", ctx.title ? `Para la petición «${ctx.title}»` : r.name);
          btn.replaceChildren(icon("check", 16), "Añadida");
          state.dlContext = null;
        } catch (err) {
          toast("aMule no la ha aceptado", err.message, "error");
          btn.disabled = false;
        }
      });
      return h(
        "div",
        { class: `res-row ${EBOOK.test(fmt) && fmt !== "pdf" ? "" : "dim"}` },
        h("div", { class: "res-name" }, h("span", { class: `fmt ${fmt === "epub" ? "epub" : ""}` }, fmt.toUpperCase() || "?"), h("span", { title: r.name }, r.name)),
        h("span", { class: "mono", style: "font-size: 13px" }, formatSize(r.size)),
        h("span", { style: "font-size: 13px; color: var(--text-2)" }, `${r.sources}${r.complete ? ` (${r.complete} compl.)` : ""}`),
        btn
      );
    })
  );
}

const DL_STATE = { downloading: "Descargando", waiting: "En cola", stalled: "Sin fuentes", paused: "En pausa", stopped: "Parada", hashing: "Comprobando", completing: "Terminando", complete: "Terminada", error: "Error" };

function downloadItem(it) {
  const d = it.download;
  const p = it.plan;
  const op = async (name) => {
    try {
      await api(`/api/admin/downloads/${d.hash}/${name}`, { method: "POST" });
    } catch (err) {
      toast("aMule no ha podido", err.message, "error");
    }
  };
  const paused = d.state === "paused" || d.state === "stopped";
  return h(
    "article",
    { class: "dl-item" },
    h(
      "div",
      { style: "display: flex; gap: 10px; align-items: flex-start" },
      h("div", { style: "flex: 1; min-width: 0" }, h("div", { class: "serif", style: "font-weight: 600; font-size: 16px" }, p?.title ? `${p.title}${p.author ? ` · ${p.author}` : ""}` : d.name), h("div", { class: "mono", style: "font-size: 12px; color: var(--text-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap" }, d.name)),
      h("button", { type: "button", class: "icon-btn", "aria-label": paused ? "Reanudar" : "Pausar", onclick: () => op(paused ? "resume" : "pause") }, icon(paused ? "play" : "pause", 15)),
      h("button", { type: "button", class: "icon-btn", "aria-label": "Cancelar la descarga", onclick: () => confirm(`¿Cancelar la descarga de ${d.name}?`) && op("cancel") }, icon("close", 15))
    ),
    h("div", { class: "bar", style: "margin: 12px 0 8px" }, h("i", { style: `width: ${Math.round(d.progress * 100)}%` })),
    h("div", { class: "dl-meta" }, h("b", { style: "color: var(--text)" }, `${Math.round(d.progress * 100)} %`), h("span", {}, DL_STATE[d.state] ?? d.state), d.speed ? h("span", { class: "mono" }, `${formatSize(d.speed)}/s`) : null, h("span", {}, `${d.sources}/${d.sourcesTotal} fuentes`), h("span", { style: "margin-left: auto" }, formatSize(d.size))),
    it.target ? h("div", { class: "dest" }, "Irá a ", h("b", {}, it.target)) : h("div", { class: "dest" }, "Al terminar se mirará qué libro es por el epub y el nombre del archivo.")
  );
}

function pendingItem(p, reload) {
  const s = p.suggestion ?? {};
  const f = (id, label, value) => h("div", { class: "field" }, h("label", { for: id }, label), h("input", { class: "input", id, value: value ?? "" }));
  const form = h(
    "form",
    {
      class: "form-grid", hidden: true, style: "margin-top: 12px",
      onsubmit: async (e) => {
        e.preventDefault();
        try {
          await api(`/api/admin/downloads/pending/${p.hash}`, json("POST", { title: $(`pt-${p.hash}`).value, author: $(`pa-${p.hash}`).value, series: $(`ps-${p.hash}`).value, seriesIndex: $(`pi-${p.hash}`).value }));
          toast("Colocado en la biblioteca", $(`pt-${p.hash}`).value);
          await loadData();
          reload();
        } catch (err) {
          toast("No se pudo colocar", err.message, "error");
        }
      },
    },
    f(`pt-${p.hash}`, "Título", s.title),
    f(`pa-${p.hash}`, "Autor", s.author),
    h("div", { class: "field-row" }, f(`ps-${p.hash}`, "Saga (opcional)", s.series), f(`pi-${p.hash}`, "Número", s.seriesIndex)),
    h("div", { class: "form-actions" }, h("button", { class: "btn btn-grad btn-sm", type: "submit" }, "Colocar en la biblioteca"))
  );
  return h(
    "div",
    { class: "dl-item" },
    h(
      "div",
      { style: "display: flex; gap: 12px; align-items: center" },
      h("span", { class: `fmt ${FORMAT(p.name) === "epub" ? "epub" : ""}` }, FORMAT(p.name).toUpperCase()),
      h("div", { style: "flex: 1; min-width: 0" }, h("div", { class: "mono", style: "font-size: 13px; overflow-wrap: anywhere" }, p.name), h("div", { style: "font-size: 13px; color: var(--warn); margin-top: 2px" }, p.reason)),
      h("button", { type: "button", class: "btn btn-quiet btn-sm", onclick: () => { form.hidden = !form.hidden; } }, "Colocar…"),
      h("button", { type: "button", class: "icon-btn", "aria-label": "Descartar (el archivo se queda en la carpeta de aMule)", title: "Descartar", onclick: async () => { await api(`/api/admin/downloads/pending/${p.hash}`, { method: "DELETE" }).catch((err) => toast("No se pudo", err.message, "error")); reload(); } }, icon("trash", 15))
    ),
    form
  );
}

/* Registro */
const LOG_GROUPS = [["session", "Sesiones"], ["download", "Descargas"], ["kindle", "Kindle"], ["edit", "Cambios"], ["request", "Peticiones"], ["amule", "aMule"], ["error", "Errores"]];
const KIND_TEXT = {
  login: "Sesión", logout: "Sesión", login_failed: "Login fallido", login_denied: "Sin acceso", blocked: "Bloqueo",
  download: "Descarga", kindle: "Kindle", edit: "Cambio", cover: "Portada", rename: "Renombrado", universe: "Universo", refresh: "Actualizar",
  request: "Petición", request_resolved: "Petición", amule: "aMule", error: "Error",
};

function adminLog() {
  const filters = { groups: new Set(), month: "", user: "", q: "" };
  const table = h("div", {}, h("p", { class: "status" }, "Cargando el registro…"));
  const side = h("aside", { class: "stack" });
  const chips = h("div", { class: "chip-row", style: "margin-bottom: 12px" });
  const month = h("select", { class: "input", style: "width: 210px", "aria-label": "Mes" });
  const user = h("input", { class: "input", style: "width: 170px", placeholder: "Usuario", "aria-label": "Filtrar por usuario" });
  const q = h("input", { class: "input", style: "flex: 1; min-width: 200px", type: "search", placeholder: "Libro, IP, detalle…", "aria-label": "Buscar en el registro" });
  let timer;
  const later = () => { clearTimeout(timer); timer = setTimeout(load, 350); };
  user.addEventListener("input", () => { filters.user = user.value; later(); });
  q.addEventListener("input", () => { filters.q = q.value; later(); });
  month.addEventListener("change", () => { filters.month = month.value; load(); });

  const drawChips = () => {
    const all = h("button", { type: "button", class: "fchip", "aria-pressed": String(!filters.groups.size), onclick: () => { filters.groups.clear(); drawChips(); load(); } }, "Todo");
    chips.replaceChildren(all, ...LOG_GROUPS.map(([id, label]) => h("button", { type: "button", class: `fchip k-${id}`, "aria-pressed": String(filters.groups.has(id)), onclick: () => { filters.groups.has(id) ? filters.groups.delete(id) : filters.groups.add(id); drawChips(); load(); } }, h("i"), label)));
  };

  async function load() {
    const params = new URLSearchParams({ month: filters.month, groups: [...filters.groups].join(","), user: filters.user, q: filters.q });
    try {
      const data = await api(`/api/admin/log?${params}`);
      if (!month.options.length) {
        const months = data.months.length ? data.months : [data.month];
        month.replaceChildren(...months.map((m) => h("option", { value: m }, new Date(`${m}-01T12:00`).toLocaleDateString("es", { month: "long", year: "numeric" }))));
      }
      table.replaceChildren(
        data.entries.length
          ? h("div", { class: "table-wrap" }, h("table", { class: "table" },
              h("thead", {}, h("tr", {}, h("th", {}, "Cuándo"), h("th", {}, "Usuario"), h("th", {}, "Qué"), h("th", {}, "Detalle"), h("th", {}, "Desde"))),
              h("tbody", {}, data.entries.map((e) => h("tr", { class: ["login_failed", "blocked", "error", "login_denied"].includes(e.kind) ? "bad" : null },
                h("td", { class: "mono", style: "font-size: 13px; color: var(--text-2); white-space: nowrap" }, whenShort(e.at)),
                h("td", { style: "font-weight: 600" }, e.user ?? "—"),
                h("td", {}, h("span", { class: `kind k-${e.group}` }, KIND_TEXT[e.kind] ?? e.kind)),
                h("td", {}, h("div", { class: "ellipsis", title: [e.title, e.detail].filter(Boolean).join(" · ") }, e.title ?? "", e.detail ? h("span", { style: "color: var(--text-3)" }, ` · ${e.detail}`) : null)),
                h("td", { class: "mono", style: "font-size: 12px; color: var(--text-3); white-space: nowrap" }, e.ip ?? "")
              )))
            ), h("p", { class: "status" }, `${plural(data.entries.length, "evento", "eventos")}${data.entries.length >= 500 ? " (los 500 más recientes)" : ""} · se guardan 12 meses`))
          : h("p", { class: "status" }, "No hay eventos con esos filtros.")
      );
      drawSide(data.today);
    } catch (err) {
      table.replaceChildren(h("p", { class: "status is-error" }, err.message));
    }
  }

  async function drawSide(today) {
    const blocks = await api("/api/admin/login-blocks").catch(() => []);
    const stat = (n, label, bad) => h("div", {}, h("b", { class: "serif", style: `font-size: 28px; display: block; ${bad && n ? "color: var(--danger)" : ""}` }, n ?? 0), h("span", { class: "hint", style: "font-size: 13px" }, label));
    side.replaceChildren(
      h("section", { class: "panel" }, h("h2", { style: "margin-bottom: 4px" }, "Bloqueos de login"), h("p", { class: "status", style: "margin: 0 0 10px" }, "Tras 5 fallos por IP o 10 por cuenta, 15 minutos."),
        blocks.length
          ? blocks.map((b) => h("div", { class: "dl-item", style: "display: flex; align-items: center; gap: 10px" },
              h("div", { style: "flex: 1" }, h("div", { class: b.kind === "ip" ? "mono" : "", style: "font-weight: 600" }, b.key), h("div", { style: "color: var(--danger); font-size: 13px" }, `${b.kind === "ip" ? "IP" : "Cuenta"} · hasta las ${new Date(b.until).toLocaleTimeString("es", { hour: "2-digit", minute: "2-digit" })}`)),
              h("button", { type: "button", class: "btn btn-quiet btn-sm", onclick: async () => { await api("/api/admin/login-blocks/unblock", json("POST", { key: b.key })); toast("Desbloqueado", b.key); load(); } }, "Desbloquear")
            ))
          : h("p", { class: "status" }, "No hay nadie bloqueado.")),
      h("section", { class: "panel" }, h("h2", { style: "margin-bottom: 14px" }, "Hoy"),
        h("div", { style: "display: grid; grid-template-columns: 1fr 1fr; gap: 14px" },
          stat(today.download, "descargas"), stat(today.kindle, "envíos a Kindle"), stat(today.people, "personas han entrado"), stat((today.login_failed ?? 0) + (today.login_denied ?? 0), "logins fallidos", true)))
    );
  }

  drawChips();
  load();
  return h(
    "div",
    { class: "two-col", style: "grid-template-columns: minmax(0, 1fr) 320px" },
    h("section", {}, chips, h("div", { style: "display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 16px" }, month, user, q), table),
    side
  );
}

// --- Rutas ------------------------------------------------------------------

function currentRoute() {
  const [section, ...rest] = location.hash.replace(/^#\/?/, "").split("/");
  const param = rest.join("/");
  return { section: section || "", param: param ? decodeURIComponent(param) : "" };
}

let revealObserver = null;

function render({ focus = true } = {}) {
  if (!state.loaded) return;
  cleanups.forEach((fn) => fn());
  cleanups = [];
  const { section, param } = currentRoute();
  const views = {
    "": viewHome,
    libro: () => viewBook(param),
    universos: viewUniverses,
    universo: () => viewUniverse(param),
    sagas: viewSagas,
    saga: () => viewSaga(param),
    autores: viewAuthors,
    autor: () => viewAuthor(param),
    todos: viewAll,
    buscar: () => viewSearch(param),
    peticiones: () => viewRequests(param),
    admin: () => viewAdmin(param),
  };
  const view = views[section] ?? viewHome;
  const nodes = [view()].flat(Infinity);
  main.replaceChildren(...nodes);
  const hasHero = !!main.querySelector(":scope > .hero, :scope > .page-hero");
  main.classList.toggle("no-hero", !hasHero);
  main.classList.remove("view-enter");
  void main.offsetWidth;
  main.classList.add("view-enter");
  updateTopbar();

  const navKey = { "": "home", universos: "universes", universo: "universes", sagas: "sagas", saga: "sagas", autores: "authors", autor: "authors", todos: "all", peticiones: "requests", admin: "admin" }[section];
  document.querySelectorAll("[data-nav]").forEach((a) => {
    if (a.dataset.nav === navKey) a.setAttribute("aria-current", "page");
    else a.removeAttribute("aria-current");
  });
  if (section !== "buscar" && document.activeElement !== $("search")) $("search").value = "";

  // Las filas aparecen al llegar a ellas
  revealObserver?.disconnect();
  const reveals = main.querySelectorAll(".reveal");
  if ("IntersectionObserver" in window && !reduceMotion) {
    revealObserver = new IntersectionObserver((entries) => entries.forEach((e) => {
      if (e.isIntersecting) {
        e.target.classList.add("in");
        revealObserver.unobserve(e.target);
      }
    }), { rootMargin: "0px 0px -8% 0px" });
    reveals.forEach((el) => revealObserver.observe(el));
  } else reveals.forEach((el) => el.classList.add("in"));

  const heading = main.querySelector("h1");
  document.title = heading ? `${heading.textContent} · BiblioLocal` : "BiblioLocal";
  if (focus) {
    window.scrollTo(0, 0);
    if (section !== "buscar" && section !== "peticiones") heading?.focus({ preventScroll: true });
    announce(document.title);
  }
}

function updateTopbar() {
  $("topbar").classList.toggle("solid", scrollY > 40 || main.classList.contains("no-hero"));
}
addEventListener("scroll", updateTopbar, { passive: true });

state.navCount = 0;
window.addEventListener("hashchange", () => {
  state.navCount++;
  render();
});

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

// --- Menu de la cuenta -----------------------------------------------------------

$("meBtn").addEventListener("click", (e) => {
  e.stopPropagation();
  const menu = $("meMenu");
  menu.hidden = !menu.hidden;
  $("meBtn").setAttribute("aria-expanded", String(!menu.hidden));
});
document.addEventListener("click", (e) => {
  if (!$("meMenu").hidden && !$("meMenu").contains(e.target)) {
    $("meMenu").hidden = true;
    $("meBtn").setAttribute("aria-expanded", "false");
  }
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && !$("meMenu").hidden) {
    $("meMenu").hidden = true;
    $("meBtn").focus();
  }
});

// --- Cambiar portada --------------------------------------------------------

const coverDialog = $("coverDialog");
let coverBook = null;

function openCoverDialog(book) {
  coverBook = book;
  $("coverLead").textContent = `Para «${book.title}». Pulsa una portada para guardarla dentro del epub.`;
  $("coverQuery").value = [book.title, book.author].filter(Boolean).join(" ");
  $("coverResults").replaceChildren();
  $("coverFile").value = "";
  coverDialog.showModal();
  searchCovers();
}

async function searchCovers() {
  const q = $("coverQuery").value.trim();
  if (!q) return;
  $("coverResults").replaceChildren();
  setStatus($("coverStatus"), "Buscando portadas…");
  try {
    const candidates = await api(`/api/covers/search?q=${encodeURIComponent(q)}`);
    setStatus($("coverStatus"), candidates.length ? `${plural(candidates.length, "portada encontrada", "portadas encontradas")}.` : "No hay portadas para esa búsqueda. Prueba solo con el título, o sube una imagen.");
    $("coverResults").replaceChildren(
      ...candidates.map((c) => {
        const lang = c.language === "spa" ? "en español" : c.language ? `idioma ${c.language}` : null;
        const description = [c.publisher, lang].filter(Boolean).join(", ");
        const img = h("img", { src: c.thumbnailUrl, alt: "", loading: "lazy" });
        const option = h(
          "button",
          { type: "button", class: "cover-option", "aria-label": `Usar la portada de ${c.title ?? "esta edición"}${description ? ", " + description : ""}`, onclick: () => applyCover(`/api/books/${coverBook.id}/cover/openlibrary/${c.coverId}`, { method: "POST" }) },
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
  applyCover(`/api/books/${coverBook.id}/cover`, { method: "POST", body: form });
});

async function applyCover(url, options) {
  setStatus($("coverStatus"), "Guardando la portada en el epub…");
  $("coverResults").classList.add("busy");
  try {
    await api(url, options);
    coverDialog.close();
    await reloadAndRender();
    toast("Portada cambiada", coverBook.title);
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
    (kind === "author" ? "Para unificar dos autores, elige de la lista el nombre que quieres conservar." : "Deja el nombre vacío para quitar la saga de esos libros.") +
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
  setStatus($("renameStatus"), "Cambiando…");
  try {
    const url = renameTarget.kind === "author" ? "/api/authors/rename" : "/api/series/rename";
    const result = await api(url, json("POST", { from: renameTarget.value, to }));
    // La saga cambia de nombre: el universo la sigue por el nombre nuevo
    if (renameTarget.kind === "series") {
      const saga = state.sagaByKey.get(normKey(renameTarget.value));
      const universe = saga && universeOfSaga(saga);
      if (universe) {
        const sagas = universe.raw.sagas.filter((s) => normKey(s) !== saga.key);
        if (to) sagas.push(to);
        await api(`/api/admin/universes/${universe.id}`, json("PUT", { name: universe.name, sagas, books: universe.raw.books }));
      }
    }
    await loadData();
    const parts = [`Cambiado en ${plural(result.updated, "libro", "libros")}.`];
    if (result.unchanged) parts.push(`${plural(result.unchanged, "ya lo tenía", "ya lo tenían")}.`);
    if (result.skipped.length) parts.push(`${plural(result.skipped.length, "no es epub", "no son epub")}.`);
    if (result.failed.length) parts.push(`No se pudieron cambiar: ${result.failed.join("; ")}`);
    const ok = !result.failed.length;
    setStatus($("renameStatus"), parts.join(" "), ok ? "ok" : "error");
    const section = renameTarget.kind === "author" ? "autor" : "saga";
    if (currentRoute().section === section) location.hash = to ? `#/${section}/${encodeURIComponent(normKey(to))}` : "#/sagas";
    else render({ focus: false });
    if (ok) setTimeout(() => renameDialog.open && renameDialog.close(), 1200);
  } catch (err) {
    setStatus($("renameStatus"), err.message, "error");
  } finally {
    $("renameSave").disabled = false;
  }
});

// --- Universos: crear, renombrar, borrar y asignar --------------------------------

const universeDialog = $("universeDialog");
let universeTarget = null;

function openUniverseDialog(target) {
  universeTarget = target;
  const current = target.kind === "saga" ? universeOfSaga(target.saga) : state.universes.find((u) => u.books.includes(target.book));
  $("universeTitle").textContent = target.kind === "saga" ? `Universo de ${target.saga.name}` : `Universo de ${target.book.title}`;
  $("universeLead").textContent = target.kind === "saga" ? "Toda la saga pasa a formar parte del universo que elijas." : "Para libros sueltos que comparten mundo con otras sagas (como Elantris en el Cosmere).";
  const option = (value, label, checked, sub) =>
    h("label", { class: "option" }, h("input", { type: "radio", name: "universe", value, checked: checked || null }), h("span", {}, h("b", {}, label), sub ? h("span", { class: "hint", style: "display: block; font-size: 13px" }, sub) : null));
  $("universeOptions").replaceChildren(
    option("", "Ninguno", !current),
    ...state.universes.map((u) => option(u.id, u.name, current === u, [u.sagas.length ? plural(u.sagas.length, "saga", "sagas") : null, plural(u.all.length, "libro", "libros")].filter(Boolean).join(" · "))),
    option("new", "Universo nuevo…", false)
  );
  $("universeNewField").hidden = true;
  $("universeNewName").value = "";
  setStatus($("universeStatus"), "");
  universeDialog.showModal();
}

$("universeOptions").addEventListener("change", (e) => {
  $("universeNewField").hidden = e.target.value !== "new";
  if (e.target.value === "new") $("universeNewName").focus();
});

/**
 * Mete una saga ({kind: "saga", saga}) o un libro suelto ({kind: "book", book}) en
 * un universo: {id} uno que existe, {name} uno nuevo, null ninguno. Sale del que
 * estuviera. Devuelve el universo final (o null).
 */
async function setUniverse(t, choice) {
  const isSaga = t.kind === "saga";
  const without = (u) => ({
    name: u.name,
    sagas: isSaga ? u.raw.sagas.filter((s) => normKey(s) !== t.saga.key) : u.raw.sagas,
    books: isSaga ? u.raw.books : u.raw.books.filter((id) => id !== t.book.id),
  });
  for (const u of state.universes) {
    const inIt = isSaga ? u.raw.sagas.some((s) => normKey(s) === t.saga.key) : u.raw.books.includes(t.book.id);
    if (inIt && u.id !== choice?.id) await api(`/api/admin/universes/${u.id}`, json("PUT", without(u)));
  }
  if (choice?.name) {
    return api("/api/admin/universes", json("POST", { name: choice.name, sagas: isSaga ? [t.saga.name] : [], books: isSaga ? [] : [t.book.id] }));
  }
  if (choice?.id) {
    const u = state.universeById.get(choice.id);
    const body = without(u);
    if (isSaga) body.sagas.push(t.saga.name);
    else body.books.push(t.book.id);
    return api(`/api/admin/universes/${u.id}`, json("PUT", body));
  }
  return null;
}

$("universeForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const choice = new FormData($("universeForm")).get("universe") ?? "";
  const t = universeTarget;
  const isSaga = t.kind === "saga";
  $("universeSave").disabled = true;
  try {
    let target;
    if (choice === "new") {
      const name = $("universeNewName").value.trim();
      if (!name) throw new Error("Ponle un nombre al universo nuevo.");
      const existing = state.universes.find((u) => normKey(u.name) === normKey(name));
      target = await setUniverse(t, existing ? { id: existing.id } : { name });
    } else {
      target = await setUniverse(t, choice ? { id: choice } : null);
    }
    universeDialog.close();
    await reloadAndRender();
    toast(target ? `Ahora en el universo ${target.name}` : "Fuera de cualquier universo", isSaga ? t.saga.name : t.book.title, "ok", target ? { href: `#/universo/${target.id}`, text: "Ver el universo" } : null);
  } catch (err) {
    setStatus($("universeStatus"), err.message, "error");
  } finally {
    $("universeSave").disabled = false;
  }
});

async function createUniverse() {
  const name = prompt("Nombre del universo (por ejemplo, Tierra Media):");
  if (!name?.trim()) return;
  try {
    const u = await api("/api/admin/universes", json("POST", { name: name.trim(), sagas: [], books: [] }));
    await loadData();
    location.hash = `#/universo/${u.id}`;
    toast("Universo creado", "Añádele sagas desde la página de cada saga.");
  } catch (err) {
    toast("No se pudo crear", err.message, "error");
  }
}

async function renameUniverse(u) {
  const name = prompt("Nuevo nombre del universo:", u.name);
  if (!name?.trim() || name.trim() === u.name) return;
  try {
    await api(`/api/admin/universes/${u.id}`, json("PUT", { name: name.trim(), sagas: u.raw.sagas, books: u.raw.books }));
    await reloadAndRender();
    toast("Universo renombrado", name.trim());
  } catch (err) {
    toast("No se pudo renombrar", err.message, "error");
  }
}

async function deleteUniverse(u) {
  if (!confirm(`¿Borrar el universo ${u.name}? Las sagas y los libros no se tocan.`)) return;
  try {
    await api(`/api/admin/universes/${u.id}`, { method: "DELETE" });
    await loadData();
    location.hash = "#/universos";
    toast("Universo borrado", u.name);
  } catch (err) {
    toast("No se pudo borrar", err.message, "error");
  }
}

// --- Kindle -----------------------------------------------------------------

const kindleDialog = $("kindleDialog");

async function openKindleDialog() {
  $("meMenu").hidden = true;
  setStatus($("kindleStatus"), "");
  kindleDialog.showModal();
  try {
    const { email } = await api("/api/me/kindle-email");
    $("kindleEmail").value = email;
  } catch (err) {
    setStatus($("kindleStatus"), err.message, "error");
  }
}
$("kindleBtn").addEventListener("click", openKindleDialog);

$("kindleForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  $("kindleSave").disabled = true;
  try {
    await api("/api/me/kindle-email", json("PUT", { email: $("kindleEmail").value.trim() }));
    setStatus($("kindleStatus"), "Dirección guardada.", "ok");
    setTimeout(() => kindleDialog.open && kindleDialog.close(), 900);
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
  try {
    await api("/api/index/refresh", { method: "POST" });
    await reloadAndRender();
    toast("Biblioteca actualizada", plural(state.books.length, "libro", "libros"));
  } catch (err) {
    toast("No se pudo actualizar", err.message, "error");
  } finally {
    button.disabled = false;
    button.classList.remove("busy");
  }
});

// --- Arranque ---------------------------------------------------------------

loadData()
  .then(() => {
    render({ focus: false });
    // Lo que has pedido y ya ha llegado (o se ha rechazado) desde la ultima vez
    for (const u of state.me.updates ?? []) {
      if (u.status === "done") toast("Ya está en la biblioteca", `«${u.title}», que pediste.`, "ok", u.bookId ? { href: `#/libro/${encodeURIComponent(u.bookId)}`, text: "Abrir la ficha" } : null);
      else if (u.status === "rejected") toast("Petición rechazada", `«${u.title}». Mira la respuesta en Peticiones.`, "info", { href: "#/peticiones", text: "Ver peticiones" });
    }
  })
  .catch((err) => {
    main.classList.add("no-hero");
    main.replaceChildren(emptyState("No se pudo cargar la biblioteca", err.message));
    updateTopbar();
  });
