// Dashboard charts (Phase 1: sample data from /api/dashboard/sample).
(function () {
  var trendEl = document.getElementById('chart-trend');
  var donutEl = document.getElementById('chart-groups');
  if (!trendEl && !donutEl) { return; }
  var C = window.MoneyCharts;

  fetch(trendEl.getAttribute('data-src'), { headers: { 'Accept': 'application/json' } })
    .then(function (r) { return r.json(); })
    .then(function (d) {
      var months = d.months.map(function (m) { return C.monthName(d.year, m); });
      var remaining = d.income.map(function (inc, i) { return inc === 0 && d.outcome[i] === 0 ? null : inc - d.outcome[i]; });

      C.register(trendEl, function (p) {
        return {
          color: [p.income, p.expense, p.primary],
          textStyle: { fontFamily: 'inherit', color: p.muted },
          grid: { left: 8, right: 8, top: 36, bottom: 8, containLabel: true },
          legend: { top: 0, left: 0, textStyle: { color: p.muted }, itemWidth: 12, itemHeight: 8 },
          tooltip: {
            trigger: 'axis', backgroundColor: p.surface, borderColor: p.border, textStyle: { color: p.text },
            valueFormatter: function (v) { return v == null ? '-' : C.baht(v); }
          },
          xAxis: { type: 'category', data: months, axisLine: { lineStyle: { color: p.border } }, axisTick: { show: false },
                   axisLabel: { color: p.muted } },
          yAxis: { type: 'value', splitLine: { lineStyle: { color: p.border } },
                   axisLabel: { color: p.muted, formatter: function (v) { return C.short.format(v); } } },
          series: [
            { name: d.labels.income, type: 'bar', data: d.income, barMaxWidth: 14, itemStyle: { borderRadius: [4, 4, 0, 0] } },
            { name: d.labels.outcome, type: 'bar', data: d.outcome, barMaxWidth: 14, itemStyle: { borderRadius: [4, 4, 0, 0] } },
            { name: d.labels.remaining, type: 'line', data: remaining, smooth: true, symbolSize: 6, lineStyle: { width: 2.5 } }
          ]
        };
      });

      C.register(donutEl, function (p) {
        return {
          color: [p.expense, p.primary, p.investment, p.warning, p.income],
          textStyle: { fontFamily: 'inherit', color: p.muted },
          tooltip: { trigger: 'item', backgroundColor: p.surface, borderColor: p.border, textStyle: { color: p.text },
                     valueFormatter: function (v) { return C.baht(v); } },
          legend: { bottom: 0, left: 'center', textStyle: { color: p.muted }, itemWidth: 10, itemHeight: 10 },
          series: [{
            type: 'pie', radius: ['52%', '76%'], center: ['50%', '42%'], avoidLabelOverlap: true,
            itemStyle: { borderColor: p.surface, borderWidth: 2, borderRadius: 4 },
            label: { show: false }, data: d.byGroup
          }]
        };
      });
    });
})();
