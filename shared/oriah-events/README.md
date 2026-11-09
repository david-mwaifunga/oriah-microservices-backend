# oriah-events: the event log convention

Every fact that affects money (an order placed, a payment taken, a price
changed) is written to a permanent log in the service that owns it, **in the
same database transaction as the change itself**. The log is then published
to Kafka in the order it was written. Consumers apply each event once, and a
consumer that joins late can ask for the whole log again.

Why the same transaction: if the order row commits, its `order.created` is in
the log; if the order rolls back, so does the event. Nothing is ever
announced about an order that does not exist, and nothing that happened is
ever left unannounced.

## The four switches

All four are off by default. Adding this jar to a service changes nothing
until the service turns a switch on.

| Property | What it gives the service |
|---|---|
| `oriah.events.outbox.enabled` | `OutboxWriter`: append a fact to the `outbox` table inside your own transaction. |
| `oriah.events.relay.enabled` | `OutboxRelay`: publishes the outbox to Kafka every `oriah.events.relay.delay` ms (default 500). Needs the outbox, `oriah.events.producer` and the binder's sync sends (below), or the service refuses to start. |
| `oriah.events.replay.enabled` | `OutboxReplayer`: answers replay requests from late consumers by sending the outbox again (see "Answering a replay request"). Needs the relay and `spring.application.name`, or the service refuses to start. |
| `oriah.events.inbox.enabled` | `Inbox`: applies each received event once (see "Receiving events"). With `oriah.events.inbox.catch-up.consumer` also set, `CatchUp` too: a consumer that starts after its producers gets their whole history (see "How a late consumer catches up"). |

A service can be producer and consumer at once; the switches are
independent.

Each switch is `true` or `false` (any case). Anything else (`on`, `yes`,
`1`) stops the service at startup: Spring reads those spellings as true in
some places and false in others, so `on` could bind a listener with nothing
behind it.

`oriah.events.relay.batch-size` (default 100) is the most rows one relay
round sends, and the page size a replay reads in. It must be 1 or more; 0
would publish nothing and leave a replay paging for ever.

Also set:

- `oriah.events.producer` - this service's name (`order`, `payment`). Every
  message carries it.
- `oriah.events.topics` - the only topics this service may publish to. An
  append to any other topic is refused, so a typo fails loudly instead of
  publishing to a topic nobody reads.
- In the binder config: `spring.cloud.stream.kafka.default.producer.sync=true`
  (a send returns only once Kafka has it) and
  `spring.cloud.stream.default.contentType=application/json`. These apply to
  the relay's dynamic topics too, so no per-topic binding is needed.

  **The relay and CatchUp refuse to start without `sync=true`.** With
  Kafka's default the send only queues the record inside the service and
  answers "sent"; if the broker then fails to take it, that is reported
  only in a log. The relay would move its cursor past an event that was
  never published, and CatchUp would wait for an answer to a request
  nobody received. The check reads the default only, so never override it
  to `false` for a single binding.
- And a limit on how long one send may wait for Kafka:

  ```yaml
  spring.cloud.stream.kafka.binder.producer-properties:
    max.block.ms: 10000         # waiting for the topic's details from the broker
    request.timeout.ms: 5000    # one request to the broker
    delivery.timeout.ms: 10000  # the whole send, retries included
  ```

  Why: while a send waits, the relay's round holds its database
  transaction and its place in the log. With Kafka's own defaults, a
  stopped broker holds it for 60 seconds (waiting for the topic's details)
  or 120 (waiting for the send). With these, the send fails within about
  10 to 25 seconds (the two waits plus 5 seconds the binder adds), the
  cursor stays where it was, and the row goes out in a later round once the
  broker is back. `OutboxRelayKafkaTest` proves exactly this against a real
  broker that it stops and starts again.

## Writing an event

```java
@Transactional
public Order markPaid(...) {
    order.markPaid(...);
    orders.flush();                       // your own write first: a constraint
                                          // violation fails here, before the lock
    outboxWriter.append(new EventDraft(
            "order", order.id().toString(), "order.paid", 1, "oriah.order",
            paidAt,                       // when it really happened, never "now" by default
            "user:" + jwt.getSubject(),   // or "system:<name>" for a job
            OrderSnapshot.of(order)));    // every amount with its currency
    return order;
}
```

`append` refuses to run outside a transaction. It takes a lock that queues
appends from different transactions behind each other until each commits;
that is what lets the relay keep a single "published up to here" number
without ever stepping over a row that commits late. Append last, so the lock
is held briefly.

## What a published message looks like

- **Body**: the payload exactly as stored (JSON, nulls written, amounts like
  `150.00`, times like `2026-11-09T07:30:00Z`).
- **Key**: the aggregate id, so every event of one order lands on one Kafka
  partition and is read in order. It is sent as UTF-8 bytes, because the
  Kafka binder only accepts a key as bytes; a consumer reads the
  `aggregate-id` header rather than the key.
- **Headers**: `event-id`, `event-type`, `aggregate-type`, `aggregate-id`,
  `seq` (1, 2, 3 within the aggregate), `outbox-id`, `occurred-at`,
  `recorded-at`, `actor`, `schema-version`, `producer`.

The relay delivers **at least once**: if it crashes after Kafka took a
message but before it recorded that, the message is sent again with the same
`event-id`. It never skips one. Consumers dedupe on `event-id`.

## Facts and projections: the one rule for consumers

A consumer is one of two kinds, never a mix:

- **A fact consumer** (`once`) - each event is a thing that happened, applied
  exactly once, in any order. Example: finance recording a sale. Order does
  not matter because each fact stands alone.
- **A projection** (`onceInOrder`) - keeps a copy of another service's state
  (search's product index). Each event is a **full snapshot** of its
  aggregate, so the projection only ever needs the newest one: an older
  snapshot arriving late is skipped, using `seq`.

So producers emit a full snapshot for any state a projection reads, and no
consumer may depend on receiving changes ("deltas") in strict order.

## Receiving events (the Inbox)

Kafka delivers **at least once**: after a crash or a rebalance the same
event can arrive again. The Inbox makes that harmless. It records each event
it applies in `processed_event` (consumer + event-id) **in the same
transaction as your change**, so the record and the change commit together
or not at all.

Declare the consumer once - its name, the event types it applies (with the
payload versions it can read) and the types it deliberately skips:

```java
static final InboxConsumer SEARCH = InboxConsumer.named("search")
        .handles("product.updated", 1)
        .handles("product.deleted", 1)
        .ignores("product.reviewed");

@Transactional
public void on(Message<byte[]> message) {
    inbox.onceInOrder(SEARCH, message.getHeaders(), () -> index.apply(read(message)));
}
```

What happens to each delivery:

| The delivery | What the Inbox does |
|---|---|
| New, handled type | runs your change and records it, in your transaction |
| Already recorded (a duplicate) | nothing |
| Your change throws | nothing is recorded (it rolls back with your change), so the retry applies it |
| An ignored type | records it, runs nothing |
| A type you never declared, or a payload version you did not list | **throws before recording anything** |
| A missing or garbled envelope header (`event-id`, `event-type`, `aggregate-type`, `aggregate-id`, `seq`, `schema-version`) | **throws before recording anything** |
| `onceInOrder` only: a `seq` no newer than the one already applied to that aggregate | records it, runs nothing (a newer snapshot is already in place) |

Why throw instead of skipping an unknown type: the binder retries the
message, then parks it on the dead-letter topic. Nothing is lost - a later
version of the consumer that does read that type can apply it from a replay.
Skipping it silently would lose it for good.

`onceInOrder` keeps the highest `seq` applied per aggregate in
`processed_aggregate`. That row is **never deleted**, so it outlives your own
row: a product deleted at seq 3 stays deleted when its seq 2 update turns up
late (it is the "tombstone"). Two deliveries for one aggregate at the same
moment are made to take turns (a lock on that row), so the older one is
always the one skipped. An ignored type never moves the `seq`.

Both methods refuse to run outside a transaction: the record must commit
with your change. The consumer name is 1 to 64 lower-case letters, digits or
dashes (it is also the suffix of the consumer's replay topic).

## Answering a replay request

A producer with `oriah.events.replay.enabled=true` listens on
`oriah.outbox.replay-requests`. The module binds the listener itself: in a
consumer group named after the service (`spring.application.name`), so each
producer service sees every request once however many instances it runs,
and it adds the listener (`outboxReplayRequests`) to the service's
`spring.cloud.function.definition`, keeping the service's own consumers.

> If the service has a consumer of its own and relies on Spring Cloud Stream
> picking its only function without being told, list that function in
> `spring.cloud.function.definition`: with the listener there are now two,
> and Spring binds none it was not told about.

A request is JSON (the `ReplayRequest` record):

```json
{"requestId": "5f0c...", "producer": "order", "consumer": "finance", "fromOutboxId": 0}
```

The producer:

1. ignores a request naming another producer;
2. refuses (throws: retried, then dead-lettered) one with no `requestId`, a
   `fromOutboxId` below 0, or a consumer name that is not 1 to 64 lower-case
   letters, digits or dashes - the name becomes a topic name;
3. ignores a request it has already answered (its `requestId` is recorded in
   `processed_event` under the consumer name `replay-requests`);
4. takes **H**, the newest `outbox_id` at that moment, and sends every row
   from `fromOutboxId` through H, oldest first, to `oriah.replay.<consumer>`:
   each is the live message again (same body and headers) plus
   `replay-request-id` and `replay-prev-outbox-id` - the `outbox-id` of the
   record before it in this replay (`fromOutboxId - 1` for the first), so the
   consumer can tell it has every record with no gap;
5. ends with one marker: `event-type` = `outbox.replay.completed`,
   `producer`, `replay-request-id`, `through-outbox-id` = H and
   `last-outbox-id` = the last record sent (`fromOutboxId - 1` when there was
   none). Its body repeats these for whoever reads the topic by eye.

**Every record of one replay, and its marker, is keyed by the request id**,
not by the aggregate id as live. Kafka puts a record on a partition by its
key, and the consumer's replay topic may have several partitions: the
consumer's binder creates it when the replay binding starts, with one
partition per consumer thread (`concurrency`) per instance, or
`minPartitionCount` if that is more. Keyed by aggregate, one replay would be
split across those partitions and read out of order, and the consumer's
gap-free chain (below) would stop at the first record that came early - on
every retry, for good. With one key the whole run sits on one partition, in
the order it was sent, however many partitions the topic has.
`OutboxRelayKafkaTest` proves this on a real broker with a three-partition
replay topic.

Rows appended while a replay runs are above H: they are not in it, and go
out on the live topic as always. A replay never touches the live topics or
the relay's cursor. If a send fails part-way, nothing is recorded and the
redelivered request replays the whole range again; the consumer's Inbox
applies the repeats once.

## How a late consumer catches up (CatchUp)

A consumer added months after a producer started (finance, Phase 10) has
missed everything Kafka no longer keeps - Kafka holds messages only for a
while. The producer's outbox holds them for good, so the consumer asks for
a **replay** of it. `CatchUp` does the asking and keeps track of how far
the replay has got.

### Switching it on

```yaml
oriah.events.inbox.enabled: true
oriah.events.inbox.catch-up.consumer: finance          # its Inbox name; replays come on oriah.replay.finance
oriah.events.inbox.catch-up.producers: order,payment   # the services it catches up from
```

One catching-up consumer per service: `outbox_catch_up` keeps one position
per producer, so `CatchUp` refuses any consumer but the one named here. The
service refuses to start with no producers listed, or with a name that is
not 1 to 64 lower-case letters, digits or dashes.

The consumer then has two kinds of binding. Its live ones read the
producers' topics in a **new** consumer group, from the earliest message
Kafka still has. One more reads its replay topic:

```yaml
spring.cloud.function.definition: orderEvents;financeReplay
spring.cloud.stream.bindings.orderEvents-in-0: {destination: oriah.order, group: finance}
spring.cloud.stream.kafka.bindings.orderEvents-in-0.consumer.startOffset: earliest
spring.cloud.stream.bindings.financeReplay-in-0: {destination: oriah.replay.finance, group: finance}
```

The replay handler is the live handler with `catchUp` in place of `inbox` -
the same Inbox method underneath, so an event is applied once whichever
stream brings it:

```java
@Transactional
public void onOrderEvent(Message<byte[]> message) {          // live
    inbox.once(FINANCE, message.getHeaders(), () -> ledger.record(read(message)));
}

@Transactional
public void onReplay(Message<byte[]> message) {              // oriah.replay.finance
    catchUp.once(FINANCE, message.getHeaders(), () -> ledger.record(read(message)));
}
```

A projection uses `onceInOrder` in both places instead.

The consumer sends too - its replay requests - so it sets the same binder
settings as a producer: `spring.cloud.stream.kafka.default.producer.sync=true`
and the three send timeouts (see "The four switches"). CatchUp refuses to
start without `sync=true`: a request Kafka never took would otherwise look
sent, nothing would ever answer it, and the catch-up would stay open with
nothing to say so.

The replay binding needs nothing about partitions. However many its topic
has, each replay arrives whole on one of them, in order (see "Answering a
replay request").

### What happens

**On every start**, for each producer listed:

| The producer's row in `outbox_catch_up` | What CatchUp does |
|---|---|
| none yet (first start) | adds one (a new request id, `replayed_through` 0) and asks for the whole log |
| there, not complete | gives it a **new** request id and asks again from `replayed_through + 1` |
| complete | nothing |

Every request asks from `replayed_through + 1` - the first one too, which
is from 1, the whole log (outbox ids start at 1). That way the first record
of every replay names `replayed_through` as the record before it, so the
gap-free chain below starts with it. (Asked "from 0", the first record
would name -1, and the chain could never start.)

The request is sent after the row is saved, so no replayed record can
arrive before the row knows its request. If it cannot be sent - the binder
refuses it, or Kafka does not take it within the send timeouts - the
service fails to start, rather than run believing it asked; the row keeps
its position, and the next start asks again from there with a new request
id.

**The live bindings start at the same moment.** There is no switch-over
from replay to live: a fact is applied once whichever stream brings it
first, and a projection skips any snapshot older than the one it holds. So
the two streams may arrive interleaved in any order. Nothing is lost to
retention either: live is read from the earliest message kept, and
anything older comes from the replay.

**`replayed_through` means "every row up to here is applied".** Each
replayed record is applied through the Inbox and, in the same transaction,
moves `replayed_through` up to its `outbox-id` - but only when both hold:

- its `replay-request-id` is the row's current request, and
- its `replay-prev-outbox-id` equals `replayed_through` (it continues an
  unbroken chain).

**The end-of-replay marker** completes the catch-up (`completed_through` =
H, `completed_at` = now) only when its `last-outbox-id` equals
`replayed_through`: nothing is missing behind it.

| What arrives | What happens |
|---|---|
| A replay record that continues the chain | applied once; `replayed_through` moves to it |
| A replay record whose change throws | nothing recorded; retried, then dead-lettered; the chain stops before it |
| A later replay record, after such a failure | applied once; `replayed_through` stays |
| A record of an earlier request, still on the topic after a restart | applied once; `replayed_through` stays |
| A record arriving again (Kafka redelivers) | nothing new; `replayed_through` stays |
| A live delivery, even the very record the chain waits for | applied once, through the Inbox; never touches `replayed_through` |
| The marker, nothing missing | the catch-up is complete |
| The marker, after a failure | stays open; the next start asks again from where the chain stopped |
| A record or marker with a missing or garbled replay header (`producer`, `outbox-id`, `replay-request-id`, `replay-prev-outbox-id`; on the marker `through-outbox-id`, `last-outbox-id`) | **throws before recording anything** |

So a record that cannot be applied blocks completion until a fixed
version of the consumer starts: it asks again from that record, applies it
and finishes. Records after it were applied the first time round and are
skipped as duplicates.

`CatchUpTest` proves each rule, including a request the binder refuses
(the start fails) and the row committing before the request goes out.
`LateConsumerTest` starts a fact consumer
and a projection late while the producer keeps appending and the relay
keeps publishing, delivers live and replay in the worst order it can
arrange - and again on real threads at the same time - and checks that
every fact is applied exactly once, every projection matches the newest
snapshot, and both catch-ups complete.

## The tables (each service adds them itself)

This module ships **no** Flyway migration. A service copies the reference
SQL from `src/test/resources/db/oriah-events/` into its own next-free
migration:

| File | Who copies it |
|---|---|
| `V1__outbox.sql` - `outbox` (append-only) and `outbox_relay_cursor` (seeded `'main', 0`) | every producer |
| `V2__processed_event.sql` - `processed_event` (append-only) | every consumer, and every producer (it dedupes replay requests) |
| `V3__processed_aggregate.sql` - `processed_aggregate` | projection consumers |
| `V4__outbox_catch_up.sql` - `outbox_catch_up` | a consumer that uses CatchUp |

Then it proves the copy with the guards from this module's test-jar
(`<type>test-jar</type>`, test scope), against its own migrated
Testcontainers database:

```java
assertThat(OutboxSchemaGuard.problems(dataSource)).isEmpty();
assertThat(InboxSchemaGuard.problems(dataSource, PROCESSED_EVENT)).isEmpty();
```

The guards compare every column, default and constraint, and actually try
UPDATE, DELETE and TRUNCATE on the append-only tables (inside a transaction
they always roll back) to prove each is refused. The outbox guard also checks
two things that would make the relay skip events for good:

- `outbox_id`'s sequence starts at 1 and hands out ids one at a time
  (`START 1 MINVALUE 1 CACHE 1`). The relay sends only ids above its
  cursor, which starts at 0, so an id of 0 would never go out; and with a
  larger cache each pooled connection holds back its own block of ids, so
  ids stop following commit order.
- the cursor's `'main'` row is at 0, or at the id of a row the relay has
  already sent (the guard may run after other tests have published). A seed
  of `('main', 5)` would mean the first five events are never published.
