package com.moneymgmt.web;

import com.moneymgmt.domain.Category;
import com.moneymgmt.repo.CategoryRepository;
import com.moneymgmt.util.AppTime;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Values every page layout needs: menu, current path, idle-logout time and the quick-add form. */
@ControllerAdvice
public class LayoutAdvice {

    private final long sessionTimeoutSeconds;
    private final CategoryRepository categories;

    public LayoutAdvice(@Value("${server.servlet.session.timeout:30m}") Duration sessionTimeout,
                        CategoryRepository categories) {
        this.sessionTimeoutSeconds = sessionTimeout.toSeconds();
        this.categories = categories;
    }

    @ModelAttribute("nav")
    public List<NavItem> nav() {
        return NavItem.ALL;
    }

    @ModelAttribute("currentPath")
    public String currentPath(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length());
    }

    /**
     * Link target for the EN/TH switch: this page with its query (e.g. ?month=2026-10) kept, ending ready for
     * "lang=xx".
     */
    @ModelAttribute("langBase")
    public String langBase(HttpServletRequest request) {
        StringBuilder sb = new StringBuilder(request.getRequestURI()).append('?');
        String query = request.getQueryString();
        if (query != null) {
            for (String part : query.split("&")) {
                if (!part.isEmpty() && !part.startsWith("lang=") && !"lang".equals(part)) {
                    sb.append(part).append('&');
                }
            }
        }
        return sb.toString();
    }

    @ModelAttribute("sessionTimeoutSeconds")
    public long sessionTimeoutSeconds() {
        return sessionTimeoutSeconds;
    }

    @ModelAttribute("today")
    public LocalDate today() {
        return AppTime.today();
    }

    /** Empty quick-add form: expense, dated today. */
    @ModelAttribute("quickForm")
    public TxForm quickForm() {
        TxForm f = new TxForm();
        f.setDate(AppTime.today());
        return f;
    }

    /** Categories for the quick-add drawer (and forms). Only active ones. */
    @ModelAttribute("catOptions")
    public CategoryOptions catOptions() {
        List<Category> active = categories.findAllByOrderBySortOrderAsc().stream().filter(Category::isActive).toList();
        return new CategoryOptions(active);
    }
}
