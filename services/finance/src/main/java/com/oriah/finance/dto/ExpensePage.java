package com.oriah.finance.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** A page of expenses, newest first. */
@Schema(name = "ExpensePage")
public record ExpensePage(List<ExpenseResponse> items, int page, int size, long total) {
}
