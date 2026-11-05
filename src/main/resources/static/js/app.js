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
      if (dlg && dlg.showModal) {
        dlg.showModal();
        var first = dlg.querySelector('[data-autofocus]');
        if (first) { first.focus(); }
      }
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

  // ---------- Transaction form: Expense / Income / Saving switch shows the matching category list ----------
  function store(key, value) {
    try {
      if (value === undefined) { return localStorage.getItem(key); }
      localStorage.setItem(key, value);
    } catch (err) { /* storage blocked: the form still works */ }
    return null;
  }

  function showType(form, type, restore) {
    form.querySelectorAll('select[data-for-type]').forEach(function (sel) {
      var on = sel.getAttribute('data-for-type') === type;
      sel.hidden = !on;
      sel.disabled = !on;
      if (on && restore) {
        var last = store('lastCategory.' + type);
        if (last && sel.querySelector('option[value="' + last + '"]')) { sel.value = last; }
      }
    });
    // Fields that only apply to one type (the installment period is for expenses).
    form.querySelectorAll('[data-only-type]').forEach(function (box) {
      var on = box.getAttribute('data-only-type') === type;
      box.hidden = !on;
      box.querySelectorAll('input').forEach(function (i) { i.disabled = !on; });
    });
  }

  document.addEventListener('change', function (e) {
    if (e.target.matches('[data-type-radio]')) {
      var form = e.target.closest('[data-tx-form]');
      if (form) { showType(form, e.target.value, form.hasAttribute('data-remember-category')); }
    }
  });

  document.addEventListener('submit', function (e) {
    var form = e.target;
    // Ask before deleting.
    var question = form.getAttribute && form.getAttribute('data-confirm');
    if (question && !window.confirm(question)) {
      e.preventDefault();
      return;
    }
    // Quick add remembers the last category per type, so the next entry is one tap shorter.
    if (form.hasAttribute && form.hasAttribute('data-remember-category')) {
      var checked = form.querySelector('[data-type-radio]:checked');
      var sel = checked && form.querySelector('select[data-for-type="' + checked.value + '"]');
      if (sel) { store('lastCategory.' + checked.value, sel.value); }
    }
  });

  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('[data-remember-category]').forEach(function (form) {
      var checked = form.querySelector('[data-type-radio]:checked');
      showType(form, checked ? checked.value : 'EXPENSE', true);
    });
  });

  // ---------- Installment form: show the calculated monthly charge ----------
  function num(input) {
    var v = parseFloat(String(input && input.value || '').replace(/[,฿%\s]/g, ''));
    return isNaN(v) ? 0 : v;
  }
  function updateInstallment(form) {
    var full = num(form.querySelector('[data-calc="full"]'));
    var months = num(form.querySelector('[data-calc="months"]'));
    var rate = num(form.querySelector('[data-calc="rate"]'));
    var out = form.querySelector('[data-calc="out"]');
    if (!out) { return; }
    if (full > 0 && months > 0) {
      var monthly = full / months + full * rate / 100;
      out.placeholder = monthly.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    } else {
      out.placeholder = '';
    }
  }
  document.addEventListener('input', function (e) {
    var form = e.target.closest && e.target.closest('[data-installment-form]');
    if (form) { updateInstallment(form); }
  });
  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('[data-installment-form]').forEach(updateInstallment);
  });

  // ---------- Budget form: % / ฿ / none per group, with the baht it works out to ----------
  var bahtFmt = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  function updateBudgetRow(row, base) {
    var mode = row.querySelector('[data-mode]:checked');
    var input = row.querySelector('input.input');
    var preview = row.querySelector('[data-preview]');
    if (!mode || !input) { return; }
    input.disabled = mode.value === 'NONE';
    input.placeholder = mode.value === 'PERCENT' ? '%' : (mode.value === 'AMOUNT' ? '฿' : '');
    if (!preview) { return; }
    var v = num(input);
    if (mode.value === 'PERCENT' && input.value.trim() !== '') {
      preview.textContent = '= ฿' + bahtFmt.format(base * v / 100);
    } else if (mode.value === 'AMOUNT' && input.value.trim() !== '' && base > 0) {
      preview.textContent = '= ' + (v / base * 100).toFixed(1) + '%';
    } else {
      preview.textContent = '';
    }
  }
  function budgetForm(el) {
    var form = el.closest && el.closest('[data-budget-form]');
    return form ? { form: form, base: parseFloat(form.getAttribute('data-base')) || 0 } : null;
  }
  ['input', 'change'].forEach(function (type) {
    document.addEventListener(type, function (e) {
      var b = budgetForm(e.target);
      var row = b && e.target.closest('.budget-edit-row');
      if (row) {
        updateBudgetRow(row, b.base);
        if (e.target.matches('[data-mode]') && e.target.value !== 'NONE') {
          row.querySelector('input.input').focus();
        }
      }
    });
  });
  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('[data-budget-form]').forEach(function (form) {
      var base = parseFloat(form.getAttribute('data-base')) || 0;
      form.querySelectorAll('.budget-edit-row').forEach(function (row) { updateBudgetRow(row, base); });
    });
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
