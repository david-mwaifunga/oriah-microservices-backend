-- The message dispatcher's own state: one row, like an outbox relay's cursor row. Technical state, not
-- business data. It is how "the mail server is failing" is known on every pass, by every instance, and
-- across restarts: without it a restarted service would hammer a server that has been refusing its
-- sign-in for an hour.
CREATE TABLE dispatch_state (
    id                    SMALLINT     PRIMARY KEY,
    -- When the mail server first failed since it last accepted anything; NULL = it is working.
    server_failing_since  TIMESTAMPTZ,
    server_failures       INTEGER      NOT NULL DEFAULT 0,
    -- Nothing is handed to the mail server before this moment (it grows with each failure in a row).
    quiet_until           TIMESTAMPTZ,
    CONSTRAINT dispatch_state_single_row CHECK (id = 1),
    CONSTRAINT dispatch_state_failing_whole CHECK ((server_failing_since IS NULL) = (server_failures = 0)),
    CONSTRAINT dispatch_state_quiet_only_when_failing CHECK (quiet_until IS NULL OR server_failing_since IS NOT NULL)
);
INSERT INTO dispatch_state (id) VALUES (1);
