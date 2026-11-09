/**
 * Catalog domain events — what catalog tells the rest of the system has
 * happened.
 *
 * <p>Each event is a Java {@code record} (immutable, equals-by-value). Its
 * JSON is the payload consumers read, so these records ARE the contract:
 * inventory and search depend on it, and {@code ProductEventGoldenTest} pins
 * it byte for byte.
 *
 * <h2>How they are published</h2>
 *
 * {@link com.oriah.catalog.service.ProductService} appends each event to
 * catalog's outbox table (shared/oriah-events) in the same transaction as the
 * change it describes, and the outbox relay publishes the table to Kafka in
 * the order it was written. So:
 * <ul>
 *   <li><b>Nothing is announced that rolled back.</b> The event is a row in
 *       the same transaction; a rollback removes both.</li>
 *   <li><b>Nothing that committed is lost.</b> This replaced a send made after
 *       the commit, where a crash between the two lost the message for good.
 *       Now the row waits in the outbox until Kafka has it.</li>
 *   <li><b>Consumers can apply each event once, in order.</b> Every message
 *       carries an envelope: {@code event-id} to recognise a redelivery,
 *       {@code seq} (1, 2, 3 within the product) to skip an older snapshot,
 *       {@code aggregate-id} (the product id), the {@code actor} who made the
 *       change. The Kafka key is the product id, so one product's events
 *       stay on one partition, in order.</li>
 * </ul>
 *
 * <p>One topic, several event types: all three land on
 * {@code catalog.product}, and each carries a {@code TYPE} constant matching
 * the {@code event-type} header, so a consumer can route within the topic
 * without reading the payload.
 */
package com.oriah.catalog.events;
