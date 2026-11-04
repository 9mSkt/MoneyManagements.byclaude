package com.moneymgmt.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "import_batch")
public class ImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_name", nullable = false)
    private String sourceName;

    @Column(name = "imported_at", nullable = false)
    private LocalDateTime importedAt;

    private String summary;

    protected ImportBatch() {
    }

    public ImportBatch(String sourceName, LocalDateTime importedAt) {
        this.sourceName = sourceName;
        this.importedAt = importedAt;
    }

    public Long getId() { return id; }
    public String getSourceName() { return sourceName; }
    public LocalDateTime getImportedAt() { return importedAt; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    /** Counts stored as "transactions,installments,assets"; formatted on the page through i18n. */
    public String[] getCounts() {
        String[] parts = summary == null ? new String[0] : summary.split(",");
        return parts.length == 3 ? parts : new String[] {"0", "0", "0"};
    }
}
