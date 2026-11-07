package com.moneymgmt.web;

import com.moneymgmt.domain.Category;
import com.moneymgmt.domain.TxType;
import com.moneymgmt.repo.CategoryRepository;
import com.moneymgmt.security.TotpService;
import com.moneymgmt.service.SheetCopyService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Settings: appearance, categories, data (import, downloads, daily Google Sheet copy) and sign-in security. */
@Controller
@RequestMapping("/settings")
public class SettingsController {

    private final CategoryRepository categories;
    private final SheetCopyService sheetCopy;
    private final TotpService totp;
    public SettingsController(CategoryRepository categories, SheetCopyService sheetCopy, TotpService totp) {
        this.categories = categories;
        this.sheetCopy = sheetCopy;
        this.totp = totp;
    }

    @GetMapping
    public String settings(Model model) {
        model.addAttribute("copy", sheetCopy.status());
        model.addAttribute("twoStep", totp.isEnabled());
        return "settings";
    }

    @PostMapping("/google-copy")
    public String copyNow(RedirectAttributes flash) {
        boolean ok = sheetCopy.copyNow();
        flash.addFlashAttribute(ok ? "flash" : "flashError", ok ? "copy.done" : "copy.failed");
        return "redirect:/settings#copy";
    }

    // ---------------------------------------------------------------- categories

    @GetMapping("/categories")
    public String categoryList(Model model) {
        List<Category> all = categories.findAllByOrderBySortOrderAsc();
        model.addAttribute("byType", Map.of(
                TxType.INCOME, all.stream().filter(c -> c.getType() == TxType.INCOME).toList(),
                TxType.EXPENSE, all.stream().filter(c -> c.getType() == TxType.EXPENSE).toList(),
                TxType.TRANSFER, all.stream().filter(c -> c.getType() == TxType.TRANSFER).toList()));
        model.addAttribute("types", TxType.values());
        return "settings/categories";
    }

    @GetMapping("/categories/new")
    public String newCategory(@RequestParam(required = false) TxType type, Model model) {
        CategoryForm f = new CategoryForm();
        if (type != null) {
            f.setType(type);
            List<com.moneymgmt.domain.BudgetGroup> groups = CategoryForm.groupsFor(type);
            f.setBudgetGroup(groups.isEmpty() ? null : groups.get(0));
        }
        return categoryForm(f, Map.of(), model);
    }

    @GetMapping("/categories/{id}/edit")
    public String editCategory(@PathVariable Long id, Model model) {
        Category c = categories.findById(id).orElseThrow(TransactionController::notFound);
        return categoryForm(CategoryForm.of(c), Map.of(), model);
    }

    @PostMapping("/categories")
    public String saveCategory(@ModelAttribute("form") CategoryForm f, BindingResult binding, Model model,
                               RedirectAttributes flash) {
        Category existing = f.getId() == null ? null : categories.findById(f.getId()).orElseThrow(TransactionController::notFound);
        if (existing != null) {
            f.setType(existing.getType());   // the type never changes: entries of this category depend on it
        }
        Map<String, String> errors = FixedCostController.withBindingErrors(binding, f.validate());
        if (!errors.isEmpty()) {
            return categoryForm(f, errors, model);
        }
        Category c = existing;
        if (c == null) {
            String code = f.suggestedCode();
            String unique = code;
            for (int n = 2; categories.findByCode(unique).isPresent(); n++) {
                unique = code + "_" + n;
            }
            c = new Category(unique, f.getNameEn().trim(), f.getNameTh().trim(), f.getType(), f.getBudgetGroup());
        }
        c.setNameEn(f.getNameEn().trim());
        c.setNameTh(f.getNameTh().trim());
        c.setBudgetGroup(f.getType() == TxType.INCOME ? null : f.getBudgetGroup());
        c.setSortOrder(f.getSortOrder());
        c.setActive(f.isActive());
        c.setNeedsReview(false);
        categories.save(c);
        flash.addFlashAttribute("flash", "cat.saved");
        return "redirect:/settings/categories";
    }

    private String categoryForm(CategoryForm f, Map<String, String> errors, Model model) {
        model.addAttribute("form", f);
        model.addAttribute("errors", errors);
        model.addAttribute("types", TxType.values());
        model.addAttribute("groups", f.getType() == null ? List.of() : CategoryForm.groupsFor(f.getType()));
        return "settings/category-form";
    }
}
