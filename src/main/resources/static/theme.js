(function () {
  function effectiveTheme() {
    const stored = localStorage.getItem("theme");
    if (stored === "light" || stored === "dark") return stored;
    return matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  }

  function apply(theme) {
    document.documentElement.setAttribute("data-theme", theme);
  }

  // Aplicar antes de pintar para evitar parpadeo
  apply(effectiveTheme());

  window.initThemeToggle = function (buttonEl) {
    function render() {
      const current = document.documentElement.getAttribute("data-theme");
      buttonEl.textContent = current === "dark" ? "☀" : "🌙";
      buttonEl.title = current === "dark" ? "Cambiar a claro" : "Cambiar a oscuro";
    }
    buttonEl.addEventListener("click", () => {
      const next = document.documentElement.getAttribute("data-theme") === "dark" ? "light" : "dark";
      localStorage.setItem("theme", next);
      apply(next);
      render();
    });
    render();
  };
})();
