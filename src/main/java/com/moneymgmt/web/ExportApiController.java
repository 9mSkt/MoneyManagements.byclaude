package com.moneymgmt.web;

import com.moneymgmt.service.YearExportService;
import com.moneymgmt.util.AppTime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The yearly Excel file for the owner's Google Apps Script, which saves it to their Google Drive every January.
 * There is no sign-in here: the request must carry the secret EXPORT_TOKEN as "Authorization: Bearer …".
 * Without EXPORT_TOKEN set, this address does not exist (404).
 */
@RestController
public class ExportApiController {

    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    public static final String KEY_LAST = "drive.export.last";

    private final YearExportService export;
    private final com.moneymgmt.repo.AppSettingRepository settings;
    private final byte[] token;

    public ExportApiController(YearExportService export, com.moneymgmt.repo.AppSettingRepository settings,
                               @Value("${app.export-token:}") String token) {
        this.export = export;
        this.settings = settings;
        String t = token == null ? "" : token.trim();
        if (!t.isEmpty() && t.length() < 32) {
            throw new IllegalStateException("EXPORT_TOKEN is too short (32+ characters). Create one with: export-token");
        }
        this.token = t.isEmpty() ? null : t.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/api/export/year.xlsx")
    public ResponseEntity<byte[]> year(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                       @RequestParam(required = false) Integer year,
                                       @RequestParam(defaultValue = "en") String lang) throws IOException {
        if (token == null) {
            return ResponseEntity.notFound().build();
        }
        String given = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : "";
        if (!MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        int y = year == null ? AppTime.thisMonth().getYear() - 1 : year;
        if (y < 1990 || y > AppTime.thisMonth().getYear()) {
            return ResponseEntity.badRequest().build();
        }
        Locale locale = "th".equals(lang) ? Locale.forLanguageTag("th") : Locale.ENGLISH;
        byte[] body = export.yearXlsx(y, locale);
        // Remembered for the Settings page: when the Drive script last fetched a yearly file, and which year.
        var s = settings.findById(KEY_LAST).orElse(null);
        String value = java.time.Instant.now() + " " + y;
        if (s == null) {
            settings.save(new com.moneymgmt.domain.AppSetting(KEY_LAST, value));
        } else {
            s.set(value);
            settings.save(s);
        }
        return xlsx(YearExportService.fileName(y), body);
    }

    public boolean isEnabled() {
        return token != null;
    }

    static ResponseEntity<byte[]> xlsx(String name, byte[] body) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(XLSX))
                .body(body);
    }
}
