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
| `oriah.events.replay.enabled` | `OutboxReplayer`: answers replay requests from late consumers by sending the outbox again, a few rows at a time (see "Answering a replay request"). Needs the relay, `spring.application.name` and the send timeouts below, or the service refuses to start. |
| `oriah.events.inbox.enabled` | `Inbox`: applies each received event once (see "Receiving events"). Needs `oriah.events.inbox.topics`, the topics it reads. With `oriah.events.inbox.catch-up.consumer` also set, `CatchUp` too: a consumer that starts after its producers gets their whole history (see "How a late consumer catches up"). |

A service can be producer and consumer at once; the switches are
independent.

Each switch is `true` or `false` (any case). Anything else (`on`, `yes`,
`1`) stops the service at startup: Spring reads those spellings as true in
some places and false in others, so `on` could bind a listener with nothing
behind it.

`oriah.events.relay.batch-size` (default 100) is the most rows one relay
round sends. It must be 1 or more; 0 would publish nothing.

`oriah.events.replay.chunk-size` (default 10) is the most rows one step of
a replay sends (see "Answering a replay request"). It must be 1 or more,
and small enough that a whole step fits the time Kafka allows the replay
listener between two polls; the service refuses to start otherwise.

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

  **With replay on, the service refuses to start without them** (or with
  others too slow for the replay's step size): a replay step must finish
  within the time Kafka allows its listener, and these two waits decide how
  long a step can take. See "How long one step may take".

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
aggregate each is about and the payload versions it can read) and the types
it deliberately skips:

```java
static final InboxConsumer SEARCH = InboxConsumer.named("search")
        .handles("product", "product.updated", 1)   // aggregate type, event type, payload versions
        .handles("product", "product.deleted", 1)
        .ignores("product.reviewed");

@Transactional
public void on(Message<byte[]> message) {
    inbox.onceInOrder(SEARCH, message.getHeaders(), () -> index.apply(read(message)));
}
```

And list the topics the service reads through the Inbox:

```yaml
oriah.events.inbox.enabled: true
oriah.events.inbox.topics: catalog.product      # comma-separated if more than one
```

Their bindings are the ones that get the module's dead-letter handling (see
"When a message keeps failing"); every other binding of the service keeps
the Kafka binder's own. The service refuses to start with the Inbox on and
no topics listed, or with a listed topic that no input binding reads (a
typo would leave the real binding without the module's handling).

What happens to each delivery:

| The delivery | What the Inbox does |
|---|---|
| New, handled type | runs your change and records it, in your transaction |
| Already recorded (a duplicate) | nothing |
| Your change throws | nothing is recorded (it rolls back with your change), so the retry applies it |
| An ignored type | records it, runs nothing |
| A type you never declared, or a payload version you did not list | **throws before recording anything** |
| A handled type whose `aggregate-type` is not the aggregate you declared for it | **throws before recording anything** |
| A missing or garbled envelope header (`event-id`, `event-type`, `aggregate-type`, `aggregate-id`, `seq`, `schema-version`) | **throws before recording anything** |
| `onceInOrder` only: a `seq` no newer than the one already applied to that aggregate | records it, runs nothing (a newer snapshot is already in place) |

Why throw instead of skipping an unknown type: the message then goes to the
binding's dead-letter topic (see "When a message keeps failing"), and the
event itself stays in the producer's outbox for good, so a later version of
the consumer that does read that type can apply it from a replay. Skipping
it silently would lose it for good.

`onceInOrder` keeps the highest `seq` applied per aggregate in
`processed_aggregate`. That row is **never deleted**, so it outlives your own
row: a product deleted at seq 3 stays deleted when its seq 2 update turns up
late (it is the "tombstone"). Two deliveries for one aggregate at the same
moment are made to take turns (a lock on that row), so the older one is
always the one skipped. An ignored type never moves the `seq`.

Both methods refuse to run outside a transaction: the record must commit
with your change. The consumer name is 1 to 64 lower-case letters, digits or
dashes (it is also the suffix of the consumer's replay topic).

### Why each handled type names its aggregate

`seq` counts 1, 2, 3 separately inside each aggregate, and `onceInOrder`
keeps the newest one per consumer, **aggregate type** and aggregate id. So
the `aggregate-type` header decides which history a snapshot is ordered in.
Suppose a message arrived with the right event type, `product.updated`, but
the wrong `aggregate-type`, say `order`, and seq 9. Accepted, its product
snapshot would be applied, but seq 9 would be recorded under an order with
that id; the product's own seq would stay at, say, 3. A genuine product
snapshot with seq 5, older than the one just applied, would then look new
and overwrite the newer state. That is why each `handles` names the
aggregate its events are about, and the Inbox refuses (before recording
anything, so the message goes to the dead-letter topic) an event whose
`aggregate-type` is not that one. `once` checks it too: a fact consumer reads
`aggregate-id` as the id of that kind of thing.

The aggregate is declared, not worked out from the event type's name,
because the two need not match: payment's events are `payment.*`, but the
thing they are about is a `payment_intent`. It is spelled exactly as the
producer writes it (`Product` is not `product`), and follows the outbox's
rule for an aggregate type: present, at most 64 characters. One event type
is about one aggregate: declaring the same type again for the same
aggregate adds payload versions; for a different aggregate it is refused.
An ignored type names no aggregate - it is never applied, so it never
records a `seq`.

## When a message keeps failing: the dead-letter topic

A message the consumer cannot apply - the Inbox refuses it, or your change
throws - is tried again, and if it still fails it is moved aside to the
binding's **dead-letter topic**, so the messages behind it can be read. The
module does this itself for its own bindings, in place of the Kafka binder's
own handling. Why: when the binder's dead-letter publish fails (the topic is
missing, the broker refuses it), Spring Cloud Stream 5.0.1 only logs the
failure and then commits the message as read - it is silently dropped. The
module never commits a message until its dead-letter topic has it.

**Which bindings.** Only the module's own: the ones that read a topic in
`oriah.events.inbox.topics`, CatchUp's replay topic (`oriah.replay.<consumer>`)
and the replay-request listener (`oriah.outbox.replay-requests`). Every other
binding of the service keeps the binder's behaviour, untouched. (The module
gives the Kafka binder its one listener container customizer; a service that
defines another of its own is refused at startup, since the binder takes only
one and the module's could be the one left out.)

One exception the module cannot avoid: a binding of the service's own with
**both `batch-mode` and `enable-dlq`** is refused at startup. Once a
customizer like the module's is there, the Kafka binder (5.0.1) no longer
dead-letters any batch itself - it leaves every batch to the customizer,
which takes only the module's bindings - so a failing batch would be retried
ten times and then committed: dropped, although its settings say
"dead-letter". Turn `batch-mode` off for it (the binder then dead-letters each
failed record itself), or turn `enable-dlq` off if dropping a failed batch is
acceptable. No service reads in batches today.

**What happens to a failing message:**

1. It is tried `max-attempts` times **in all**, pausing between tries as the
   binding's `back-off-*` settings say. `max-attempts: 3` means three
   deliveries. (The binder's own retry, still used by every other binding,
   gives one more: four.)
2. An exception the binding lists under `retryable-exceptions` as `false` is
   not tried again: it goes to the dead-letter topic on its first delivery.
   The services list `IllegalArgumentException` (an Inbox refusal, which
   fails the same way every time). A few failures that can never succeed are
   never retried unless the binding names that very type as retryable
   (Spring Kafka's own list: a ClassCastException, a body that cannot be
   deserialised, and so on) - not even under a broader type marked `true`.
3. Then it is published to the dead-letter topic, and only then is its
   offset committed. This is **at least once**: if the service dies between
   the publish and the commit, the message is delivered again and lands on
   the dead-letter topic twice.
4. **If the dead-letter publish fails**, the message is not committed. It is
   delivered again (a retryable failure goes through its tries again first),
   and the module tries the dead-letter topic again. Nothing is dropped while
   Kafka still holds the message - 7 days by default (Kafka's own retention;
   the compose stack sets none). The cost: the consumer thread waits on that
   message, and so does every partition it reads, until the dead-letter
   topic takes it. Each failed round logs one ERROR and then pauses the
   binding's `back-off-max-interval` (10 s unless the binding says
   otherwise), so a publish that fails at once cannot spin the consumer. The
   ERROR comes back about every pause **plus** the time the failed publish
   itself took: anything from almost nothing (refused at once) up to about
   25 s (a topic that does not answer - see "The dead-letter publisher"
   below), so with the defaults expect one ERROR every 10 to 35 s. A
   retryable failure's back-offs between its tries come on top:

   ```
   ERROR ... DeadLetterThrottle : Dead-lettering failed for binding orderEvents-in-0: topic oriah.order,
   partition 0, offset 1234 (Dead-letter publication to oriah.order.dlq.notification failed ...). The
   record stays uncommitted and is delivered again after a pause of 10000 ms; its partition waits until
   the dead-letter topic takes it.
   ```

   It shows as these ERROR lines and as the consumer group's lag growing.
   **Operations: alert on both** (recorded as an operations item; nothing
   alerts on them yet).
5. If the stall outlasts Kafka's retention, the message is gone from Kafka -
   but never from the producer's outbox, so a replay (CatchUp) can still
   bring it back. Notification has no CatchUp today.

**The dead-letter record** (the contract for anyone reading a dead-letter
topic): the original key, body and headers, exactly as they arrived, plus
Spring Kafka's `kafka_dlt-*` headers:

| Header | Holds |
|---|---|
| `kafka_dlt-original-topic` | the topic it was read from (UTF-8) |
| `kafka_dlt-original-partition` | its partition (4-byte big-endian int) |
| `kafka_dlt-original-offset` | its offset (8-byte big-endian long) |
| `kafka_dlt-original-timestamp`, `kafka_dlt-original-timestamp-type` | Kafka's timestamp of the original |
| `kafka_dlt-original-consumer-group` | the group that failed it |
| `kafka_dlt-exception-fqcn`, `kafka_dlt-exception-cause-fqcn` | the exception's class, and its cause's |
| `kafka_dlt-exception-message`, `kafka_dlt-exception-stacktrace` | what went wrong (the stack trace names the real cause, e.g. the Inbox's refusal) |

(Other bindings, on the binder's own handling, write `x-original-topic`,
`x-exception-fqcn` and so on instead.)

**How each of the module's bindings must be set up** - the service refuses
to start otherwise, naming every problem at once, before anything is bound
(a binding refused later, while the binder sets it up, would not stop the
service: Spring Cloud Stream would leave it unbound and retry every 30 s):

- a `group` (Kafka keeps no dead-letter topic for an anonymous subscriber);
- `enable-dlq: true` and a `dlq-name` (by convention `<topic>.dlq.<group>`)
  that is **not the topic the binding reads**. Pointed back at its own topic,
  a failed message would be read again and again; worse, for a failure Spring
  Kafka counts as never worth retrying (a body that cannot be read, a
  ClassCastException and the like) its dead-letter publisher quietly skips a
  publish back to the same topic, and the message would be committed -
  dropped;
- `max-attempts` 2 or more - with 1 the binder hands every failure to its
  own dead-letter sender, the one that drops a message when its publish
  fails. To dead-letter a failure on its first delivery, mark its exception
  `false` under `retryable-exceptions` (or set `default-retryable: false`);
- no `batch-mode`, no `use-native-decoding`, no Kafka transactions, and key
  and value read as raw bytes (`ByteArrayDeserializer`, the binder's
  default): the module re-publishes the record's bytes exactly as Kafka
  held them;
- `retryable-exceptions` lists Exceptions only;
- each topic in `oriah.events.inbox.topics`, and CatchUp's replay topic, is
  read by exactly one input binding;
- **one binder, of type kafka, with no `environment` block.** The module
  reads each binding's Kafka settings from the service's configuration (the
  binding's own value, else `spring.cloud.stream.kafka.default.consumer`);
  with several binders each could carry settings of its own, so it supports
  one Kafka binder only. Declaring that one binder by name
  (`spring.cloud.stream.binders.<name>.type: kafka`) is fine, but not giving
  it settings under `spring.cloud.stream.binders.<name>.environment`: the
  binder reads those before the service's own configuration, so the module
  would be checking settings the binder does not use (a dead-letter switch,
  a deserializer, the replay's send timeouts). Put them in the service's
  configuration instead.

```yaml
spring.cloud.stream.bindings.orderEvents-in-0:
  destination: oriah.order
  group: notification
  consumer:
    max-attempts: 3                 # three deliveries in all
    back-off-initial-interval: 1000
    back-off-multiplier: 2.0
    back-off-max-interval: 10000    # also the pause after a failed dead-letter publish
    retryable-exceptions:
      java.lang.IllegalArgumentException: false
spring.cloud.stream.kafka.bindings.orderEvents-in-0.consumer:
  enable-dlq: true
  dlq-name: oriah.order.dlq.notification
oriah.events.inbox.topics: oriah.order
```

**The dead-letter publisher** is built for each binding from that binding's
own consumer settings - the broker address and the security settings,
nothing else - so two bindings never share credentials. It sends raw bytes
and waits for every replica (`acks=all`, idempotent). A dead-letter topic
that does not answer fails the publish - and keeps the message - within
about 25 seconds, the waits one after another:

| Wait | Up to | Why |
|---|---|---|
| Spring Kafka looks up the dead-letter topic's partitions | 5 s | to check the partition it is about to write to exists |
| the producer waits for the topic's details (`max.block.ms`) | 10 s | it cannot send to a topic it knows nothing about |
| the send itself, retries included (`delivery.timeout.ms`; each request to the broker `request.timeout.ms`, 5 s) | 10 s | the broker must confirm the record on every replica |

With Kafka's own defaults the last two would be 60 s and 120 s, holding the
consumer for three minutes on every failed round.

`DeadLetterKafkaTest` proves all of this on a real broker: a refusal
delivered once and dead-lettered with its key, body and headers; a failure
delivered three times then dead-lettered; one that clears on the second try
applied once with nothing dead-lettered; a missing dead-letter topic keeping
the message uncommitted (its group's committed offset checked with an admin
client), redelivered after the pause, with the ERROR logged, until the topic
is created and the message lands; a binding of the service's own left on
the binder's handling; and the precedence both ways - the binder-wide
default `enable-dlq` applying to a binding that says nothing, and a
binding's own `enable-dlq: true` winning over a default of `false` - read the
same by the real binder and by the module's startup check.
`DeadLetterStartupCheckTest` proves each startup refusal, and the same
precedence for every setting the check reads.

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

The listener is one of the module's bindings, so it gets the module's
dead-letter handling (see "When a message keeps failing"). **The module also
sets all of its Kafka settings; the service sets none of them**, and the
module's win over anything the service does set, because the way a replay
is answered depends on them:

```yaml
spring.cloud.stream.bindings.outboxReplayRequests-in-0.consumer:
  retryable-exceptions:
    java.lang.IllegalArgumentException: false  # a malformed request: dead-lettered on its first delivery
spring.cloud.stream.kafka.bindings.outboxReplayRequests-in-0.consumer:
  enable-dlq: true
  dlq-name: oriah.outbox.replay-requests.dlq.${spring.application.name}
  configuration:
    max.poll.records: 1           # one request - one step - per poll
    max.poll.interval.ms: 300000  # five minutes for it (see "How long one step may take")
```

A request is JSON (the `ReplayRequest` record):

```json
{"requestId": "5f0c...", "producer": "order", "consumer": "finance", "fromOutboxId": 1}
```

### Steps: why a replay is not answered in one go

A replay can be long - every row the producer has ever written. Kafka gives
a listener a fixed time between two polls (`max.poll.interval.ms`). A
listener that takes longer is taken to be dead: Kafka gives its partitions
to another instance, and the request it was answering is delivered again -
where it takes just as long, and so on for ever. So one delivery never
answers a whole replay. It answers one **step**: at most
`oriah.events.replay.chunk-size` rows (default 10). If rows are left, the
step ends by sending a **continuation** - the same request with two more
fields - back to `oriah.outbox.replay-requests`, which the producer reads
and answers as its next step, like any request:

```json
{"requestId": "5f0c...", "producer": "order", "consumer": "finance", "fromOutboxId": 1,
 "throughOutboxId": 35, "afterOutboxId": 10}
```

- `throughOutboxId` is **H**, the newest `outbox_id` when the replay began:
  the replay ends there, whichever step is answering.
- `afterOutboxId` is the last row already sent: the next step starts after it.

A consumer's own request has neither field; a continuation has both. Any
other mix is refused, as is an `afterOutboxId` below `fromOutboxId - 1`
(it would send rows before the first one asked for) or a `throughOutboxId`
below `afterOutboxId`.

For each request or continuation, the producer:

1. ignores one naming another producer;
2. refuses (throws, so it goes to the listener's dead-letter topic on its
   first delivery) one with no `requestId`, a `fromOutboxId` below 0, a
   consumer name that is not 1 to 64 lower-case letters, digits or dashes -
   the name becomes a topic name - or continuation fields that do not fit
   together (above);
3. ignores a step it has already answered. Each step has an id, recorded in
   `processed_event` under the consumer name `replay-requests` when the step
   commits: the `requestId` for the first step, and for a continuation a
   name-based UUID made from the `requestId` and `afterOutboxId`. So the same
   continuation delivered twice has the same id and is answered once, while
   the next step has an id of its own;
4. on the first step, takes H, and starts after `fromOutboxId - 1`; a
   continuation brings both with it;
5. sends up to `chunk-size` rows after that position and up to H, oldest
   first, to `oriah.replay.<consumer>`: each is the live message again (same
   body and headers) plus `replay-request-id` and `replay-prev-outbox-id` -
   the `outbox-id` of the record before it in this replay (`fromOutboxId - 1`
   for the very first; the last row of the step before, for the first row of
   a later step), so the consumer can tell it has every record with no gap,
   across all the steps;
6. if rows up to H are left, sends the continuation; otherwise sends the
   end-of-replay marker: `event-type` = `outbox.replay.completed`,
   `producer`, `replay-request-id`, `through-outbox-id` = H and
   `last-outbox-id` = the last record the replay sent (`fromOutboxId - 1`
   when there was none). Its body repeats these for whoever reads the topic
   by eye;
7. commits the step's record.

The consumer sees none of this: the records and the marker arrive exactly as
if they had been sent in one go, and CatchUp works as it always has.

**Every record of one replay, its marker, the consumer's request and every
continuation are keyed by the request id**, not by the aggregate id as live.
Kafka puts a record on a partition by its key, and keeps order only within a
partition. The consumer's replay topic may have several partitions: the
consumer's binder creates it when the replay binding starts, with one
partition per consumer thread (`concurrency`) per instance, or
`minPartitionCount` if that is more. Keyed by aggregate, one replay would be
split across those partitions and read out of order, and the consumer's
gap-free chain (below) would stop at the first record that came early - on
every retry, for good. With one key the whole run sits on one partition, in
the order it was sent. The same goes for the requests topic: with the request
and its continuations under one key, the steps of a replay are read one
after another, in order.

Rows appended while a replay runs are above H: they are not in it, and go
out on the live topic as always. A replay never touches the live topics or
the relay's cursor.

**When a step fails.** Each step is one database transaction, and every send
in it waits until Kafka has the record. If a send fails, or the service dies
before the step commits, the step's record rolls back and Kafka delivers the
step again: its rows are sent again (the consumer's Inbox applies the
repeats once) and so is its continuation - which is then on the topic twice,
and answered once (point 3). A step that keeps failing is tried
`max-attempts` times (3 by default) and then goes to the listener's
dead-letter topic; that replay then stops where it is, no marker comes, and
the consumer's catch-up stays open until its next start asks again from
where its chain stopped.

**What a replay leaves behind, and what nothing stops.**

- Every step's id stays in the producer's `processed_event` for good: the
  table is append-only, nothing deletes from it. A replay through H leaves
  about H / `chunk-size` rows there - at the default 10, a replay of a
  million-row outbox adds 100,000 rows.
- The producer cannot tell that a replay has been overtaken. When a consumer
  restarts before its catch-up completes, CatchUp asks again under a **new**
  request id (see "How a late consumer catches up"), while the continuations
  of the old request keep going, step by step, to their own H. Nothing goes
  wrong - the consumer applies each record once and skips the old request's
  records for its chain - but each such restart can cost the producer up to
  one more whole replay of sends, and its rows in `processed_event`. A
  consumer that keeps restarting during a long catch-up multiplies that work.
  Nothing stops an overtaken replay today.

### How long one step may take

The listener reads one request per poll, so the time between two polls is
one step. A step makes up to `chunk-size` + 1 sends (its rows, then the
continuation or the marker), one after another, and a send that Kafka does
not answer can wait `max.block.ms` and then `delivery.timeout.ms` before it
fails. So the service refuses to start unless

```
(chunk-size + 1) x (max.block.ms + delivery.timeout.ms) < max.poll.interval.ms
```

With the send timeouts every producing service sets (10000 each, see "The
four switches") and the default chunk size: 11 x 20 s = 220 s, under the
module's 300 s. A long history simply takes more steps.

**What the rule does not count.** It counts the sends only. A step that
fails also spends time on the listener's thread before the next poll:

- the pause before its next try (the listener's back-off: 1 s, then 2 s);
- after its last try, the dead-letter publish - up to about 25 s (see "The
  dead-letter publisher") - and, if that fails too, the pause after it (the
  back-off max-interval, 10 s).

At the default chunk size these fit in what the rule leaves: 220 + 25 + 10 =
255 s, under 300 s. The rule alone would accept a chunk size up to 13 with
these timeouts (14 x 20 s = 280 s), but there they do not fit: a step that
fails slowly on its last try could take 280 + 25 + 10 = 315 s. Kafka would
then presume the listener dead and deliver the step again - nothing is lost,
the step is simply answered again - but it is wasted work. So leave
`chunk-size` at 10 unless there is a measured reason to change it; the
refusal says the same.

Each value is read as the Kafka binder will use it: a send timeout from
`spring.kafka.properties`, `spring.kafka.producer.properties`, the binder's
`configuration`, its `producer-properties`, then
`spring.cloud.stream.kafka.default.producer.configuration`, a later one
winning (the replay topics are named only when a request arrives, so they
have no binding of their own - do not give one any). Missing everywhere, a
timeout is Kafka's own default - 60000 and 120000 - which fails the rule
whatever the chunk size. A binder `environment` block, which the binder would
read first, is refused (see "How each of the module's bindings must be set
up"), so these are the values the binder uses. The refusal says each number,
where it came from, and what to change:

```
oriah-events refuses to start: one replay step could take longer than Kafka allows the replay-request
listener (outboxReplayRequests-in-0) between two polls. ...
A step sends up to oriah.events.replay.chunk-size rows plus one continuation or end-of-replay marker, ...:
(10 + 1) x (60000 + 120000) ms = 1980000 ms, which is not under max.poll.interval.ms 300000 ms.
Where each number came from: chunk size 10 (oriah.events.replay.chunk-size), max.block.ms 60000 (Kafka's
default), delivery.timeout.ms 120000 (Kafka's default), max.poll.interval.ms 300000
(spring.cloud.stream.kafka.bindings.outboxReplayRequests-in-0.consumer.configuration).
To fix it, set max.block.ms: 10000 and delivery.timeout.ms: 10000 under
spring.cloud.stream.kafka.binder.producer-properties, ...
This rule counts the sends only. A step that fails also waits ... the default chunk size of 10 leaves room
for them; the largest the rule allows may not, ...
```

`OutboxReplayTest` proves each rule of a step on the test binder, including
a history whose outbox ids have gaps (an append that rolled back uses up its
id for good): each record names the row actually sent before it, so the
chain runs unbroken across every gap.
`ChunkedReplayKafkaTest` proves the whole of it on a real broker: a producer
and a late consumer, each on the real Kafka binder, a history of 35 rows
answered in steps of 10, with the requests topic and the replay topic each
of three partitions. The second step "crashes" after its records and its
continuation have gone out but before it commits; rows are appended while
the replay runs. Every row arrives in order with an unbroken chain, the
repeated step's records are applied once, its doubled continuation is
answered once, the new rows arrive only live, every record of the replay
(and every request) sits on one partition under the request id, and the
consumer's catch-up completes at H. `ReplayStepTimeTest` proves the startup
rule and where each value is read from.

## How a late consumer catches up (CatchUp)

A consumer added months after a producer started (finance, Phase 10) has
missed everything Kafka no longer keeps - Kafka holds messages only for a
while. The producer's outbox holds them for good, so the consumer asks for
a **replay** of it. `CatchUp` does the asking and keeps track of how far
the replay has got.

### Switching it on

```yaml
oriah.events.inbox.enabled: true
oriah.events.inbox.topics: oriah.order
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
# Both are the module's bindings, so both need dead-lettering (see "When a message keeps failing").
spring.cloud.stream.kafka.default.consumer.enable-dlq: true
spring.cloud.stream.kafka.bindings.orderEvents-in-0.consumer.dlq-name: oriah.order.dlq.finance
spring.cloud.stream.kafka.bindings.financeReplay-in-0.consumer.dlq-name: oriah.replay.finance.dlq.finance
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
id. It is keyed by its request id, as the producer keys every continuation
of it, so the whole replay's requests sit on one partition of the requests
topic, in order (see "Answering a replay request").

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
| A replay record whose change throws | nothing recorded; dead-lettered (see "When a message keeps failing"); the chain stops before it |
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
