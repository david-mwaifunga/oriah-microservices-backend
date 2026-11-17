#!/usr/bin/env python3
"""Captures and verifies the platform's error responses AS SEEN AT THE EDGE.

Tier 0 planned two golden sets: service-local (0d) and gateway-through (0e).
The service-local half is covered by unit and integration tests in each module.
This is the other half, and it is the only artefact that records what a client
actually receives -- after the gateway's filters, its security chain, its
circuit breakers and its error handling have all had a turn.

WHY A GOLDEN FILE AND NOT MORE TESTS
------------------------------------
The per-service tests assert that a handler produces a 404 with a problem body.
They cannot see that the gateway rewrote the status, swallowed the body, or
added a header -- all three of which this project has done at some point:

  * framework errors rendered as 500 platform-wide              (Tier 0.5)
  * a 503 from an unresolvable lb:// target reported as 500      (Tier 2)
  * a circuit-breaker fallback carrying no correlation id        (Tier 3)
  * every unmapped path answering 500                           (Tier 5)

Each was found by looking at a real response at the edge. This file makes that
look repeatable and diffable, so the next regression of that shape shows up as
a changed golden rather than as a report from a user.

NORMALISATION
-------------
traceId, correlation ids, UUIDs and timestamps change every run, so they are
replaced with placeholders. What remains is the part that is supposed to be
stable: status, content type, problem `type`, `title`, and the SHAPE of the
detail. A diff on this file is therefore a contract change, not noise.

USAGE
    python scripts/error_goldens.py capture [--gateway URL]
    python scripts/error_goldens.py verify  [--gateway URL]

Exit 0 on success, 1 on a golden mismatch (verify) or a capture failure, 2 on
a usage error.
"""

import argparse
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

GOLDEN = Path(__file__).with_name("error-goldens.json")

UUID_RE = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                     r"[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
HEX32_RE = re.compile(r"\b[0-9a-f]{32}\b")
HEX16_RE = re.compile(r"\b[0-9a-f]{16}\b")
ISO_RE = re.compile(r"\d{4}-\d{2}-\d{2}T[\d:.]+Z?")

MISSING = "00000000-0000-0000-0000-000000000000"

# (name, method, path, who, body)
#   who: None = anonymous, "user" = ROLE_USER, "admin" = all admin roles
CASES = [
    # --- 401: the security chain at the edge -------------------------------
    ("401-cart-anonymous", "GET", "/api/v1/carts/me", None, None),
    ("401-admin-products-anonymous", "GET", "/api/v1/admin/products", None, None),
    ("401-contact-info-anonymous", "GET", "/api/v1/products/contact-info", None, None),

    # The edge is STRICTER than the service here, deliberately. inventory's own
    # SecurityConfig permits anonymous GET /api/v1/inventory/**, because
    # catalog's Feign call needs it -- but that call goes service-to-service over
    # the docker network and never touches the gateway, whose PUBLIC_GET_PATHS
    # list is only /api/v1/products/** and /api/v1/search/**. So no anonymous
    # client can read stock, which is correct and was previously undocumented.
    # Captured because it reads like a bug and is not.
    ("401-inventory-read-anonymous-at-edge", "GET", "/api/v1/inventory/" + MISSING, None, None),

    # --- 403: authenticated, wrong role -----------------------------------
    # A GET, not a POST. The first version of this case used
    # POST /api/v1/admin/products with what turned out to be an ADMIN token, so
    # it returned 201 and CREATED A PRODUCT every time the goldens were
    # captured. A golden capture must not mutate state.
    ("403-admin-products-as-plain-user", "GET", "/api/v1/admin/products", "user", None),
    # procurement (Phase 2): the supplier list is PROCUREMENT_ADMIN only. Also a
    # GET, so capturing it creates nothing.
    ("403-procurement-suppliers-as-plain-user", "GET", "/api/v1/admin/procurement/suppliers", "user", None),
    # procurement (Phase 4): consignments, their costs and invoices are PROCUREMENT_ADMIN only.
    ("403-procurement-consignments-as-plain-user", "GET", "/api/v1/admin/procurement/consignments", "user", None),
    # Phase 5: what stock cost is COST_VIEWER's, writing stock off is STOCK_WRITE_OFF's, and nobody keys a sale by
    # hand: the gateway does not route confirm for anyone, an owner included (the denial is the case). All three are
    # refused before anything is read or written, so capturing them creates no state.
    ("403-cost-lots-as-plain-user", "GET", "/api/v1/admin/costs/lots", "user", None),
    ("403-write-offs-as-plain-user", "GET", "/api/v1/admin/inventory/write-offs", "user", None),
    ("403-sale-keyed-through-the-gateway", "POST", "/api/v1/admin/inventory/" + MISSING + "/confirm", "admin",
     {"qty": 1, "saleLineId": MISSING}),
    # Phase 6: pricing shows landed cost, so it needs CATALOG_ADMIN and COST_VIEWER both, and boxes set aside are
    # INVENTORY_ADMIN's. Both are GETs refused at the edge, so capturing them creates nothing.
    ("403-pricing-list-as-plain-user", "GET", "/api/v1/admin/pricing/below-cost", "user", None),
    ("403-box-commitments-as-plain-user", "GET", "/api/v1/admin/inventory/box-commitments", "user", None),

    # --- 404: the Tier 5 bodies, through the gateway ----------------------
    ("404-unknown-product", "GET", "/api/v1/products/" + MISSING, None, None),
    ("404-unknown-order", "GET", "/api/v1/orders/" + MISSING, "user", None),
    # procurement: "admin" is maya (OWNER, so she holds PROCUREMENT_ADMIN). A
    # well-formed id that no supplier has is a 404, not the 400 a malformed id gets.
    ("404-unknown-supplier", "GET", "/api/v1/admin/procurement/suppliers/" + MISSING, "admin", None),
    # Phase 4: an order, a consignment and a document that nobody has. Each is a GET.
    ("404-unknown-purchase-order", "GET", "/api/v1/admin/procurement/purchase-orders/" + MISSING, "admin", None),
    ("404-unknown-consignment", "GET", "/api/v1/admin/procurement/consignments/" + MISSING, "admin", None),
    ("404-unknown-procurement-document", "GET", "/api/v1/admin/procurement/documents/" + MISSING + "/content", "admin", None),
    # Phase 5: a write-off nobody recorded, and a correction of a consignment nobody has (nothing is written).
    ("404-unknown-write-off", "GET", "/api/v1/admin/inventory/write-offs/" + MISSING, "admin", None),
    ("404-amend-unknown-consignment", "POST", "/api/v1/admin/procurement/consignments/" + MISSING + "/amendments", "admin",
     {"fromRevision": 1, "reason": "golden: nothing to correct",
      "costLines": [{"costType": "COURIER_INBOUND", "amount": 1.00}]}),
    # Phase 6: a variant nobody has (found before any cost is asked for), and a product nobody has.
    ("404-pricing-unknown-variant", "GET", "/api/v1/admin/pricing/variants/" + MISSING, "admin", None),
    ("404-unpublish-unknown-product", "POST", "/api/v1/admin/products/" + MISSING + "/unpublish", "admin", None),
    # Phase 7: the doors the sale no longer has answer like any unmapped path (a clear 404 problem), never a stack trace and never a quiet success. These
    # three replaced 404-unknown-payment-intent, 404-unknown-promotion and 422-outside-delivery-area, whose endpoints are gone.
    ("404-payment-intent-door-retired", "GET", "/api/v1/payments/intents/" + MISSING, "user", None),
    ("404-promotion-code-door-retired", "GET", "/api/v1/promotions/NOSUCHCODE", "user", None),
    ("404-shipping-rates-retired", "POST", "/api/v1/shipping/rates", "user",
     {"destinationCountry": "US", "subtotal": 100.00, "currency": "ZMW"}),
    # Phase 7: an order nobody has, a town nobody has, and a box swap on an order nobody has; each is found before anything is written.
    ("404-payment-claim-for-an-unknown-order", "POST", "/api/v1/orders/" + MISSING + "/payment-claim", "user",
     {"method": "MTN", "reference": "MP261113"}),
    ("404-quote-for-an-unknown-town", "POST", "/api/v1/shipping/quote", "user",
     {"townId": MISSING, "optionCode": "OWN_DELIVERY", "goodsTotal": 100.00, "currency": "ZMW"}),
    ("404-box-swap-on-an-unknown-order", "POST", "/api/v1/admin/orders/" + MISSING + "/box-swaps", "admin",
     {"lineId": MISSING, "replacementBoxVariantId": MISSING, "reason": "golden: no such order to change"}),
    # Phase 7 (the delivery waiver): an order nobody has; a reason too short is refused before the order is read; a shopper does not hold DELIVERY_WAIVER.
    ("404-delivery-waiver-on-an-unknown-order", "POST", "/api/v1/admin/orders/" + MISSING + "/delivery-waiver", "admin",
     {"reason": "golden: no such order to waive"}),
    ("400-delivery-waiver-reason-too-short", "POST", "/api/v1/admin/orders/" + MISSING + "/delivery-waiver", "admin",
     {"reason": "no"}),
    ("403-delivery-waiver-by-a-shopper", "POST", "/api/v1/admin/orders/" + MISSING + "/delivery-waiver", "user",
     {"reason": "golden: a shopper may not waive delivery"}),
    # Phase 8 (deliver): the dispatch steps, the packer's list and slip, and the correction of delivery details. An order nobody has is 404 on each (nothing is
    # written); a bad stage is refused before the database is read; a shopper holds none of the capabilities. "admin" (maya, OWNER) holds FULFILMENT_AGENT and ORDER_ADMIN.
    ("404-pack-an-unknown-order", "POST", "/api/v1/admin/orders/" + MISSING + "/dispatch/pack", "admin", {}),
    ("404-slip-of-an-unknown-order", "GET", "/api/v1/admin/orders/" + MISSING + "/dispatch", "admin", None),
    ("404-correct-delivery-of-an-unknown-order", "POST", "/api/v1/admin/orders/" + MISSING + "/delivery-details", "admin",
     {"recipientName": "Mulenga Chanda", "recipientPhone": "0971234567", "reason": "golden: no such order to correct",
      "address": {"line1": "Plot 12 Great East Road", "city": "Lusaka", "country": "ZM"}}),
    ("400-dispatch-queue-with-a-bad-stage", "GET", "/api/v1/admin/orders/dispatch/queue?stage=NOT_A_STAGE", "admin", None),
    ("403-dispatch-queue-as-a-shopper", "GET", "/api/v1/admin/orders/dispatch/queue", "user", None),
    ("403-pack-as-a-shopper", "POST", "/api/v1/admin/orders/" + MISSING + "/dispatch/pack", "user", {}),
    # Phase 9 (phone orders): an order nobody recorded is 404 on each door (nothing is written); a body with no expected total is 400 before anything else is looked at; a shopper holds
    # no SALES_AGENT. "admin" (maya, OWNER) holds SALES_AGENT.
    ("404-recorded-order-unknown", "GET", "/api/v1/admin/orders/recorded/" + MISSING, "admin", None),
    ("404-recorded-receipt-unknown", "GET", "/api/v1/admin/orders/recorded/" + MISSING + "/receipt", "admin", None),
    ("404-recorded-cancel-unknown", "POST", "/api/v1/admin/orders/recorded/" + MISSING + "/cancel", "admin", None),
    ("400-record-without-an-expected-total", "POST", "/api/v1/admin/orders/recorded", "admin",
     {"kind": "LIVE_PHONE", "caller": {"name": "Golden Caller"}, "lines": [{"variantId": MISSING, "quantity": 1}], "delivery": {"mode": "PICKUP"}}),
    ("403-record-as-a-shopper", "POST", "/api/v1/admin/orders/recorded", "user", {}),
    ("403-recorded-list-as-a-shopper", "GET", "/api/v1/admin/orders/recorded", "user", None),
    # Phase 10 (finance): an expense and a tax entry nobody typed are 404; a month that is not a month, and an expense with no Idempotency-Key, are refused before anything is read or
    # written; a shopper holds no FINANCE_ADMIN and no token is no door at all. "admin" (maya, OWNER) holds FINANCE_ADMIN.
    ("404-finance-expense-unknown", "GET", "/api/v1/admin/finance/expenses/" + MISSING, "admin", None),
    ("404-finance-tax-entry-unknown", "GET", "/api/v1/admin/finance/tax-entries/" + MISSING, "admin", None),
    ("400-finance-month-that-is-not-a-month", "GET", "/api/v1/admin/finance/reports/profit/october", "admin", None),
    ("400-finance-expense-without-a-key", "POST", "/api/v1/admin/finance/expenses", "admin",
     {"category": "RENT_UTILITIES", "amount": 1.00, "expenseDate": "2026-10-15", "description": "golden rent"}),
    ("403-finance-status-as-a-shopper", "GET", "/api/v1/admin/finance/status", "user", None),
    ("401-finance-report-without-a-token", "GET", "/api/v1/admin/finance/reports/profit/2026-10", None, None),
    # Phase 11 (notification): the two staff reads. Both are GETs and change nothing. A shopper holds neither SALES_AGENT nor the two cost
    # capabilities; a state that is not OPEN or ALL, and a messages read that names neither an order nor the work list, are refused.
    ("401-staff-alerts-without-a-token", "GET", "/api/v1/admin/notifications/alerts", None, None),
    ("403-staff-alerts-as-a-shopper", "GET", "/api/v1/admin/notifications/alerts", "user", None),
    ("400-staff-alerts-unknown-state", "GET", "/api/v1/admin/notifications/alerts?state=CLOSED", "admin", None),
    ("401-staff-messages-without-a-token", "GET", "/api/v1/admin/notifications/messages?status=BY_HAND", None, None),
    ("403-staff-messages-as-a-shopper", "GET", "/api/v1/admin/notifications/messages?status=BY_HAND", "user", None),
    ("400-staff-messages-neither-order-nor-work-list", "GET", "/api/v1/admin/notifications/messages", "admin", None),
    # Phase 12 (returns and refunds). None of these writes anything: each is refused before a correction, a debt or a payout exists. A paid order that
    # is not there cannot be cancelled or returned; "other" says why; a return needs its Idempotency-Key; the work lists know their states; a refund
    # nobody owes and a payout nobody made are named 404s; a payout needs its key too; and the stock system's own door (a sale's reversal) is closed
    # at the edge even to the owner.
    ("404-paid-cancellation-of-no-order", "POST", "/api/v1/admin/orders/" + MISSING + "/cancellation", "admin", {"reasonCode": "CUSTOMER_ASKED"}),
    ("404-no-paid-cancellation-to-read", "GET", "/api/v1/admin/orders/" + MISSING + "/cancellation", "admin", None),
    ("400-paid-cancellation-other-without-a-note", "POST", "/api/v1/admin/orders/" + MISSING + "/cancellation", "admin", {"reasonCode": "OTHER"}),
    ("400-return-without-a-key", "POST", "/api/v1/admin/orders/" + MISSING + "/returns", "admin",
     {"reasonCode": "CHANGED_MIND", "lines": [{"lineId": MISSING, "quantity": 1}]}),
    ("404-return-nobody-recorded", "GET", "/api/v1/admin/orders/returns/" + MISSING, "admin", None),
    ("400-returns-in-a-state-that-is-not-one", "GET", "/api/v1/admin/orders/returns?state=SOMETHING", "admin", None),
    ("404-assessment-of-no-return", "POST", "/api/v1/admin/orders/returns/" + MISSING + "/pieces/" + MISSING + "/assessment", "admin", {"outcome": "FINE"}),
    ("404-abandon-of-no-correction", "POST", "/api/v1/admin/orders/corrections/" + MISSING + "/abandon", "admin", {}),
    ("404-refund-nobody-owes", "GET", "/api/v1/admin/payments/refunds-due/" + MISSING, "admin", None),
    ("404-payout-nobody-made", "GET", "/api/v1/admin/payments/payouts/" + MISSING, "admin", None),
    ("400-payout-without-a-key", "POST", "/api/v1/admin/payments/payouts", "admin",
     {"refundDueId": MISSING, "method": "CASH", "customerFee": 0.00, "sendCharge": 0.00}),
    ("403-sale-reversal-by-the-owner-at-the-edge", "POST", "/api/v1/admin/inventory/sale-reversals", "admin", {}),
    # Phase 13 (paying through ZynlePay). None of these writes anything or asks ZynlePay anything: a payment needs its Idempotency-Key; a payment
    # nobody started is a named 404 for the shopper and for staff; the staff list knows its filters; the order's own door for payment is closed
    # at the edge even to the owner; and the old list of card intents is gone. (The refusals of a typed amount or card are in the smoke test:
    # they need a key, and this script sends none.)
    ("400-provider-payment-without-a-key", "POST", "/api/v1/payments/provider-payments", "user",
     {"orderId": MISSING, "channel": "MOBILE_MONEY", "phone": "0960000001"}),
    ("404-provider-payment-nobody-started", "GET", "/api/v1/payments/provider-payments/" + MISSING, "user", None),
    ("404-provider-payment-staff-never-saw", "GET", "/api/v1/admin/payments/provider-payments/" + MISSING, "admin", None),
    ("400-provider-payments-in-a-status-that-is-not-one", "GET", "/api/v1/admin/payments/provider-payments?status=SOMETHING", "admin", None),
    ("403-order-payment-door-by-the-owner-at-the-edge", "POST", "/api/v1/orders/" + MISSING + "/provider-payment", "admin",
     {"providerPaymentId": MISSING}),
    ("404-card-intent-list-retired", "GET", "/api/v1/admin/payments", "admin", None),
    ("404-unmapped-path-under-prefix", "GET", "/api/v1/products/does/not/exist", None, None),

    # --- 400: validation and binding --------------------------------------
    ("400-validation-missing-title", "POST", "/api/v1/admin/products", "admin",
     {"description": "no title", "tags": []}),
    ("400-malformed-uuid-path", "GET", "/api/v1/products/not-a-uuid", None, None),
    # Phase 4: both are refused before anything is written, so capturing them creates no state.
    ("400-purchase-order-without-lines", "POST", "/api/v1/admin/procurement/purchase-orders", "admin",
     {"lines": []}),
    ("400-consignment-without-orders", "POST", "/api/v1/admin/procurement/consignments", "admin", {}),
    # Phase 5: a correction must say which revision it was made from and why; refused before anything is read.
    ("400-amendment-without-reason", "POST", "/api/v1/admin/procurement/consignments/" + MISSING + "/amendments", "admin",
     {"fromRevision": 1}),
    # Phase 6: a price must be given, and a commitment is at least one box; both refused before anything is read or written.
    ("400-price-without-a-price", "PUT", "/api/v1/admin/pricing/variants/" + MISSING + "/price", "admin",
     {"expectedPriceId": None}),
    ("400-box-commitment-of-zero", "PUT", "/api/v1/admin/inventory/box-commitments/" + MISSING, "admin", {"quantity": 0}),
    # Phase 7: cash is never claimed (staff key it), and a town goes only with a delivery that needs one; both are refused before anything is read or written.
    ("400-payment-claim-in-cash", "POST", "/api/v1/orders/" + MISSING + "/payment-claim", "user",
     {"method": "CASH", "reference": "MP261113"}),
    ("400-preview-a-town-for-a-pickup", "POST", "/api/v1/checkout/preview", "user",
     {"deliveryOption": "SELF_COLLECT", "deliveryTownId": MISSING}),

    # --- 405 / 415: framework errors, the Tier 0.5 class ------------------
    # Both need a token, or the gateway answers 401 before the service ever
    # sees the request and the case tests the edge instead of the handler.
    ("405-wrong-method", "DELETE", "/api/v1/products", "admin", None),
    ("415-wrong-content-type", "POST", "/api/v1/admin/products", "admin", "RAW:not json"),

    # --- 422: well-formed, but something Oriah does not do ----------------
    # Nothing answers 422 today. Phase 0's 422-outside-delivery-area went in Phase 7 with the country/zone quote: delivery is priced per TOWN, and a town
    # nobody has priced is 409 town-not-offered, never "outside the delivery area" (see 404-shipping-rates-retired above).

    # --- 409 -----------------------------------------------------------------
    # Phase 6: inventory has not heard of this product, so nothing can be set aside for it. Refused before anything is written.
    ("409-box-commitment-for-an-unknown-product", "PUT", "/api/v1/admin/inventory/box-commitments/" + MISSING, "admin", {"quantity": 1}),

    # NOTE: no other 409 case. Reproducing inventory's over-reserve, a stale correction of a consignment or a price below
    # cost needs a stock row, a received consignment or a priced piece to exist first, and this script must not create
    # state. Those are covered by InventoryIntegrationTest.overReserveIsAConflict, ConsignmentAmendHttpTest.aStaleRevision
    # and PriceSettingHttpTest.belowCostWithoutAReasonIsAWarning.
]


def token(gateway, client, secret, user):
    body = urllib.parse.urlencode({
        "client_id": client, "client_secret": secret,
        "username": user, "password": "password", "grant_type": "password",
    }).encode()
    url = "http://localhost:7080/realms/oriah/protocol/openid-connect/token"
    with urllib.request.urlopen(urllib.request.Request(url, data=body), timeout=60) as r:
        return json.loads(r.read())["access_token"]


def normalise(text):
    if not text:
        return ""
    text = UUID_RE.sub("<uuid>", text)
    text = HEX32_RE.sub("<traceId>", text)
    text = HEX16_RE.sub("<spanId>", text)
    text = ISO_RE.sub("<timestamp>", text)
    return text


def call(gateway, method, path, bearer, body):
    url = gateway.rstrip("/") + path
    data, ctype = None, None
    if body == "RAW:not json":
        data, ctype = b"not json", "text/plain"
    elif body is not None:
        data, ctype = json.dumps(body).encode(), "application/json"

    req = urllib.request.Request(url, data=data, method=method)
    if bearer:
        req.add_header("Authorization", "Bearer " + bearer)
    if ctype:
        req.add_header("Content-Type", ctype)

    try:
        # A limit on every call: with none, a gateway that stopped answering (a stalled Docker engine did it once) hangs this script for ever
        # with nothing printed. A call that runs out is recorded as a transport error, which no golden matches.
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.headers.get("Content-Type"), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("Content-Type"), e.read().decode("utf-8", "replace")
    except Exception as exc:                                  # noqa: BLE001
        return None, None, "TRANSPORT-ERROR: " + str(exc)


def shape(raw):
    """The stable parts of a problem body, as a sorted key list plus values."""
    try:
        parsed = json.loads(raw)
    except (json.JSONDecodeError, TypeError):
        return {"body": normalise(raw)[:200]}
    if not isinstance(parsed, dict):
        return {"body": normalise(raw)[:200]}
    return {
        "keys": sorted(parsed.keys()),
        "type": parsed.get("type"),
        "title": parsed.get("title"),
        "status": parsed.get("status"),
        "detail": normalise(str(parsed.get("detail"))),
        "instance": normalise(str(parsed.get("instance"))),
    }


def capture(gateway, tokens):
    out = {}
    for name, method, path, who, body in CASES:
        status, ctype, raw = call(gateway, method, path, tokens.get(who), body)
        out[name] = {
            "request": "%s %s%s" % (method, path.replace(MISSING, "<uuid>"),
                                    "" if who is None else "  (as %s)" % who),
            "status": status,
            "contentType": (ctype or "").split(";")[0] or None,
            "problem": shape(raw),
        }
    return out


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("mode", choices=["capture", "verify"])
    ap.add_argument("--gateway", default="http://localhost:8080")
    ap.add_argument("--client", default="oriah-admin")
    ap.add_argument("--secret", default="oriah-admin-secret")
    args = ap.parse_args(argv)

    try:
        admin = token(args.gateway, args.client, args.secret, "maya")
    except Exception as exc:                                  # noqa: BLE001
        print("ERROR: could not obtain a token from Keycloak: %s" % exc)
        print("Is the stack up? docker compose up -d")
        return 2

    # Two DIFFERENT principals, which the first version got wrong by reusing
    # the admin token for both. maya (keycloak/dev/oriah-users-0.json) holds
    # OWNER + USER, so all 14 capabilities, and a "403 as a user" case run
    # with her token is a 2xx -- and when the case was a POST, a silently
    # successful one.
    try:
        plain_user = token(args.gateway, args.client, args.secret, "alice")
    except Exception as exc:                                  # noqa: BLE001
        print("ERROR: could not obtain alice's token: %s" % exc)
        return 2
    tokens = {"admin": admin, "user": plain_user, None: None}

    captured = capture(args.gateway, tokens)
    transport = [k for k, v in captured.items()
                 if v["status"] is None or "TRANSPORT-ERROR" in str(v["problem"])]
    if transport:
        print("ERROR: %d case(s) could not reach the gateway: %s"
              % (len(transport), ", ".join(transport)))
        return 2

    if args.mode == "capture":
        GOLDEN.write_text(json.dumps(captured, indent=2, sort_keys=True) + "\n",
                          encoding="utf-8")
        print("captured %d error responses -> %s" % (len(captured), GOLDEN.name))
        for name in sorted(captured):
            c = captured[name]
            print("  %-34s %s  %s" % (name, c["status"], c["contentType"]))
        return 0

    if not GOLDEN.exists():
        print("ERROR: no golden file at %s -- run `capture` first" % GOLDEN)
        return 2

    expected = json.loads(GOLDEN.read_text(encoding="utf-8"))
    diffs = []
    for name in sorted(set(expected) | set(captured)):
        if name not in expected:
            diffs.append((name, "NEW case, not in the golden file", "", ""))
            continue
        if name not in captured:
            diffs.append((name, "case missing from this capture", "", ""))
            continue
        for field in ("status", "contentType", "problem"):
            if expected[name][field] != captured[name][field]:
                diffs.append((name, field,
                              json.dumps(expected[name][field]),
                              json.dumps(captured[name][field])))

    print("verified %d error responses against %s" % (len(captured), GOLDEN.name))
    if not diffs:
        print("PASS: every error response at the edge matches its golden.")
        return 0

    print("\nFAIL: %d difference(s) -- a client-visible error contract changed" % len(diffs))
    for name, field, was, now in diffs:
        print("  ! %s / %s" % (name, field))
        if was:
            print("      golden:  %s" % was)
            print("      current: %s" % now)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
