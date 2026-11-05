package com.moneymgmt.importer;

import com.moneymgmt.repo.ImportBatchRepository;
import jakarta.servlet.http.HttpSession;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Settings → Import: upload the sheet (.xlsx), check what will be imported, then confirm. The file is never stored. */
@Controller
@RequestMapping("/settings/import")
public class ImportController {

    private static final Logger log = LoggerFactory.getLogger(ImportController.class);
    static final String PLAN = "importPlan";
    static final String FILE = "importFileName";

    private final ImportBatchRepository batches;
    private final ImportService importService;
    private final int payday;

    public ImportController(ImportBatchRepository batches, ImportService importService,
                            @org.springframework.beans.factory.annotation.Value("${app.salary.payday:28}") int payday) {
        this.batches = batches;
        this.importService = importService;
        this.payday = payday;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("batches", batches.findAllByOrderByImportedAtDesc());
        return "import";
    }

    @PostMapping("/preview")
    public String preview(@RequestParam(value = "file", required = false) MultipartFile file, HttpSession session,
                          Model model, RedirectAttributes redirect) {
        if (file == null || file.isEmpty()) {
            redirect.addFlashAttribute("error", "import.error.empty");
            return "redirect:/settings/import";
        }
        ImportPlan plan;
        try (InputStream in = file.getInputStream()) {
            plan = SheetParser.parse(in, payday);
        } catch (Exception e) {
            // Log the type only: the message could contain cell content from the owner's sheet.
            log.warn("Sheet import could not be read: {}", e.getClass().getSimpleName());
            redirect.addFlashAttribute("error", "import.error.read");
            return "redirect:/settings/import";
        }
        String name = file.getOriginalFilename() == null ? "sheet.xlsx"
                : file.getOriginalFilename().replaceAll("[^\\w .()-]", "_");
        session.setAttribute(PLAN, plan);
        session.setAttribute(FILE, name);
        model.addAttribute("plan", plan);
        model.addAttribute("fileName", name);
        return "import-preview";
    }

    @PostMapping("/confirm")
    public String confirm(HttpSession session, RedirectAttributes redirect) {
        Object plan = session.getAttribute(PLAN);
        if (!(plan instanceof ImportPlan p)) {
            redirect.addFlashAttribute("error", "import.error.expired");
            return "redirect:/settings/import";
        }
        ImportService.Result result = importService.save(p, (String) session.getAttribute(FILE));
        session.removeAttribute(PLAN);
        session.removeAttribute(FILE);
        redirect.addFlashAttribute("result", result);
        return "redirect:/settings/import";
    }

    @PostMapping("/cancel")
    public String cancel(HttpSession session) {
        session.removeAttribute(PLAN);
        session.removeAttribute(FILE);
        return "redirect:/settings/import";
    }
}
