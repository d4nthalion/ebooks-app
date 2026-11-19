const state = {
  books: [],
  author: null,
  series: null,
};

const el = {
  search: document.getElementById("search"),
  refreshBtn: document.getElementById("refreshBtn"),
  authorList: document.getElementById("authorList"),
  seriesList: document.getElementById("seriesList"),
  grid: document.getElementById("grid"),
  empty: document.getElementById("empty"),
  status: document.getElementById("status"),
  modalOverlay: document.getElementById("modalOverlay"),
  modalClose: document.getElementById("modalClose"),
  modalCover: document.getElementById("modalCover"),
  modalTitle: document.getElementById("modalTitle"),
  modalAuthor: document.getElementById("modalAuthor"),
  modalSeries: document.getElementById("modalSeries"),
  modalMeta: document.getElementById("modalMeta"),
  modalDownload: document.getElementById("modalDownload"),
  modalSendKindle: document.getElementById("modalSendKindle"),
  modalKindleStatus: document.getElementById("modalKindleStatus"),
  settingsBtn: document.getElementById("settingsBtn"),
  settingsOverlay: document.getElementById("settingsOverlay"),
  settingsClose: document.getElementById("settingsClose"),
  kindleEmailInput: document.getElementById("kindleEmailInput"),
  kindleEmailSave: document.getElementById("kindleEmailSave"),
  settingsStatus: document.getElementById("settingsStatus"),
  modalEditActions: document.getElementById("modalEditActions"),
  modalEdit: document.getElementById("modalEdit"),
  modalCoverBtn: document.getElementById("modalCover"),
  editForm: document.getElementById("editForm"),
  editTitle: document.getElementById("editTitle"),
  editAuthor: document.getElementById("editAuthor"),
  editSeries: document.getElementById("editSeries"),
  editSeriesIndex: document.getElementById("editSeriesIndex"),
  editSave: document.getElementById("editSave"),
  editCancel: document.getElementById("editCancel"),
  editStatus: document.getElementById("editStatus"),
  coverOverlay: document.getElementById("coverOverlay"),
  coverClose: document.getElementById("coverClose"),
  coverSearchForm: document.getElementById("coverSearchForm"),
  coverQuery: document.getElementById("coverQuery"),
  coverFile: document.getElementById("coverFile"),
  coverStatus: document.getElementById("coverStatus"),
  coverResults: document.getElementById("coverResults"),
  renameOverlay: document.getElementById("renameOverlay"),
  renameClose: document.getElementById("renameClose"),
  renameForm: document.getElementById("renameForm"),
  renameTitle: document.getElementById("renameTitle"),
  renameInfo: document.getElementById("renameInfo"),
  renameInput: document.getElementById("renameInput"),
  renameSave: document.getElementById("renameSave"),
  renameStatus: document.getElementById("renameStatus"),
  authorOptions: document.getElementById("authorOptions"),
  seriesOptions: document.getElementById("seriesOptions"),
};

let currentBook = null;

function coverUrl(book) {
  // lastModified cambia al editar el epub: evita que el navegador muestre la portada vieja
  return `/api/books/${book.id}/cover?v=${book.lastModified}`;
}

/** fetch que devuelve el JSON o lanza un Error con el mensaje del servidor. */
async function apiCall(url, options) {
  let res;
  try {
    res = await fetch(url, options);
  } catch {
    throw new Error("Error de red");
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error ?? `Error ${res.status}`);
  return data;
}

function showStatus(statusEl, text) {
  statusEl.textContent = text;
  statusEl.hidden = !text;
}

function formatSize(bytes) {
  if (!bytes) return "";
  const mb = bytes / (1024 * 1024);
  return mb >= 1 ? `${mb.toFixed(1)} MB` : `${(bytes / 1024).toFixed(0)} KB`;
}

async function fetchJson(url, options) {
  const res = await fetch(url, options);
  if (!res.ok) throw new Error(`${url}: ${res.status}`);
  return res.json();
}

async function loadFilters() {
  const [authors, allSeries] = await Promise.all([fetchJson("/api/authors"), fetchJson("/api/series")]);
  renderFilterList(el.authorList, authors, "author", onAuthorSelected);
  fillDatalist(el.authorOptions, authors);
  fillDatalist(el.seriesOptions, allSeries);
  await loadSeriesFilter();
}

function fillDatalist(listEl, values) {
  listEl.innerHTML = "";
  for (const value of values) {
    const option = document.createElement("option");
    option.value = value;
    listEl.appendChild(option);
  }
}

async function loadSeriesFilter() {
  const params = new URLSearchParams();
  if (state.author) params.set("author", state.author);
  const series = await fetchJson(`/api/series?${params.toString()}`);
  renderFilterList(el.seriesList, series, "series", loadBooks);
}

async function onAuthorSelected() {
  state.series = null;
  await loadSeriesFilter();
  await loadBooks();
}

function renderFilterList(listEl, items, kind, onSelect) {
  listEl.innerHTML = "";
  const allItem = document.createElement("li");
  allItem.textContent = "Todos";
  allItem.className = state[kind] === null ? "active" : "";
  allItem.addEventListener("click", () => {
    state[kind] = null;
    highlightActive(listEl, allItem);
    onSelect();
  });
  listEl.appendChild(allItem);

  for (const item of items) {
    const li = document.createElement("li");
    const label = document.createElement("span");
    label.textContent = item;
    const rename = document.createElement("button");
    rename.className = "rename-btn";
    rename.textContent = "✎";
    rename.title = kind === "author" ? "Renombrar este autor en todos sus libros" : "Renombrar esta saga en todos sus libros";
    rename.setAttribute("aria-label", rename.title);
    rename.addEventListener("click", (e) => {
      e.stopPropagation();
      openRename(kind, item);
    });
    li.append(label, rename);
    li.className = state[kind] === item ? "active" : "";
    li.addEventListener("click", () => {
      state[kind] = item;
      highlightActive(listEl, li);
      onSelect();
    });
    listEl.appendChild(li);
  }
}

function highlightActive(listEl, activeLi) {
  [...listEl.children].forEach((c) => c.classList.remove("active"));
  activeLi.classList.add("active");
}

async function loadBooks() {
  const params = new URLSearchParams();
  if (el.search.value.trim()) params.set("search", el.search.value.trim());
  if (state.author) params.set("author", state.author);
  if (state.series) params.set("series", state.series);

  const books = await fetchJson(`/api/books?${params.toString()}`);
  state.books = books;
  renderGrid(books);
}

function renderGrid(books) {
  el.grid.innerHTML = "";
  el.empty.hidden = books.length > 0;
  el.status.textContent = `${books.length} libro${books.length === 1 ? "" : "s"}`;

  for (const book of books) {
    const card = document.createElement("div");
    card.className = "card";
    card.addEventListener("click", () => openModal(book));

    const cover = document.createElement("div");
    cover.className = "card-cover";
    if (book.hasCover) {
      const img = document.createElement("img");
      img.src = coverUrl(book);
      img.alt = book.title;
      img.loading = "lazy";
      cover.appendChild(img);
    } else {
      const placeholder = document.createElement("span");
      placeholder.className = "placeholder";
      placeholder.textContent = "📖";
      cover.appendChild(placeholder);
    }

    const title = document.createElement("div");
    title.className = "card-title";
    title.textContent = book.title;

    const author = document.createElement("div");
    author.className = "card-author";
    author.textContent = book.author ?? "";

    card.append(cover, title, author);
    el.grid.appendChild(card);
  }
}

function showBookInModal(book) {
  currentBook = book;
  el.modalTitle.textContent = book.title;
  el.modalAuthor.textContent = book.author ?? "";
  el.modalSeries.textContent = book.series
    ? `Saga: ${book.series}${book.seriesIndex ? ` · n.º ${book.seriesIndex}` : ""}`
    : "";
  el.modalMeta.textContent = `${book.extension.toUpperCase()} · ${formatSize(book.sizeBytes)}`;
  el.modalCover.src = book.hasCover ? coverUrl(book) : "";
  el.modalCover.style.visibility = book.hasCover ? "visible" : "hidden";
  el.modalDownload.href = `/api/books/${book.id}/download`;
  el.modalEditActions.hidden = book.extension !== "epub";
}

function openModal(book) {
  showBookInModal(book);
  el.editForm.hidden = true;
  el.modalKindleStatus.hidden = true;
  el.modalSendKindle.disabled = false;
  el.modalSendKindle.textContent = "📧 Enviar a Kindle";
  el.modalOverlay.hidden = false;
}

async function sendCurrentBookToKindle() {
  if (!currentBook) return;
  el.modalSendKindle.disabled = true;
  el.modalSendKindle.textContent = "Enviando...";
  el.modalKindleStatus.hidden = true;
  try {
    const res = await fetch(`/api/books/${currentBook.id}/send-to-kindle`, { method: "POST" });
    const data = await res.json();
    el.modalKindleStatus.textContent = res.ok ? `Enviado: ${data.status}` : data.error ?? "No se pudo enviar";
    el.modalKindleStatus.hidden = false;
  } catch {
    el.modalKindleStatus.textContent = "No se pudo enviar (error de red)";
    el.modalKindleStatus.hidden = false;
  } finally {
    el.modalSendKindle.disabled = false;
    el.modalSendKindle.textContent = "📧 Enviar a Kindle";
  }
}

el.modalSendKindle.addEventListener("click", sendCurrentBookToKindle);

async function openSettings() {
  el.settingsStatus.hidden = true;
  try {
    const { email } = await fetchJson("/api/me/kindle-email");
    el.kindleEmailInput.value = email;
  } catch {
    el.kindleEmailInput.value = "";
  }
  el.settingsOverlay.hidden = false;
}

function closeSettings() {
  el.settingsOverlay.hidden = true;
}

el.settingsBtn.addEventListener("click", openSettings);
el.settingsClose.addEventListener("click", closeSettings);
el.settingsOverlay.addEventListener("click", (e) => {
  if (e.target === el.settingsOverlay) closeSettings();
});

el.kindleEmailSave.addEventListener("click", async () => {
  el.kindleEmailSave.disabled = true;
  try {
    await fetch("/api/me/kindle-email", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email: el.kindleEmailInput.value.trim() }),
    });
    el.settingsStatus.textContent = "Guardado.";
    el.settingsStatus.hidden = false;
  } finally {
    el.kindleEmailSave.disabled = false;
  }
});

function closeModal() {
  el.modalOverlay.hidden = true;
}

/** Tras editar: refresca la ficha abierta, la rejilla y los filtros. */
async function afterEdit(book) {
  showBookInModal(book);
  await Promise.all([loadFilters(), loadBooks()]);
}

// --- Editar datos ------------------------------------------------------------

el.modalEdit.addEventListener("click", () => {
  el.editTitle.value = currentBook.title ?? "";
  el.editAuthor.value = currentBook.author ?? "";
  el.editSeries.value = currentBook.series ?? "";
  el.editSeriesIndex.value = currentBook.seriesIndex ?? "";
  showStatus(el.editStatus, "");
  el.editForm.hidden = false;
  el.editTitle.focus();
});

el.editCancel.addEventListener("click", () => {
  el.editForm.hidden = true;
});

el.editForm.addEventListener("submit", async (e) => {
  e.preventDefault();
  // Solo se envia lo que ha cambiado (null = no tocar)
  const changed = (value, original) => (value.trim() === (original ?? "") ? null : value.trim());
  const body = {
    title: changed(el.editTitle.value, currentBook.title),
    author: changed(el.editAuthor.value, currentBook.author),
    series: changed(el.editSeries.value, currentBook.series),
    seriesIndex: changed(el.editSeriesIndex.value, currentBook.seriesIndex),
  };
  if (Object.values(body).every((v) => v === null)) {
    el.editForm.hidden = true;
    return;
  }
  el.editSave.disabled = true;
  showStatus(el.editStatus, "Guardando...");
  try {
    const book = await apiCall(`/api/books/${currentBook.id}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    el.editForm.hidden = true;
    await afterEdit(book);
  } catch (err) {
    showStatus(el.editStatus, err.message);
  } finally {
    el.editSave.disabled = false;
  }
});

// --- Portada -----------------------------------------------------------------

el.modalCoverBtn.addEventListener("click", () => {
  el.coverQuery.value = [currentBook.title, currentBook.author].filter(Boolean).join(" ");
  el.coverResults.innerHTML = "";
  el.coverFile.value = "";
  el.coverOverlay.hidden = false;
  searchCovers();
});

async function searchCovers() {
  const q = el.coverQuery.value.trim();
  if (!q) return;
  el.coverResults.innerHTML = "";
  showStatus(el.coverStatus, "Buscando...");
  try {
    const candidates = await apiCall(`/api/covers/search?q=${encodeURIComponent(q)}`);
    showStatus(
      el.coverStatus,
      candidates.length
        ? "Pulsa una portada para usarla."
        : "No se encontraron portadas. Prueba con otro texto o sube una imagen."
    );
    for (const c of candidates) {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "cover-option";
      btn.title = [c.title, c.publisher, c.language].filter(Boolean).join(" · ");
      const img = document.createElement("img");
      img.src = c.thumbnailUrl;
      img.alt = btn.title;
      img.loading = "lazy";
      // Open Library a veces devuelve un pixel vacio: se oculta esa opcion
      img.addEventListener("load", () => {
        if (img.naturalWidth < 10) btn.remove();
      });
      img.addEventListener("error", () => btn.remove());
      const caption = document.createElement("span");
      const lang = c.language === "spa" ? "ES" : c.language?.toUpperCase();
      caption.textContent = [c.publisher, lang].filter(Boolean).join(" · ");
      btn.append(img, caption);
      btn.addEventListener("click", () =>
        applyCover(`/api/books/${currentBook.id}/cover/openlibrary/${c.coverId}`, { method: "POST" })
      );
      el.coverResults.appendChild(btn);
    }
  } catch (err) {
    showStatus(el.coverStatus, `No se pudo buscar: ${err.message}`);
  }
}

el.coverSearchForm.addEventListener("submit", (e) => {
  e.preventDefault();
  searchCovers();
});

el.coverFile.addEventListener("change", () => {
  const file = el.coverFile.files[0];
  if (!file) return;
  const form = new FormData();
  form.append("file", file);
  applyCover(`/api/books/${currentBook.id}/cover`, { method: "POST", body: form });
});

async function applyCover(url, options) {
  showStatus(el.coverStatus, "Guardando la portada en el epub...");
  el.coverResults.classList.add("busy");
  try {
    const book = await apiCall(url, options);
    el.coverOverlay.hidden = true;
    await afterEdit(book);
  } catch (err) {
    showStatus(el.coverStatus, err.message);
  } finally {
    el.coverResults.classList.remove("busy");
    el.coverFile.value = "";
  }
}

function closeCover() {
  el.coverOverlay.hidden = true;
}
el.coverClose.addEventListener("click", closeCover);
el.coverOverlay.addEventListener("click", (e) => {
  if (e.target === el.coverOverlay) closeCover();
});

// --- Renombrar autor / saga en bloque -------------------------------------------

let renameTarget = null;

async function openRename(kind, value) {
  renameTarget = { kind, value };
  el.renameTitle.textContent = kind === "author" ? "Renombrar autor" : "Renombrar saga";
  el.renameInput.value = value;
  el.renameInput.setAttribute("list", kind === "author" ? "authorOptions" : "seriesOptions");
  showStatus(el.renameStatus, "");
  el.renameInfo.textContent = "";
  el.renameOverlay.hidden = false;
  el.renameInput.select();

  const params = new URLSearchParams({ [kind]: value });
  const books = await fetchJson(`/api/books?${params.toString()}`).catch(() => []);
  const epubs = books.filter((b) => b.extension === "epub").length;
  const others = books.length - epubs;
  el.renameInfo.textContent =
    `«${value}» aparece en ${books.length} libro${books.length === 1 ? "" : "s"}. ` +
    (kind === "author"
      ? "Escribe el nombre bueno (o elige uno que ya exista) para unificarlos."
      : "Deja el nombre vacio para quitar la saga de esos libros.") +
    (others ? ` ${others} no son epub y no se pueden cambiar.` : "");
}

el.renameForm.addEventListener("submit", async (e) => {
  e.preventDefault();
  const to = el.renameInput.value.trim();
  if (to === renameTarget.value) {
    closeRename();
    return;
  }
  if (!to && renameTarget.kind === "author") {
    showStatus(el.renameStatus, "El autor no puede quedar vacio.");
    return;
  }
  el.renameSave.disabled = true;
  showStatus(el.renameStatus, "Cambiando...");
  try {
    const url = renameTarget.kind === "author" ? "/api/authors/rename" : "/api/series/rename";
    const result = await apiCall(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ from: renameTarget.value, to }),
    });
    if (state[renameTarget.kind] === renameTarget.value) state[renameTarget.kind] = to || null;
    await Promise.all([loadFilters(), loadBooks()]);
    const n = result.updated;
    const unchanged = result.unchanged ? ` ${result.unchanged} ya lo tenian.` : "";
    const skipped = result.skipped.length ? ` ${result.skipped.length} sin cambiar (no son epub).` : "";
    const failed = result.failed.length ? ` No se pudieron cambiar ${result.failed.length}: ${result.failed.join(" · ")}` : "";
    showStatus(el.renameStatus, `Cambiado en ${n} libro${n === 1 ? "" : "s"}.${unchanged}${skipped}${failed}`);
    if (!skipped && !failed) setTimeout(closeRename, 900);
  } catch (err) {
    showStatus(el.renameStatus, err.message);
  } finally {
    el.renameSave.disabled = false;
  }
});

function closeRename() {
  el.renameOverlay.hidden = true;
}
el.renameClose.addEventListener("click", closeRename);
el.renameOverlay.addEventListener("click", (e) => {
  if (e.target === el.renameOverlay) closeRename();
});

el.modalClose.addEventListener("click", closeModal);
el.modalOverlay.addEventListener("click", (e) => {
  if (e.target === el.modalOverlay) closeModal();
});

let searchTimeout;
el.search.addEventListener("input", () => {
  clearTimeout(searchTimeout);
  searchTimeout = setTimeout(loadBooks, 250);
});

el.refreshBtn.addEventListener("click", async () => {
  el.refreshBtn.disabled = true;
  el.refreshBtn.textContent = "Reescaneando...";
  try {
    await fetch("/api/index/refresh", { method: "POST" });
    await Promise.all([loadFilters(), loadBooks()]);
  } finally {
    el.refreshBtn.disabled = false;
    el.refreshBtn.textContent = "⟳ Reescanear";
  }
});

loadFilters();
loadBooks();
