// Tax bracket chart: how much of the net income falls in each bracket (filled) and the room left in it.
(function () {
  var C = window.MoneyCharts;
  var host = document.getElementById('tax-chart');
  if (!host || !C) { return; }
  var el = document.getElementById('chart-tax');

  fetch(host.getAttribute('data-src'), { headers: { 'Accept': 'application/json' } })
    .then(function (r) { return r.json(); })
    .then(function (d) {
      var L = d.labels;
      if (!d.net || Number(d.net) <= 0) { C.empty(el, L.noData); return; }
      C.register(el, function (p) {
        return {
          textStyle: { fontFamily: 'inherit', color: p.muted },
          grid: { left: 4, right: 8, top: 56, bottom: 4, containLabel: true },
          legend: { top: 0, left: 0, textStyle: { color: p.muted }, itemWidth: 12, itemHeight: 8,
                    data: [L.portion, L.room] },
          tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, confine: true,
                     backgroundColor: p.surface, borderColor: p.border, textStyle: { color: p.text },
                     formatter: function (items) {
                       var row = d.rows[items[0].dataIndex];
                       return '<b>' + row.rate + '</b> · ' + row.range + '<br>' +
                         L.portion + ': ' + C.baht(row.portion) + '<br>' + L.tax + ': ' + C.baht(row.tax);
                     } },
          xAxis: { type: 'category', data: d.rows.map(function (r) { return r.rate; }),
                   axisLine: { lineStyle: { color: p.border } }, axisTick: { show: false },
                   axisLabel: { color: p.muted, fontWeight: 600 } },
          yAxis: { type: 'value', splitLine: { lineStyle: { color: p.border } },
                   axisLabel: { color: p.muted, formatter: function (v) { return C.short.format(v); } } },
          series: [
            { name: L.portion, type: 'bar', stack: 'b', barMaxWidth: 56, color: p.warning,
              data: d.rows.map(function (r) {
                return { value: r.portion, itemStyle: { color: p.warning, opacity: r.current ? 1 : 0.55,
                                                        borderRadius: Number(r.room) > 0 ? 0 : [6, 6, 0, 0] } };
              }) },
            { name: L.room, type: 'bar', stack: 'b', barMaxWidth: 56, color: p.surface2,
              itemStyle: { color: p.surface2, borderColor: p.border, borderRadius: [6, 6, 0, 0] },
              label: { show: true, position: 'top', color: p.text, fontSize: 11,
                       formatter: function (x) { var t = Number(d.rows[x.dataIndex].tax); return t > 0 ? C.baht(t) : ''; } },
              data: d.rows.map(function (r) { return r.room; }) }
          ]
        };
      });
    });
})();
