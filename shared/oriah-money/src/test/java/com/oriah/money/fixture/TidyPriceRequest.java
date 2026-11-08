package com.oriah.money.fixture;

import java.math.BigDecimal;

import com.oriah.money.NgweeAmount;

/** Fixture for MoneyEdgeGuardTest: fully guarded. */
public record TidyPriceRequest(@NgweeAmount BigDecimal unitPrice) {}