package com.oriah.finance.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Which producer's history to read again; left out, every producer's. */
@Schema(name = "ReverifyRequest")
public record ReverifyRequest(@Schema(description = "order, payment, inventory, procurement or catalog; left out means all", types = {"string", "null"}) String producer) {
}
