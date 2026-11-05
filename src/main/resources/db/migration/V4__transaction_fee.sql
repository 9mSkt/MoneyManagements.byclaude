-- Credit card fee / interest paid together with an installment payment (e.g. a month the owner paid only
-- the minimum due). The transaction amount includes it; this column records how much of it was fee.
ALTER TABLE transaction_record ADD COLUMN fee NUMERIC(14,2);
