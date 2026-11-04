package com.moneymgmt.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.moneymgmt.AbstractWebTest;
import com.moneymgmt.repo.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;

@WithMockUser(username = "tester")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ImportFlowTest extends AbstractWebTest {

    @Autowired TransactionRepository transactions;
    @Autowired InstallmentRepository installments;
    @Autowired AssetRepository assets;
    @Autowired AssetTradeRepository trades;
    @Autowired TaxYearRepository taxYears;
    @Autowired CategoryRepository categories;
    @Autowired ImportBatchRepository batches;

    private void uploadAndConfirm() throws Exception {
        MockHttpSession session = new MockHttpSession();
        MockMultipartFile file = new MockMultipartFile("file", "sample.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", SampleWorkbook.build());
        mvc.perform(multipart("/settings/import/preview").file(file).session(session).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("sample.xlsx")));
        mvc.perform(post("/settings/import/confirm").session(session).with(csrf()))
                .andExpect(redirectedUrl("/settings/import"));
    }

    @Test
    void importSavesEverythingAndReimportReplacesIt() throws Exception {
        uploadAndConfirm();
        long txCount = transactions.count();
        assertThat(txCount).isGreaterThan(20);
        assertThat(installments.count()).isEqualTo(1);
        assertThat(assets.count()).isEqualTo(2);
        assertThat(trades.count()).isEqualTo(3);
        assertThat(taxYears.findByYear(2026)).isPresent();
        assertThat(categories.findByCode("FIXED_GYM_MEMBERSHIP")).get()
                .satisfies(c -> assertThat(c.isNeedsReview()).isTrue());

        uploadAndConfirm();
        assertThat(transactions.count()).as("re-import replaces, not duplicates").isEqualTo(txCount);
        assertThat(installments.count()).isEqualTo(1);
        assertThat(batches.count()).isEqualTo(1);
    }

    @Test
    void rejectsAFileThatIsNotAWorkbook() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "notes.xlsx", "text/plain", "hello".getBytes());
        mvc.perform(multipart("/settings/import/preview").file(file).with(csrf()))
                .andExpect(redirectedUrl("/settings/import"))
                .andExpect(flash().attribute("error", "import.error.read"));
    }

    @Test
    void confirmWithoutPreviewSaysExpired() throws Exception {
        mvc.perform(post("/settings/import/confirm").with(csrf()))
                .andExpect(flash().attribute("error", "import.error.expired"));
    }
}
