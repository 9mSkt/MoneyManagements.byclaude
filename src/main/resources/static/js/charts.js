// ECharts helpers. Chart colours are read from the CSS variables so charts follow the light/dark theme.
window.MoneyCharts = (function () {
  var charts = [];

  function css(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }

  function palette() {
    return {
      text: css('--text'), muted: css('--text-muted'), border: css('--border'), surface: css('--surface'),
      primary: css('--primary'), income: css('--income'), expense: css('--expense'),
      investment: css('--investment'), warning: css('--warning'), over: css('--over')
    };
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

  return { register: register, baht: baht, short: moneyShort, monthName: monthName };
})();
