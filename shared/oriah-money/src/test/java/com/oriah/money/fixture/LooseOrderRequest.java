package com.oriah.money.fixture;

import java.math.BigDecimal;
import java.util.List;

import com.oriah.money.NgweeAmount;

/** Fixture for MoneyEdgeGuardTest: one guarded field, two unguarded. */
public record LooseOrderRequest(@NgweeAmount BigDecimal total, BigDecimal discount, List<Line> lines) {

    public record Line(String title, BigDecimal unitPrice) {}
}