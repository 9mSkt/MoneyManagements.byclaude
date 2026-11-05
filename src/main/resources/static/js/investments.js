// Investment charts: value over time (invested vs value), allocation donut, and the DCA projection.
(function () {
  var C = window.MoneyCharts;
  var host = document.getElementById('inv-charts');
  if (!host || !C) { return; }

  function tip(p) {
    return { backgroundColor: p.surface, borderColor: p.border, textStyle: { color: p.text }, confine: true };
  }
  function series(p) {   // allocation colours: meaning colours first, then group colours
    return [p.investment, p.warning, p.primary, p.group('SHOPPING'), p.group('NECESSARY'), p.group('EAT'),
            p.group('GAS'), p.income, p.group('FIXED')];
  }

  fetch(host.getAttribute('data-charts'), { headers: { 'Accept': 'application/json' } })
    .then(function (r) { return r.json(); })
    .then(function (d) {
      var L = d.labels;
      var hist = d.history;
      var histEl = document.getElementById('chart-inv-history');
      if (!hist.months.length) {
        C.empty(histEl, L.noData);
      } else {
        var months = hist.months.map(function (x, i) {
          var n = C.monthName(x.y, x.m);
          return (i === 0 || x.m === 1) ? n + ' ' + String(x.y).slice(2) : n;
        });
        C.register(histEl, function (p) {
          return {
            color: [p.muted, p.investment],
            textStyle: { fontFamily: 'inherit', color: p.muted },
            grid: { left: 4, right: 8, top: 36, bottom: 4, containLabel: true },
            legend: { top: 0, left: 0, textStyle: { color: p.muted }, itemWidth: 12, itemHeight: 8 },
            tooltip: Object.assign(tip(p), { trigger: 'axis', valueFormatter: function (v) { return C.baht(v); } }),
            xAxis: { type: 'category', data: months, boundaryGap: false, axisLine: { lineStyle: { color: p.border } },
                     axisTick: { show: false }, axisLabel: { color: p.muted, hideOverlap: true } },
            yAxis: { type: 'value', splitLine: { lineStyle: { color: p.border } },
                     axisLabel: { color: p.muted, formatter: function (v) { return C.short.format(v); } } },
            series: [
              { name: L.cost, type: 'line', step: 'end', data: hist.cost, symbol: 'none', lineStyle: { width: 2, type: 'dashed' } },
              { name: L.value, type: 'line', data: hist.value, smooth: true, symbolSize: 5, lineStyle: { width: 2.5 },
                areaStyle: { opacity: 0.12 } }
            ]
          };
        });
      }

      var allocEl = document.getElementById('chart-inv-alloc');
      if (!d.byAsset.length) {
        C.empty(allocEl, L.noData);
      } else {
        C.register(allocEl, function (p) {
          return {
            color: series(p),
            textStyle: { fontFamily: 'inherit', color: p.muted },
            tooltip: Object.assign(tip(p), { trigger: 'item', formatter: function (x) {
              return x.marker + x.name + '<br><b>' + C.baht(x.value) + '</b> · ' + x.percent + '%';
            } }),
            legend: { bottom: 0, left: 'center', textStyle: { color: p.muted }, itemWidth: 10, itemHeight: 10 },
            series: [{
              type: 'pie', radius: ['50%', '74%'], center: ['50%', '44%'],
              itemStyle: { borderColor: p.surface, borderWidth: 2, borderRadius: 4 },
              label: { show: true, position: 'inside', color: p.surface, fontSize: 11, fontWeight: 600,
                       formatter: function (x) { return x.percent >= 8 ? Math.round(x.percent) + '%' : ''; } },
              data: d.byAsset.map(function (a) { return { name: a.name, value: a.value }; })
            }]
          };
        });
      }

      dca(L);
    });

  // ---- DCA projection: the numbers come from the server (exact decimals); redrawn as the inputs change ----
  function dca(L) {
    var card = document.getElementById('dca');
    var form = card && card.querySelector('[data-dca-form]');
    var out = card && card.querySelector('[data-dca-result]');
    var el = document.getElementById('chart-dca');
    if (!form || !el) { return; }
    var chart = null;
    var data = null;
    var timer;

    function draw() {
      if (!data) { return; }
      out.textContent = out.getAttribute('data-t')
        .replace('{0}', data.years)
        .replace('{1}', C.baht(data.final.value))
        .replace('{2}', C.baht(data.final.contributed))
        .replace('{3}', C.baht(data.final.growth));
      var build = function (p) {
        var years = data.rows.map(function (r) { return L.year + ' ' + r.year; });
        return {
          color: [p.primary, p.investment],
          textStyle: { fontFamily: 'inherit', color: p.muted },
          grid: { left: 4, right: 8, top: 36, bottom: 4, containLabel: true },
          legend: { top: 0, left: 0, textStyle: { color: p.muted }, itemWidth: 12, itemHeight: 8 },
          tooltip: Object.assign(tip(p), { trigger: 'axis', valueFormatter: function (v) { return C.baht(v); } }),
          xAxis: { type: 'category', data: years, boundaryGap: false, axisLine: { lineStyle: { color: p.border } },
                   axisTick: { show: false }, axisLabel: { color: p.muted, hideOverlap: true } },
          yAxis: { type: 'value', splitLine: { lineStyle: { color: p.border } },
                   axisLabel: { color: p.muted, formatter: function (v) { return C.short.format(v); } } },
          series: [
            { name: L.contributed, type: 'line', data: data.rows.map(function (r) { return r.contributed; }),
              symbol: 'none', areaStyle: { opacity: 0.35 }, lineStyle: { width: 2 } },
            { name: L.value, type: 'line', data: data.rows.map(function (r) { return r.value; }),
              symbol: 'none', areaStyle: { opacity: 0.18 }, lineStyle: { width: 2.5 } }
          ]
        };
      };
      if (!chart) { chart = C.register(el, build); } else { chart.setOption(build(C.palette()), true); }
    }

    function load() {
      var q = new URLSearchParams(new FormData(form)).toString();
      fetch(card.getAttribute('data-src') + '?' + q, { headers: { 'Accept': 'application/json' } })
        .then(function (r) { return r.json(); })
        .then(function (d) { data = d; draw(); });
    }
    form.addEventListener('input', function () { clearTimeout(timer); timer = setTimeout(load, 350); });
    load();
  }
})();
