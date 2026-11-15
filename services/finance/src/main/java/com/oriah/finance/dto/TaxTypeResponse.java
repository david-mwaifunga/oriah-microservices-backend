package com.oriah.finance.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** A tax that may be entered. */
@Schema(name = "TaxType")
public record TaxTypeResponse(String code, String label) {
}
