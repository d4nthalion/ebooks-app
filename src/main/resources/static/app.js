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
};

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
  const authors = await fetchJson("/api/authors");
  renderFilterList(el.authorList, authors, "author", onAuthorSelected);
  await loadSeriesFilter();
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
    li.textContent = item;
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
      img.src = `/api/books/${book.id}/cover`;
      img.alt = book.title;
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

function openModal(book) {
  el.modalTitle.textContent = book.title;
  el.modalAuthor.textContent = book.author ?? "";
  el.modalSeries.textContent = book.series ? `Saga: ${book.series}` : "";
  el.modalMeta.textContent = `${book.extension.toUpperCase()} · ${formatSize(book.sizeBytes)}`;
  el.modalCover.src = book.hasCover ? `/api/books/${book.id}/cover` : "";
  el.modalCover.style.visibility = book.hasCover ? "visible" : "hidden";
  el.modalDownload.href = `/api/books/${book.id}/download`;
  el.modalOverlay.hidden = false;
}

function closeModal() {
  el.modalOverlay.hidden = true;
}

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
