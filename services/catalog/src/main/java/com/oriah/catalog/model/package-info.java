/**
 * Domain model — JPA entities + pure domain types.
 *
 * <p>Classes here are doubly responsible: they're the persistence
 * mapping (via {@code @Entity}, {@code @Column}, etc.) AND the
 * in-memory domain model. Business invariants live in their
 * constructors and update methods — for example,
 * {@link com.oriah.catalog.model.Product#updateTitle(String)} enforces
 * the same non-blank rule as construction, so a title can never become
 * blank via any code path.
 *
 * <p>Classes in this package have NO knowledge of HTTP, JSON, or
 * Spring's wire layer. DTOs in {@link com.oriah.catalog.dto} adapt
 * them to and from the API contract.
 */
package com.oriah.catalog.model;
