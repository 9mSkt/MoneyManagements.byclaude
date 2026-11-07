// Net worth over time: stacked bars for cash, investments and debts (below zero), with a net worth line.
(function () {
  var C = window.MoneyCharts;
  var host = document.getElementById('nw-chart');
  if (!host || !C) { return; }
  var el = document.getElementById('chart-networth');

  fetch(host.getAttribute('data-src'), { headers: { 'Accept': 'application/json' } })
    .then(function (r) { return r.json(); })
    .then(function (d) {
      var L = d.labels;
      var any = d.total.some(function (v, i) {
        return Number(v) !== 0 || Number(d.cash[i]) !== 0 || Number(d.invest[i]) !== 0 || Number(d.debts[i]) !== 0;
      });
      if (!any) { C.empty(el, L.noData); return; }
      var months = d.months.map(function (x, i) {
        var n = C.monthName(x.y, x.m);
        return (i === 0 || x.m === 1) ? n + ' ' + String(x.y).slice(2) : n;
      });
      C.register(el, function (p) {
        return {
          color: [p.income, p.investment, p.expense, p.primary],
          textStyle: { fontFamily: 'inherit', color: p.muted },
          grid: { left: 4, right: 8, top: 64, bottom: 4, containLabel: true },
          legend: { top: 0, left: 0, textStyle: { color: p.muted }, itemWidth: 12, itemHeight: 8 },
          tooltip: { trigger: 'axis', confine: true, backgroundColor: p.surface, borderColor: p.border,
                     textStyle: { color: p.text }, valueFormatter: function (v) { return C.baht(v); } },
          xAxis: { type: 'category', data: months, axisLine: { lineStyle: { color: p.border } },
                   axisTick: { show: false }, axisLabel: { color: p.muted, hideOverlap: true } },
          yAxis: { type: 'value', splitLine: { lineStyle: { color: p.border } },
                   axisLabel: { color: p.muted, formatter: function (v) { return C.short.format(v); } } },
          series: [
            { name: L.cash, type: 'bar', stack: 'nw', data: d.cash, barMaxWidth: 28 },
            { name: L.invest, type: 'bar', stack: 'nw', data: d.invest, barMaxWidth: 28 },
            { name: L.debts, type: 'bar', stack: 'nw', data: d.debts, barMaxWidth: 28 },
            { name: L.total, type: 'line', data: d.total, symbolSize: 6, lineStyle: { width: 2.5 }, z: 5 }
          ]
        };
      });
    });
})();
