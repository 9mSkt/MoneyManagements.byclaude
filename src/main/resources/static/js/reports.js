// Yearly report chart: income and outcome bars per month, saved/invested bars, and a remaining line.
(function () {
  var C = window.MoneyCharts;
  var host = document.getElementById('rep-chart');
  if (!host || !C) { return; }
  var el = document.getElementById('chart-year');

  fetch(host.getAttribute('data-src'), { headers: { 'Accept': 'application/json' } })
    .then(function (r) { return r.json(); })
    .then(function (d) {
      var L = d.labels;
      var any = d.income.concat(d.outcome, d.saved).some(function (v) { return Number(v) !== 0; });
      if (!any) { host.style.display = 'none'; return; }
      var months = d.months.map(function (m) { return C.monthName(d.year, m); });
      C.register(el, function (p) {
        return {
          color: [p.income, p.expense, p.investment, p.primary],
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
            { name: L.income, type: 'bar', data: d.income, barMaxWidth: 16, itemStyle: { borderRadius: [4, 4, 0, 0] } },
            { name: L.outcome, type: 'bar', data: d.outcome, barMaxWidth: 16, itemStyle: { borderRadius: [4, 4, 0, 0] } },
            { name: L.saved, type: 'bar', data: d.saved, barMaxWidth: 16, itemStyle: { borderRadius: [4, 4, 0, 0] } },
            { name: L.remaining, type: 'line', data: d.remaining, symbolSize: 6, lineStyle: { width: 2.5 }, z: 5 }
          ]
        };
      });
    });
})();
