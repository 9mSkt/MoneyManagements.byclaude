// Shared behaviour for every page: theme switch, sheets (dialogs), HTMX CSRF header, idle sign-out.
(function () {
  var root = document.documentElement;

  function setTheme(theme) {
    root.setAttribute('data-theme', theme);
    try { localStorage.setItem('theme', theme); } catch (e) { /* storage blocked */ }
    document.querySelectorAll('[data-set-theme]').forEach(function (b) {
      b.classList.toggle('on', b.getAttribute('data-set-theme') === theme);
    });
    var meta = document.querySelector('meta[name="theme-color"]');
    if (meta) { meta.setAttribute('content', getComputedStyle(root).getPropertyValue('--bg').trim()); }
    document.dispatchEvent(new CustomEvent('themechange', { detail: theme }));
  }

  document.addEventListener('click', function (e) {
    var toggle = e.target.closest('[data-theme-toggle]');
    if (toggle) {
      setTheme(root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark');
      return;
    }
    var set = e.target.closest('[data-set-theme]');
    if (set) {
      setTheme(set.getAttribute('data-set-theme'));
      return;
    }
    var open = e.target.closest('[data-open]');
    if (open) {
      var dlg = document.getElementById(open.getAttribute('data-open'));
      if (dlg && dlg.showModal) { dlg.showModal(); }
      return;
    }
    if (e.target.closest('[data-close]')) {
      var parent = e.target.closest('dialog');
      if (parent) { parent.close(); }
      return;
    }
    // Click on the dialog backdrop closes it.
    if (e.target.tagName === 'DIALOG') { e.target.close(); }
  });

  document.addEventListener('DOMContentLoaded', function () {
    setTheme(root.getAttribute('data-theme') || 'light');
  });

  // HTMX: send the CSRF token with every request.
  document.addEventListener('htmx:configRequest', function (e) {
    var token = document.querySelector('meta[name="_csrf"]');
    var header = document.querySelector('meta[name="_csrf_header"]');
    if (token && header) { e.detail.headers[header.content] = token.content; }
  });

  // Idle sign-out: the server session ends after the timeout; send the browser to the login page at that moment.
  var timeoutMeta = document.querySelector('meta[name="session-timeout"]');
  if (timeoutMeta) {
    var seconds = parseInt(timeoutMeta.content, 10) || 1800;
    var timer;
    var reset = function () {
      clearTimeout(timer);
      timer = setTimeout(function () { window.location.href = '/login?timeout'; }, seconds * 1000);
    };
    document.addEventListener('htmx:afterRequest', reset);
    reset();
  }
})();
