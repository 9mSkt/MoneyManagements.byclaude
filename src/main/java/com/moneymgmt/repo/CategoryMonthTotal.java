package com.moneymgmt.repo;

import com.moneymgmt.domain.TxType;
import java.math.BigDecimal;

/** Sum of one category and type in one month, computed by the database. */
public record CategoryMonthTotal(Integer year, Integer month, Long categoryId, TxType type, BigDecimal amount) {
}
