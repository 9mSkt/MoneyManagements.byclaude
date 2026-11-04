package com.moneymgmt.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.moneymgmt.AbstractWebTest;
import com.moneymgmt.repo.CategoryRepository;
import com.moneymgmt.repo.TaxBracketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

@WithMockUser(username = "tester")
class PagesTest extends AbstractWebTest {

    @Autowired
    CategoryRepository categories;

    @Autowired
    TaxBracketRepository brackets;

    @Test
    void migrationsSeedCategoriesInBothLanguagesAndTaxBrackets() {
        assertThat(categories.findAll()).isNotEmpty()
                .allSatisfy(c -> assertThat(c.getNameTh()).isNotBlank());
        assertThat(categories.findByCode("GASOLINE")).get()
                .satisfies(c -> assertThat(c.getNameTh()).isEqualTo("ค่าน้ำมัน"));
        assertThat(brackets.findAll()).hasSize(8);
    }

    @Test
    void dashboardRendersInEnglishAndThai() throws Exception {
        mvc.perform(get("/").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Saving rate")));
        mvc.perform(get("/").param("lang", "th"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("อัตราการออม")))
                .andExpect(cookie().value("lang", "th"));
    }

    @Test
    void unsupportedLanguageFallsBackToEnglish() throws Exception {
        mvc.perform(get("/").param("lang", "fr"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Saving rate")));
    }

    @Test
    void sampleChartDataIsJson() throws Exception {
        mvc.perform(get("/api/dashboard/sample"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.months.length()").value(12))
                .andExpect(jsonPath("$.byGroup[0].name").exists());
    }

    @Test
    void everyMenuPageRenders() throws Exception {
        for (NavItem item : NavItem.ALL) {
            mvc.perform(get(item.path())).andExpect(status().isOk());
        }
        mvc.perform(get("/settings/import")).andExpect(status().isOk());
    }
}
