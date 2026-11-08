package com.oriah.money.fixture;

import java.math.BigDecimal;

/** Fixture for MoneyEdgeGuardTest: a response, which the guard must ignore. */
public record PriceResponse(BigDecimal unitPrice) {}