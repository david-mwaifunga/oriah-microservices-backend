package com.oriah.catalog.client;

import java.util.List;

import com.oriah.catalog.pricing.HeldCost;

/** What inventory's held-cost read answers: one entry per variant asked about. */
public record HeldCostReply(List<HeldCost> variants) {
}
