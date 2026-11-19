(function () {
  function stored() {
    try {
      return localStorage.getItem("theme");
    } catch {
      return null;
    }
  }

  function effectiveTheme() {
    const value = stored();
    if (value === "light" || value === "dark") return value;
    return matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark";
  }

  function apply(theme) {
    document.documentElement.setAttribute("data-theme", theme);
  }

  // Aplicar antes de pintar para evitar parpadeo
  apply(effectiveTheme());

  window.initThemeToggle = function (buttonEl) {
    function render() {
      const dark = document.documentElement.getAttribute("data-theme") === "dark";
      const label = dark ? "Usar tema claro" : "Usar tema oscuro";
      buttonEl.innerHTML =
        `<svg class="icon" aria-hidden="true"><use href="#${dark ? "i-sun" : "i-moon"}"/></svg>` +
        `<span class="visually-hidden">${label}</span>`;
      buttonEl.title = label;
    }
    buttonEl.addEventListener("click", () => {
      const next = document.documentElement.getAttribute("data-theme") === "dark" ? "light" : "dark";
      try {
        localStorage.setItem("theme", next);
      } catch {
        /* sin almacenamiento: el cambio dura hasta recargar */
      }
      apply(next);
      render();
    });
    render();
  };
})();
