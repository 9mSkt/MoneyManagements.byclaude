package com.moneymgmt.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Values every page layout needs: menu, current path and idle-logout time. */
@ControllerAdvice
public class LayoutAdvice {

    private final long sessionTimeoutSeconds;

    public LayoutAdvice(@Value("${server.servlet.session.timeout:30m}") Duration sessionTimeout) {
        this.sessionTimeoutSeconds = sessionTimeout.toSeconds();
    }

    @ModelAttribute("nav")
    public List<NavItem> nav() {
        return NavItem.ALL;
    }

    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    @ModelAttribute("sessionTimeoutSeconds")
    public long sessionTimeoutSeconds() {
        return sessionTimeoutSeconds;
    }
}
