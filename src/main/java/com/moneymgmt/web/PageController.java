package com.moneymgmt.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Login, settings, and the "coming in phase N" pages for modules not built yet. */
@Controller
public class PageController {

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/settings")
    public String settings() {
        return "settings";
    }

    @GetMapping({"/transactions", "/fixed-costs", "/budget", "/investments", "/tax", "/goals", "/reports"})
    public String placeholder(Model model, jakarta.servlet.http.HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        NavItem item = NavItem.ALL.stream().filter(n -> n.path().equals(path)).findFirst().orElseThrow();
        model.addAttribute("item", item);
        return "placeholder";
    }
}
