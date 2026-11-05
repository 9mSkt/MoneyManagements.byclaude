// Dashboard charts: 12-month trend, spending by group and category, money flow (Sankey), daily spending calendar.
(function () {
  var host = document.getElementById('dash-charts');
  if (!host) { return; }
  var C = window.MoneyCharts;

  function tooltipBase(p) {
    return { backgroundColor: p.surface, borderColor: p.border, textStyle: { color: p.text },
             confine: true };
  }

  function nodeColour(p, key) {
    if (key === 'INCOME' || key === 'SOURCE') { return p.income; }
    if (key === 'LEFT') { return p.primary; }
    if (key === 'SHORTFALL') { return p.over; }
    return p.group(key);
  }

  fetch(host.getAttribute('data-charts'), { headers: { 'Accept': 'application/json' } })
    .then(function (r) { return r.json(); })
    .then(function (d) {
      var L = d.labels;

      // ---- 12 months: income and outcome bars, remaining line ----
      var t = d.trend;
      var months = t.months.map(function (x, i) {
        var name = C.monthName(x.y, x.m);
        return (i === 0 || x.m === 1) ? name + ' ' + String(x.y).slice(2) : name;
      });
      C.register(document.getElementById('chart-trend'), function (p) {
        return {
          color: [p.income, p.expense, p.investment, p.primary],
          textStyle: { fontFamily: 'inherit', color: p.muted },
          grid: { left: 4, right: 8, top: 40, bottom: 4, containLabel: true },
          legend: { top: 0, left: 0, textStyle: { color: p.muted }, itemWidth: 12, itemHeight: 8 },
          tooltip: Object.assign(tooltipBase(p), { trigger: 'axis',
            valueFormatter: function (v) { return v == null ? '-' : C.baht(v); } }),
          xAxis: { type: 'category', data: months, axisLine: { lineStyle: { color: p.border } },
                   axisTick: { show: false }, axisLabel: { color: p.muted, hideOverlap: true } },
          yAxis: { type: 'value', splitLine: { lineStyle: { color: p.border } },
                   axisLabel: { color: p.muted, formatter: function (v) { return C.short.format(v); } } },
          series: [
            { name: L.income, type: 'bar', data: t.income, barMaxWidth: 12, barGap: '20%',
              itemStyle: { borderRadius: [3, 3, 0, 0] } },
            { name: L.outcome, type: 'bar', data: t.outcome, barMaxWidth: 12,
              itemStyle: { borderRadius: [3, 3, 0, 0] } },
            { name: L.transfer, type: 'bar', data: t.transfer, barMaxWidth: 12,
              itemStyle: { borderRadius: [3, 3, 0, 0] } },
            { name: L.remaining, type: 'line', data: t.remaining, smooth: true, symbolSize: 6,
              connectNulls: false, lineStyle: { width: 2.5 } }
          ]
        };
      });

      // ---- Spending: groups inside, categories outside, same colour per group ----
      var donutEl = document.getElementById('chart-groups');
      if (!d.groups.length) {
        C.empty(donutEl, L.noData);
      } else {
        C.register(donutEl, function (p) {
          var inner = d.groups.map(function (g) { return { name: g.name, value: g.value, itemStyle: { color: p.group(g.key) } }; });
          var outer = d.categories.map(function (c) {
            return { name: c.name, value: c.value, itemStyle: { color: p.group(c.key), opacity: 0.72 } };
          });
          return {
            textStyle: { fontFamily: 'inherit', color: p.muted },
            tooltip: Object.assign(tooltipBase(p), { trigger: 'item',
              formatter: function (x) { return x.marker + x.name + '<br><b>' + C.baht(x.value) + '</b> · ' + x.percent + '%'; } }),
            legend: { bottom: 0, left: 'center', data: d.groups.map(function (g) { return g.name; }),
                      textStyle: { color: p.muted }, itemWidth: 10, itemHeight: 10 },
            series: [
              { type: 'pie', radius: ['30%', '52%'], center: ['50%', '44%'], label: { show: false },
                itemStyle: { borderColor: p.surface, borderWidth: 2 }, data: inner },
              { type: 'pie', radius: ['56%', '74%'], center: ['50%', '44%'], label: { show: false },
                itemStyle: { borderColor: p.surface, borderWidth: 2, borderRadius: 3 }, data: outer }
            ]
          };
        });
      }

      // ---- Money flow ----
      var flowEl = document.getElementById('chart-flow');
      if (!d.sankey.nodes.length) {
        C.empty(flowEl, L.noData);
      } else {
        var narrow = flowEl.clientWidth < 520;
        C.register(flowEl, function (p) {
          return {
            textStyle: { fontFamily: 'inherit', color: p.text },
            tooltip: Object.assign(tooltipBase(p), { trigger: 'item',
              formatter: function (x) {
                if (x.dataType === 'edge') { return x.data.source + ' → ' + x.data.target + '<br><b>' + C.baht(x.value) + '</b>'; }
                return x.name + '<br><b>' + C.baht(x.value) + '</b>';
              } }),
            series: [{
              type: 'sankey', left: 4, right: narrow ? 112 : 150, top: 8, bottom: 8,
              nodeWidth: 12, nodeGap: 14, draggable: false, layoutIterations: 32,
              emphasis: { focus: 'adjacency' },
              // One line per label ("Shopping 2.9K") so labels of small groups never overlap.
              label: { color: p.text, fontSize: 11, lineHeight: 13,
                       formatter: function (x) { return x.name.trim() + ' ' + C.short.format(x.value); } },
              lineStyle: { color: 'gradient', opacity: 0.28, curveness: 0.5 },
              data: d.sankey.nodes.map(function (n) {
                return { name: n.name, itemStyle: { color: nodeColour(p, n.key), borderWidth: 0 } };
              }),
              links: d.sankey.links
            }]
          };
        });
      }

      // ---- Daily spending calendar ----
      var dayEl = document.getElementById('chart-daily');
      var days = d.daily.days;
      var ym = d.daily.range.split('-');
      var offset = (new Date(Number(ym[0]), Number(ym[1]) - 1, 1).getDay() + 6) % 7;  // Monday first
      var weeks = Math.ceil((offset + days.length) / 7);
      function cellSize() {
        var width = dayEl.clientWidth || 340;
        var w = Math.max(30, Math.min(52, Math.floor((width - 24) / 7)));
        return [w, Math.round(w * 0.82)];
      }
      dayEl.style.height = (40 + weeks * cellSize()[1]) + 'px';
      C.register(dayEl, function (p) {
        var cell = cellSize();
        return {
          textStyle: { fontFamily: 'inherit', color: p.muted },
          tooltip: Object.assign(tooltipBase(p), { formatter: function (x) {
            return x.value[0] + '<br><b>' + C.baht(x.value[1]) + '</b>';
          } }),
          visualMap: { show: false, min: 0, max: Math.max(1, d.daily.max),
                       inRange: { color: [p.surface2, p.expense] } },
          calendar: {
            range: d.daily.range, orient: 'vertical', left: 'center', top: 28, cellSize: cell,
            splitLine: { show: false }, itemStyle: { color: p.surface, borderColor: p.surface, borderWidth: 3 },
            yearLabel: { show: false }, monthLabel: { show: false },
            dayLabel: { firstDay: 1, nameMap: C.weekdays(), color: p.muted, fontSize: 11, margin: 8 }
          },
          series: [{
            type: 'heatmap', coordinateSystem: 'calendar', data: days,
            itemStyle: { borderRadius: 6, borderColor: p.surface, borderWidth: 3 },
            label: { show: true, fontSize: 10, color: p.text,
                     formatter: function (x) { return Number(x.value[0].slice(8)); } }
          }]
        };
      });
    });
})();
