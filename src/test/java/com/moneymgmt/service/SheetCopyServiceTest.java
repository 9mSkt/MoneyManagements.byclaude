package com.moneymgmt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.moneymgmt.AbstractWebTest;
import com.moneymgmt.repo.AppSettingRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/** Daily Google Sheet copy against a fake Sheets API (no real Google account, made-up sheet id). */
@Transactional
class SheetCopyServiceTest extends AbstractWebTest {

    @Autowired DataExportService export;
    @Autowired AppSettingRepository settings;

    private static final String SHEET = "sample-sheet-id";
    private static final String FAKE_JSON = "{\"type\":\"service_account\",\"client_email\":\"copy@sample.iam.gserviceaccount.com\"}";

    private SheetCopyService service(MockRestServiceServer[] server) {
        RestClient.Builder builder = RestClient.builder();
        server[0] = MockRestServiceServer.bindTo(builder).build();
        SheetCopyService s = new SheetCopyService(export, settings, builder, SHEET, FAKE_JSON);
        s.useToken(() -> "test-token");
        return s;
    }

    @Test
    void copiesEveryTableIntoItsOwnTab() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        SheetCopyService s = service(server);
        server[0].expect(requestTo(containsString("/spreadsheets/" + SHEET + "?fields=")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("{\"sheets\":[{\"properties\":{\"title\":\"category\"}}]}", MediaType.APPLICATION_JSON));
        server[0].expect(requestTo(containsString(SHEET + ":batchUpdate")))
                .andExpect(content().string(containsString("\"addSheet\"")))
                .andExpect(content().string(containsString("\"transaction_record\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("{\"title\":\"category\"}"))))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server[0].expect(requestTo(containsString("/values:batchClear")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server[0].expect(requestTo(containsString("/values:batchUpdate")))
                .andExpect(content().string(containsString("\"valueInputOption\":\"RAW\"")))
                .andExpect(content().string(containsString("ค่าน้ำมัน")))   // Thai category names travel as text
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(s.copyNow()).isTrue();
        server[0].verify();
        var st = s.status();
        assertThat(st.configured()).isTrue();
        assertThat(st.serviceAccount()).isEqualTo("copy@sample.iam.gserviceaccount.com");
        assertThat(st.lastSuccess()).isNotNull();
        assertThat(st.lastError()).isNull();
    }

    @Test
    void aGoogleErrorIsSavedForTheSettingsPage() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        SheetCopyService s = service(server);
        server[0].expect(requestTo(containsString("/spreadsheets/" + SHEET)))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("{\"error\":\"The caller does not have permission\"}"));
        assertThat(s.copyNow()).isFalse();
        assertThat(s.status().lastError()).contains("403").contains("permission");
        assertThat(s.status().lastSuccess()).isNull();
    }

    @Test
    void dueOncePerThaiDay() {
        Instant evening = Instant.parse("2031-03-10T16:30:00Z");   // 23:30 in Bangkok
        Instant laterSameNight = Instant.parse("2031-03-10T16:50:00Z");
        Instant afterMidnight = Instant.parse("2031-03-10T17:05:00Z");   // 00:05 next day in Bangkok
        assertThat(SheetCopyService.isDue(null, evening)).isTrue();
        assertThat(SheetCopyService.isDue(evening, laterSameNight)).isFalse();
        assertThat(SheetCopyService.isDue(evening, afterMidnight)).isTrue();
    }

    @Test
    void notConfiguredDoesNothing() {
        SheetCopyService s = new SheetCopyService(export, settings, RestClient.builder(), "", "");
        assertThat(s.isConfigured()).isFalse();
        s.copyIfDue();   // no HTTP call, no error
        assertThat(s.status().configured()).isFalse();
    }

    @Test
    void cellsKeepNumbersAndTurnDatesIntoText() {
        assertThat(SheetCopyService.cell(new BigDecimal("12.50"))).isEqualTo(new BigDecimal("12.50"));
        assertThat(SheetCopyService.cell(null)).isEqualTo("");
        assertThat(SheetCopyService.cell(java.sql.Date.valueOf("2031-01-02"))).isEqualTo("2031-01-02");
        var t = new DataExportService.Table("x", List.of("a"), List.of(List.of(true)));
        assertThat(SheetCopyService.values(t)).containsExactly(List.of("a"), List.of(true));
        assertThat(SheetCopyService.decode(java.util.Base64.getEncoder().encodeToString(FAKE_JSON.getBytes())))
                .isEqualTo(FAKE_JSON);
    }
}
