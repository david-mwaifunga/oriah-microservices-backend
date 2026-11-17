-- Phase 12 (review fix): a reversal id the order system gave up is remembered.
--
-- Order asks inventory to reverse a sale between two short transactions. When a correction is stuck and a person abandons it, order must be sure the
-- stock did not move AND never will under that id: a request that was already on its way (a retry by the job, a repeat by a person, one queued behind
-- a slow connection) could otherwise arrive a moment after order wrote "nothing moved" and put the stock back for an order that carries on as paid.
--
-- So the abandon is a fact here, written under the same advisory lock a reversal takes: once an id is in this table, any request under it is refused
-- (409 reversal-abandoned) and moves nothing. An id that WAS applied is never abandoned (the answer says so, and order finishes its correction).

CREATE TABLE sale_reversal_abandoned (
    id           UUID         PRIMARY KEY,           -- the reversal id (order's correction id)
    abandoned_at TIMESTAMPTZ  NOT NULL,
    actor        VARCHAR(128) NOT NULL
);

CREATE TRIGGER sale_reversal_abandoned_append_only_row BEFORE UPDATE OR DELETE ON sale_reversal_abandoned
    FOR EACH ROW EXECUTE FUNCTION oriah_refuse_change();
CREATE TRIGGER sale_reversal_abandoned_append_only_truncate BEFORE TRUNCATE ON sale_reversal_abandoned
    FOR EACH STATEMENT EXECUTE FUNCTION oriah_refuse_change();
