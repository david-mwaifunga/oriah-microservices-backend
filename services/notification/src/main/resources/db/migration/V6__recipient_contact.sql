-- Where to email a customer: the address their own sign-in token carried the last time they opened the shop
-- (GET /api/v1/notifications/me, which the website's bell calls on every page). ONE row per person, updated
-- in place, deletable. This is the only place notification keeps a customer's address: never in an event,
-- on a message row or in a log line.
CREATE TABLE recipient_contact (
    keycloak_id      VARCHAR(255)  PRIMARY KEY,
    email            VARCHAR(254)  NOT NULL,
    -- Whether the address was ever confirmed (the token's email_verified). An address nobody confirmed may
    -- be a stranger's (a typing mistake at sign-up), so it is only ever sent a short "please sign in" note.
    email_verified   BOOLEAN       NOT NULL,
    -- When the token that taught this was issued (its iat): an older token never replaces a newer one.
    token_issued_at  TIMESTAMPTZ   NOT NULL,
    first_seen_at    TIMESTAMPTZ   NOT NULL,
    seen_at          TIMESTAMPTZ   NOT NULL,
    CONSTRAINT recipient_contact_email_shape CHECK (email = btrim(email) AND position('@' IN email) > 1)
);
