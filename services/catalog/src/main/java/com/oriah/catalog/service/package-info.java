/**
 * Transactional business services for the catalog.
 *
 * <p>Each public method is one unit of work — wraps a single
 * {@code @Transactional} boundary, emits the INFO-level audit log for
 * state-changing operations, and exposes only the domain objects
 * controllers need.
 *
 * <p>Services depend on {@link com.oriah.catalog.repository} and
 * {@link com.oriah.catalog.model}; they do NOT depend on
 * {@link com.oriah.catalog.controller} or {@link com.oriah.catalog.dto}
 * — except for partial-update request records like
 * {@code ProductUpdateRequest}, which carry the
 * "which fields are present" intent that's clumsy to express as separate
 * primitive parameters.
 */
package com.oriah.catalog.service;
