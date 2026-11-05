// ECharts helpers. Chart colours are read from the CSS variables so charts follow the light/dark theme.
window.MoneyCharts = (function () {
  var charts = [];

  function css(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }

  function palette() {
    var groups = {};
    ['FIXED', 'GAS', 'NECESSARY', 'SHOPPING', 'EAT', 'SAVING', 'INVESTMENT'].forEach(function (g) {
      groups[g] = css('--g-' + g.toLowerCase());
    });
    return {
      text: css('--text'), muted: css('--text-muted'), border: css('--border'), surface: css('--surface'),
      surface2: css('--surface-2'),
      primary: css('--primary'), income: css('--income'), expense: css('--expense'),
      investment: css('--investment'), warning: css('--warning'), over: css('--over'),
      /** Colour of a budget group (one fixed colour per group, defined in app.css). */
      group: function (key) { return groups[key] || css('--text-muted'); }
    };
  }

  /** Short weekday names, Sunday first (the order ECharts expects), in the page language. */
  function weekdays() {
    var f = new Intl.DateTimeFormat(locale, { weekday: 'short' });
    var out = [];
    for (var i = 0; i < 7; i++) { out.push(f.format(new Date(2026, 1, 1 + i))); }  // 1 Feb 2026 is a Sunday
    return out;
  }

  /** Shows a quiet "no data" message instead of an empty chart. */
  function empty(el, text) {
    if (!el) { return; }
    el.classList.add('chart-empty');
    el.textContent = text;
  }

  var lang = document.documentElement.lang || 'en';
  var locale = lang === 'th' ? 'th-TH' : 'en-US';
  var money = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  var moneyShort = new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 });

  function baht(v) { return '฿' + money.format(v); }

  function monthName(year, month) {
    return new Intl.DateTimeFormat(locale, { month: 'short' }).format(new Date(year, month - 1, 1));
  }

  /** Registers a chart: build(palette) returns the ECharts option; it is rebuilt on theme change. */
  function register(el, build) {
    if (!el || !window.echarts) { return; }
    var chart = echarts.init(el, null, { renderer: 'svg' });
    var entry = { chart: chart, build: build };
    charts.push(entry);
    chart.setOption(build(palette()));
    return chart;
  }

  document.addEventListener('themechange', function () {
    var p = palette();
    charts.forEach(function (c) { c.chart.setOption(c.build(p), true); });
  });

  var resizeTimer;
  window.addEventListener('resize', function () {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () { charts.forEach(function (c) { c.chart.resize(); }); }, 120);
  });

  return { register: register, baht: baht, short: moneyShort, monthName: monthName, weekdays: weekdays, empty: empty };
})();
