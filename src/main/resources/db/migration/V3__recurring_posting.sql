-- Phase 2: link transactions posted from a recurring item, and remember the last month each
-- recurring item / installment was posted, so a posting the owner deletes is not posted again.

ALTER TABLE transaction_record ADD COLUMN recurring_item_id BIGINT;
ALTER TABLE transaction_record ADD CONSTRAINT fk_tx_recurring
    FOREIGN KEY (recurring_item_id) REFERENCES recurring_item(id) ON DELETE SET NULL;
CREATE INDEX ix_tx_recurring ON transaction_record(recurring_item_id);
CREATE INDEX ix_tx_installment ON transaction_record(installment_id);

ALTER TABLE recurring_item ADD COLUMN last_posted_month DATE;
ALTER TABLE installment ADD COLUMN last_posted_month DATE;
