package com.moneymgmt.web;

import com.moneymgmt.service.TaxCalculator;
import com.moneymgmt.service.TaxCalculator.Bracket;
import com.moneymgmt.service.TaxService;
import com.moneymgmt.service.TaxService.Plan;
import com.moneymgmt.service.TaxService.Settings;
import com.moneymgmt.util.AppTime;
import com.moneymgmt.util.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Yearly Thai tax planner: income and deductions, brackets, tax to pay, and RMF / ThaiESG what-ifs. */
@Controller
public class TaxController {

    static final int MIN_YEAR = 2000;
    static final int MAX_YEAR = 2100;
    static final BigDecimal MAX_AMOUNT = Money.of("100000000");

    private final TaxService service;
    private final MessageSource messages;

    public TaxController(TaxService service, MessageSource messages) {
        this.service = service;
        this.messages = messages;
    }

    @GetMapping("/tax")
    public String index(@RequestParam(required = false) Integer year, Model model) {
        int y = year(year);
        Plan p = service.plan(y);
        model.addAttribute("p", p);
        model.addAttribute("r", p.result());
        model.addAttribute("year", y);
        model.addAttribute("thisYear", AppTime.today().getYear());
        return "tax/index";
    }

    /** What-if card: more RMF / ThaiESG, or the purchase needed to save a target amount. */
    @GetMapping("/tax/whatif")
    public String whatIf(@RequestParam(required = false) Integer year, @RequestParam(required = false) String rmf,
                         @RequestParam(required = false) String esg, @RequestParam(required = false) String save,
                         Model model) {
        Plan p = service.plan(year(year));
        BigDecimal extraRmf = amount(rmf);
        BigDecimal extraEsg = amount(esg);
        model.addAttribute("p", p);
        model.addAttribute("r", p.result());
        if (extraRmf.signum() > 0 || extraEsg.signum() > 0) {
            var after = service.withExtra(p, extraRmf, extraEsg);
            model.addAttribute("after", after);
            model.addAttribute("saving", p.result().tax().subtract(after.tax()));
            model.addAttribute("overRmf", extraRmf.subtract(p.result().rmfRoom()).max(BigDecimal.ZERO));
            model.addAttribute("overEsg", extraEsg.subtract(p.result().esgRoom()).max(BigDecimal.ZERO));
        }
        BigDecimal target = amount(save);
        if (target.signum() > 0) {
            model.addAttribute("target", target);
            BigDecimal need = TaxCalculator.neededFor(target, p.inputs(), p.brackets());
            model.addAttribute("need", need == null ? null
                    : TaxService.suggestion(need, p.inputs(), p.result(), p.brackets()));
        }
        return "tax/index :: whatif";
    }

    /** Bracket chart: how much of the net income falls in each bracket, up to the one after the current. */
    @GetMapping("/api/tax/chart")
    @ResponseBody
    public Map<String, Object> chart(@RequestParam(required = false) Integer year, Locale locale) {
        Plan p = service.plan(year(year));
        var lines = p.result().brackets();
        int current = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).current()) {
                current = i;
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i <= Math.min(current + 1, lines.size() - 1); i++) {
            var l = lines.get(i);
            BigDecimal size = l.size();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rate", l.bracket().rate().stripTrailingZeros().toPlainString() + "%");
            row.put("range", range(l.bracket()));
            row.put("portion", l.portion());
            row.put("room", size == null ? BigDecimal.ZERO : size.subtract(l.portion()).max(BigDecimal.ZERO));
            row.put("tax", l.tax());
            row.put("current", l.current());
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("labels", Map.of("portion", msg("tax.chart.portion", locale), "room", msg("tax.chart.room", locale),
                "tax", msg("tax.chart.tax", locale), "noData", msg("tax.chart.noData", locale)));
        data.put("net", p.result().net());
        data.put("rows", rows);
        return data;
    }

    // ---------------------------------------------------------------- the owner's numbers

    @GetMapping("/tax/{year}/edit")
    public String edit(@PathVariable int year, Model model) {
        int y = year(year);
        Settings s = service.settings(y);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("salaryEstimate", plain(s.salaryEstimate()));
        values.put("bonusExpected", plain(s.bonusExpected()));
        values.put("otherExpected", plain(s.otherExpected()));
        s.typed().forEach((code, v) -> values.put("d_" + code, plain(v)));
        return editForm(y, values, Map.of(), model);
    }

    @PostMapping("/tax/{year}")
    public String save(@PathVariable int year, @RequestParam Map<String, String> params, Model model,
                       RedirectAttributes flash) {
        int y = year(year);
        Map<String, String> errors = new LinkedHashMap<>();
        BigDecimal salary = field(params, "salaryEstimate", errors);
        BigDecimal bonus = field(params, "bonusExpected", errors);
        BigDecimal other = field(params, "otherExpected", errors);
        Map<String, BigDecimal> typed = new LinkedHashMap<>();
        for (String code : TaxCalculator.TYPED) {
            typed.put(code, field(params, "d_" + code, errors));
        }
        if (!errors.isEmpty()) {
            return editForm(y, params, errors, model);
        }
        service.save(y, new Settings(salary, bonus, other, typed));
        flash.addFlashAttribute("flash", "tax.saved");
        return "redirect:/tax?year=" + y;
    }

    private String editForm(int year, Map<String, String> values, Map<String, String> errors, Model model) {
        model.addAttribute("year", year);
        model.addAttribute("values", values);
        model.addAttribute("errors", errors);
        model.addAttribute("typed", TaxCalculator.TYPED);
        model.addAttribute("p", service.plan(year));
        return "tax/edit";
    }

    // ---------------------------------------------------------------- brackets

    @GetMapping("/tax/brackets")
    public String brackets(@RequestParam(required = false) Integer year, Model model) {
        List<Bracket> rows = service.brackets();
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            values.put("to_" + i, rows.get(i).to() == null ? "" : plain(rows.get(i).to()));
            values.put("rate_" + i, rows.get(i).rate().stripTrailingZeros().toPlainString());
        }
        return bracketForm(rows.size(), values, Map.of(), year(year), model);
    }

    /**
     * Fields to_0..to_n and rate_0..rate_n. Each bracket starts where the one before ends; the last one has no
     * upper limit. Rows past "count" are ignored, a row with an empty rate is dropped.
     */
    @PostMapping("/tax/brackets")
    public String saveBrackets(@RequestParam Map<String, String> params, @RequestParam(required = false) Integer year,
                               Model model, RedirectAttributes flash) {
        int count = 0;
        while (params.containsKey("rate_" + count) && count < 20) {
            count++;
        }
        Map<String, String> errors = new LinkedHashMap<>();
        List<Integer> used = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (!params.getOrDefault("rate_" + i, "").isBlank()) {
                used.add(i);
            }
        }
        List<Bracket> rows = new ArrayList<>();
        BigDecimal from = Money.zero();
        for (int k = 0; k < used.size(); k++) {
            int i = used.get(k);
            boolean last = k == used.size() - 1;
            BigDecimal rate = Money.parse(params.get("rate_" + i));
            if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0) {
                errors.put("rate_" + i, "tax.error.rate");
            }
            BigDecimal to = null;
            if (!last) {
                to = Money.parse(params.get("to_" + i));
                if (to == null || to.compareTo(from) <= 0) {
                    errors.put("to_" + i, "tax.error.to");
                }
            }
            if (errors.isEmpty()) {
                rows.add(new Bracket(from, to, rate));
                from = to;
            }
        }
        if (used.isEmpty()) {
            errors.put("rate_0", "tax.error.rate");
        }
        if (!errors.isEmpty()) {
            return bracketForm(count, params, errors, year(year), model);
        }
        service.saveBrackets(rows);
        flash.addFlashAttribute("flash", "tax.bracketsSaved");
        return "redirect:/tax?year=" + year(year);
    }

    private String bracketForm(int count, Map<String, String> values, Map<String, String> errors, int year,
                               Model model) {
        model.addAttribute("count", count);
        model.addAttribute("values", values);
        model.addAttribute("errors", errors);
        model.addAttribute("year", year);
        return "tax/brackets";
    }

    // ---------------------------------------------------------------- helpers

    static int year(Integer year) {
        int y = year == null ? AppTime.today().getYear() : year;
        return Math.max(MIN_YEAR, Math.min(MAX_YEAR, y));
    }

    /** A typed amount for a what-if: empty, negative or not a number counts as 0. */
    private static BigDecimal amount(String text) {
        BigDecimal v = Money.parse(text);
        return v == null || v.signum() < 0 ? Money.zero() : v.min(MAX_AMOUNT);
    }

    /** A form amount: empty = 0, otherwise 0 or more. */
    private static BigDecimal field(Map<String, String> params, String name, Map<String, String> errors) {
        String text = params.getOrDefault(name, "");
        if (text.isBlank()) {
            return Money.zero();
        }
        BigDecimal v = Money.parse(text);
        if (v == null || v.signum() < 0 || v.compareTo(MAX_AMOUNT) > 0) {
            errors.put(name, "tax.error.amount");
            return Money.zero();
        }
        return v;
    }

    private static String plain(BigDecimal v) {
        return v == null || v.signum() == 0 ? "" : v.stripTrailingZeros().toPlainString();
    }

    private static String range(Bracket b) {
        String from = Money.format(b.from()).replace(".00", "");
        return b.to() == null ? from + "+" : from + " – " + Money.format(b.to()).replace(".00", "");
    }

    private String msg(String key, Locale locale) {
        return messages.getMessage(key, null, key, locale);
    }
}
