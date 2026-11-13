/**
 * Pricing: the below-landed-cost rule, the suggested price and the dated price history.
 *
 * <p>Pure types only (no JPA, no HTTP, no clock of their own): what a price is compared with, what a target profit makes of a
 * landed cost, and which row of a variant's history applies at an instant. The persistence and the cross-service call live
 * elsewhere, so these rules can be tested to the ngwee without a database.
 */
package com.oriah.catalog.pricing;
