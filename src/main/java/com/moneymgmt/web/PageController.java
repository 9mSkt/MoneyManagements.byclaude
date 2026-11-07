package com.moneymgmt.web;

import com.moneymgmt.security.TotpService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** The login page (with the 6-digit code field when two-step sign-in is on). */
@Controller
public class PageController {

    private final TotpService totp;

    public PageController(TotpService totp) {
        this.totp = totp;
    }

    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("twoStep", totp.isEnabled());
        return "login";
    }
}
