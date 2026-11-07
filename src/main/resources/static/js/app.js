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

  // ---------- Installment period preview: "1/3" on the add form shows the plan it will start ----------
  // Gregorian years in Thai too (2026, not 2569), matching the rest of the app.
  var pageLocale = (document.documentElement.lang === 'th') ? 'th-TH-u-ca-gregory' : 'en-GB';
  var monthFmt = new Intl.DateTimeFormat(pageLocale, { month: 'short', year: 'numeric' });
  var moneyFmt = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  function fill(template, args) {
    return template.replace(/\{(\d)\}/g, function (m, i) { return args[Number(i)]; });
  }
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) { e.className = cls; }
    if (text != null) { e.textContent = text; }
    return e;
  }
  function updatePeriodPreview(form) {
    var box = form.querySelector('[data-period-preview]');
    var input = form.querySelector('input[name="period"]');
    var feeBox = form.querySelector('[data-fee-field]');
    if (feeBox && input) {
      // The fee box belongs to installment payments: show it once a period is typed (or the entry is a payment).
      var feeInput = feeBox.querySelector('input');
      feeBox.hidden = !(input.readOnly || input.value.trim() || (feeInput && feeInput.value.trim()));
    }
    if (!box || !input) { return; }
    var text = input.value.trim();
    box.textContent = '';
    if (!text || input.disabled || input.readOnly) { box.hidden = true; return; }
    box.hidden = false;
    var m = /^(\d{1,3})\s*\/\s*(\d{1,3})$/.exec(text);
    var no = m ? Number(m[1]) : 0;
    var count = m ? Number(m[2]) : 0;
    if (!m || no < 1 || no > count || count > 120) {
      box.appendChild(el('div', 'pv-bad', box.getAttribute('data-t-bad')));
      return;
    }
    var amount = num(form.querySelector('input[name="amount"]'));
    var dateVal = (form.querySelector('input[name="date"]') || {}).value || '';
    var parts = dateVal.split('-');
    var base = parts.length === 3 ? new Date(Number(parts[0]), Number(parts[1]) - 1, 1) : new Date();
    base = new Date(base.getFullYear(), base.getMonth(), 1);
    function monthOf(k) { return new Date(base.getFullYear(), base.getMonth() + (k - no), 1); }
    function label(k) { return monthFmt.format(monthOf(k)); }

    // The amount typed is the full price: monthly payment = full price ÷ months (as the app saves it).
    var monthly = Math.round(amount / count * 100) / 100;
    var fee = num(form.querySelector('input[name="fee"]'));
    box.appendChild(el('div', 'pv-title', box.getAttribute('data-t-title')));
    box.appendChild(el('div', 'pv-line', fill(box.getAttribute('data-t-payments'),
      [count, '฿' + moneyFmt.format(monthly), '฿' + moneyFmt.format(amount)])));
    if (fee > 0) {
      box.appendChild(el('div', 'pv-line pv-this', fill(box.getAttribute('data-t-fee'),
        ['฿' + moneyFmt.format(monthly), '฿' + moneyFmt.format(fee), '฿' + moneyFmt.format(monthly + fee)])));
    }
    var ul = el('ul', 'pv-list');
    ul.appendChild(el('li', null, fill(box.getAttribute('data-t-first'), [count, label(1)])));
    if (no > 1) { ul.appendChild(el('li', 'pv-this', fill(box.getAttribute('data-t-this'), [no, count, label(no)]))); }
    else { ul.lastChild.className = 'pv-this'; }
    ul.appendChild(el('li', null, fill(box.getAttribute('data-t-last'), [count, label(count)])));
    box.appendChild(ul);
    if (no >= count) {
      box.appendChild(el('div', 'pv-line muted', box.getAttribute('data-t-none')));
    } else {
      var next = [];
      for (var k = no + 1; k <= count && next.length < 6; k++) {
        next.push(k + '/' + count + ' ' + new Intl.DateTimeFormat(pageLocale, { day: 'numeric', month: 'short' }).format(monthOf(k)));
      }
      if (count - no > 6) { next.push('…'); }
      box.appendChild(el('div', 'pv-line muted', fill(box.getAttribute('data-t-next'), [next.join(' · ')])));
    }
  }
  document.addEventListener('input', function (e) {
    var form = e.target.closest && e.target.closest('[data-tx-form]');
    if (form && /^(period|amount|date|fee)$/.test(e.target.name || '')) { updatePeriodPreview(form); }
  });
  document.addEventListener('change', function (e) {
    var form = e.target.closest && e.target.closest('[data-tx-form]');
    if (form) { updatePeriodPreview(form); }
  });
  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('[data-tx-form]').forEach(updatePeriodPreview);
  });

  // ---------- Trade form: any two of units / price / amount give the third ----------
  function updateTrade(form) {
    var out = form.querySelector('[data-trade-preview]');
    if (!out) { return; }
    var u = form.querySelector('[data-t="units"]'), p = form.querySelector('[data-t="price"]'),
        a = form.querySelector('[data-t="amount"]');
    var hasU = u.value.trim() !== '', hasP = p.value.trim() !== '', hasA = a.value.trim() !== '';
    var units = num(u), price = num(p), amount = num(a);
    if (hasU && hasP && !hasA) { amount = units * price; }
    else if (!hasU && hasP && hasA && price > 0) { units = amount / price; }
    else if (hasU && !hasP && hasA && units > 0) { price = amount / units; }
    else if (!(hasU && hasP && hasA)) { out.textContent = ''; return; }
    var ccy = out.getAttribute('data-ccy') === 'USD' ? '$' : '฿';
    out.textContent = fill(out.getAttribute('data-t'), [
      units.toLocaleString('en-US', { maximumFractionDigits: 8 }),
      ccy + price.toLocaleString('en-US', { maximumFractionDigits: 6 }),
      ccy + moneyFmt.format(amount)]);
  }
  document.addEventListener('input', function (e) {
    var form = e.target.closest && e.target.closest('[data-trade-form]');
    if (form) { updateTrade(form); }
  });
  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('[data-trade-form]').forEach(updateTrade);
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

  // Pay in installments: show the total of the ticked purchases and the monthly payment (preview only).
  var splitForm = document.querySelector('[data-split-form]');
  if (splitForm) {
    var fmt = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    var updateSplit = function () {
      var cents = 0;
      splitForm.querySelectorAll('input[name="ids"]:checked').forEach(function (c) {
        cents += Math.round(parseFloat(c.getAttribute('data-amount')) * 100);
      });
      var months = parseInt(splitForm.querySelector('[name="months"]').value, 10) || 3;
      splitForm.querySelector('[data-split-total]').textContent = '฿' + fmt.format(cents / 100);
      splitForm.querySelector('[data-split-monthly]').textContent = '฿' + fmt.format(Math.round(cents / months) / 100);
    };
    splitForm.addEventListener('change', updateSplit);
    updateSplit();
  }

  // "Select all" tick box: data-check-all="name" ticks every box with that name in the same form.
  document.addEventListener('change', function (e) {
    var all = e.target;
    if (!all.matches || !all.matches('input[data-check-all]') || !all.form) { return; }
    var name = all.getAttribute('data-check-all');
    all.form.querySelectorAll('input[type="checkbox"][name="' + name + '"]').forEach(function (c) {
      c.checked = all.checked;
    });
    all.form.dispatchEvent(new Event('change'));
  });

  // A select with data-autosubmit sends its form as soon as the choice changes (e.g. the report year).
  document.addEventListener('change', function (e) {
    var el = e.target;
    if (el.matches && el.matches('select[data-autosubmit]') && el.form) { el.form.submit(); }
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
