package com.oriah.finance.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** A page of ledger rows, newest first. */
@Schema(name = "LedgerEntryPage")
public record EntryPage(List<EntryResponse> items, int page, int size, long total) {
}
