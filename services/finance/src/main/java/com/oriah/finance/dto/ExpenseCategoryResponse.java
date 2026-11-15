package com.oriah.finance.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** One heading an expense can be entered under, and what it needs. */
@Schema(name = "ExpenseCategory")
public record ExpenseCategoryResponse(String code, String label, boolean handKeyable, boolean posted, boolean needsOrder, boolean needsProvider, boolean needsConfirmation) {
}
