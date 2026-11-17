#!/usr/bin/env python3
"""Content audit of the captured OpenAPI documents.

The additions-only gate (openapi_diff.py) proves nothing was REMOVED. It cannot
prove the document says anything useful, and for most of this project's life it
did not: an audit of the Tier 0 baseline found that all 51 operations documented
exactly one response, 200 -- no 400, 401, 403, 404 or 409 anywhere -- while
`ProblemDetail`, the shape every error actually returns, was published as a
schema in no service at all. Eleven endpoints were also wrong about SUCCESS,
documenting 200 where the handler answers 201 or 204, because springdoc cannot
see inside `ResponseEntity.created(...)` and falls back to 200.

So this is the other half of the gate: the diff checks the contract did not
regress, and this checks it is worth having. Runs over the captured JSON in
milliseconds -- no JVM, no containers -- which is why it is a script rather than
eleven more 120-second Spring contexts.

USAGE
    python scripts/openapi_audit.py CURRENT_DIR
    python scripts/openapi_audit.py --self-test CURRENT_DIR

Exit 0 when every check passes, 1 on any finding, 2 on a usage/IO error.
"""

import argparse
import copy
import json
import sys
import tempfile
from pathlib import Path

SERVICES = [
    "catalog", "inventory", "customer", "cart", "payment", "order",
    "promotion", "shipping", "checkout", "notification", "review", "search",
    "procurement", "finance",
]

METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")

PROBLEM_REF = "#/components/schemas/ProblemDetail"

# The endpoints whose real success status is NOT 200. Every one of these was
# documented as 200 in the Tier 0 baseline.
#
# customer's selfProvision is the interesting one: it answers 201 the first
# time and 200 on every repeat, because it is idempotent. Both are real, so
# both must be documented -- which is why the expectation is "contains 201"
# rather than "is exactly 201".
NON_200_SUCCESS = {
    ("catalog", "post", "/api/v1/admin/products"): "201",
    ("catalog", "delete", "/api/v1/admin/products/{id}"): "204",
    ("inventory", "post", "/api/v1/admin/inventory"): "201",
    ("customer", "post", "/api/v1/customers/me"): "201",
    ("customer", "post", "/api/v1/customers/me/addresses"): "201",
    ("customer", "delete", "/api/v1/customers/me/addresses/{addressId}"): "204",
    ("cart", "delete", "/api/v1/carts/me"): "204",
    ("cart", "delete", "/api/v1/carts/me/items/{variantId}"): "204",
    ("payment", "post", "/api/v1/payments/provider-payments"): "201",
    ("order", "post", "/api/v1/orders"): "201",
    ("promotion", "post", "/api/v1/admin/promotions"): "201",
    ("review", "post", "/api/v1/products/{productId}/reviews"): "201",
    # procurement: a supplier is added with 201 + Location and deleted (a soft
    # delete, correcting a mistake) with 204. springdoc cannot see inside
    # ResponseEntity.created(...), so without these two entries the audit would
    # accept a document that wrongly says "200".
    ("procurement", "post", "/api/v1/admin/procurement/suppliers"): "201",
    ("procurement", "delete", "/api/v1/admin/procurement/suppliers/{id}"): "204",
    # Phase 4: a purchase order, a consignment and an uploaded document are each created with
    # 201 + Location (ResponseEntity.created, which springdoc cannot see into); deleting a draft
    # consignment is 204.
    ("procurement", "post", "/api/v1/admin/procurement/purchase-orders"): "201",
    ("procurement", "post", "/api/v1/admin/procurement/consignments"): "201",
    ("procurement", "delete", "/api/v1/admin/procurement/consignments/{id}"): "204",
    ("procurement", "post", "/api/v1/admin/procurement/consignments/{id}/documents"): "201",
    ("procurement", "post", "/api/v1/admin/procurement/purchase-orders/{id}/documents"): "201",
    # Phase 10: finance writes answer 201 the first time and 200 for the same request again (ResponseEntity, which springdoc cannot see into).
    ("finance", "post", "/api/v1/admin/finance/expenses"): "201",
    ("finance", "post", "/api/v1/admin/finance/expenses/{id}/reversal"): "201",
    ("finance", "post", "/api/v1/admin/finance/expenses/{id}/correction"): "201",
    ("finance", "post", "/api/v1/admin/finance/tax-entries"): "201",
    ("finance", "post", "/api/v1/admin/finance/tax-entries/{id}/reversal"): "201",
    ("finance", "post", "/api/v1/admin/finance/tax-entries/{id}/correction"): "201",
    ("finance", "post", "/api/v1/admin/finance/statement-checks"): "201",
    ("finance", "post", "/api/v1/admin/finance/reports/profit/{month}/issue"): "201",
    ("finance", "post", "/api/v1/admin/finance/anomalies/{id}/ack"): "201",
    ("finance", "post", "/api/v1/admin/finance/catch-up/reverify"): "201",
}

# The one auto-numbered id in the contract, pinned deliberately rather than
# renamed. See AdminOrderController's javadoc for why the suffix is load-bearing.
ALLOWED_SUFFIXED_IDS = {("order", "get_1")}


class Findings:
    def __init__(self):
        self.items = []

    def add(self, service, kind, detail):
        self.items.append((service, kind, detail))

    @property
    def ok(self):
        return not self.items


def _ops(path_item):
    return {m: v for m, v in path_item.items() if m in METHODS and isinstance(v, dict)}


def load(path):
    with open(path, encoding="utf-8-sig") as fh:
        return json.load(fh)


def audit_service(service, spec, f):
    paths = spec.get("paths") or {}
    schemas = ((spec.get("components") or {}).get("schemas")) or {}

    references_problem = False

    for path, item in paths.items():
        for method, op in _ops(item).items():
            label = "%s %s" % (method.upper(), path)
            responses = op.get("responses") or {}

            # 1. an explicit, non-accidental operationId
            op_id = op.get("operationId")
            if not op_id:
                f.add(service, "operationId missing", label)
            elif (len(op_id) > 2 and op_id[-2] == "_" and op_id[-1].isdigit()
                  and (service, op_id) not in ALLOWED_SUFFIXED_IDS):
                f.add(service, "operationId auto-numbered",
                      "%s -> '%s'. springdoc appended the suffix because two handlers "
                      "share a name; which one receives it is decided by registration "
                      "order, so it can silently move. Pin both halves of the pair."
                      % (label, op_id))

            # 2. at least one 2xx, and the RIGHT one
            success = [c for c in responses if c.startswith("2")]
            if not success:
                f.add(service, "no success response documented", label)

            expected = NON_200_SUCCESS.get((service, method, path))
            if expected and expected not in responses:
                f.add(service, "wrong success status",
                      "%s answers %s but documents %s. springdoc cannot see inside "
                      "ResponseEntity and falls back to 200."
                      % (label, expected, sorted(success) or "nothing"))

            # 3. the error contract is described, and described as ProblemDetail
            errors = [c for c in responses if c[:1] in ("4", "5")]
            if not errors:
                f.add(service, "no error response documented",
                      "%s documents only %s -- the contract claims this call cannot "
                      "fail" % (label, sorted(responses)))

            for code in errors:
                body = json.dumps(responses[code])
                if PROBLEM_REF in body:
                    references_problem = True
                else:
                    f.add(service, "error response is not a ProblemDetail",
                          "%s -> %s does not reference %s, yet that is what the "
                          "service actually returns" % (label, code, PROBLEM_REF))

            # 4. tags are chosen, not derived from the class name
            for tag in (op.get("tags") or []):
                if tag.endswith("-controller"):
                    f.add(service, "tag is a springdoc default",
                          "%s is tagged '%s', which is the controller class name "
                          "kebab-cased" % (label, tag))

    # 5. ProblemDetail is actually published where it is referenced
    if references_problem and "ProblemDetail" not in schemas:
        f.add(service, "ProblemDetail referenced but not published",
              "responses point at %s but components.schemas has no ProblemDetail, "
              "so a client generator has no type for any failure" % PROBLEM_REF)


def run(current_dir, services=None):
    f = Findings()
    checked = []
    current_dir = Path(current_dir)

    for service in (services or SERVICES):
        p = current_dir / (service + ".json")
        if not p.exists():
            f.add(service, "spec missing",
                  "%s was not captured -- a missing spec is not a clean audit" % p)
            continue
        audit_service(service, load(p), f)
        checked.append(service)

    return f, checked


def report(f, checked, current_dir):
    print("audited: %s" % current_dir)
    print("services: %d (%s)" % (len(checked), ", ".join(checked)))
    print()
    if f.ok:
        print("PASS: every operation documents a correct success status, an error "
              "contract, and a chosen tag.")
        return
    by_kind = {}
    for service, kind, detail in f.items:
        by_kind.setdefault(kind, []).append((service, detail))
    print("FINDINGS (%d)" % len(f.items))
    for kind in sorted(by_kind):
        print("  %s (%d)" % (kind, len(by_kind[kind])))
        for service, detail in sorted(by_kind[kind]):
            print("    [%s] %s" % (service, detail))
    print()
    print("FAIL: the document does not describe the system.")


# ----------------------------------------------------------------------
# Self-test. Same reasoning as the diff gate: a check nobody has watched fail
# gets believed when it is broken.
# ----------------------------------------------------------------------

def _mutations():
    def strip_errors(spec):
        for _, item in sorted(spec["paths"].items()):
            for _, op in sorted(_ops(item).items()):
                for code in [c for c in op.get("responses", {}) if c[:1] in ("4", "5")]:
                    del op["responses"][code]

    def unpublish_problem(spec):
        schemas = (spec.get("components") or {}).get("schemas") or {}
        schemas.pop("ProblemDetail", None)

    def revert_success_to_200(spec):
        for _, item in sorted(spec["paths"].items()):
            for _, op in sorted(_ops(item).items()):
                for code in [c for c in op.get("responses", {}) if c in ("201", "204")]:
                    op["responses"]["200"] = op["responses"].pop(code)

    def restore_default_tag(spec):
        for _, item in sorted(spec["paths"].items()):
            for _, op in sorted(_ops(item).items()):
                op["tags"] = ["product-controller"]
                return

    def drop_operation_id(spec):
        for _, item in sorted(spec["paths"].items()):
            for _, op in sorted(_ops(item).items()):
                op.pop("operationId", None)
                return

    def auto_number_operation_id(spec):
        for _, item in sorted(spec["paths"].items()):
            for _, op in sorted(_ops(item).items()):
                op["operationId"] = "getById_1"
                return

    def inline_error_schema(spec):
        for _, item in sorted(spec["paths"].items()):
            for _, op in sorted(_ops(item).items()):
                for code in [c for c in op.get("responses", {}) if c[:1] == "4"]:
                    op["responses"][code] = {"description": "nope"}
                    return

    return [
        ("errors stripped", strip_errors, "no error response documented"),
        ("ProblemDetail unpublished", unpublish_problem,
         "ProblemDetail referenced but not published"),
        ("201/204 reverted to 200", revert_success_to_200, "wrong success status"),
        ("springdoc default tag", restore_default_tag, "tag is a springdoc default"),
        ("operationId removed", drop_operation_id, "operationId missing"),
        ("operationId auto-numbered", auto_number_operation_id, "operationId auto-numbered"),
        ("error schema inlined", inline_error_schema, "error response is not a ProblemDetail"),
    ]


def self_test(current_dir):
    current_dir = Path(current_dir)
    if not (current_dir / "catalog.json").exists():
        print("SELF-TEST ERROR: no catalog.json in %s" % current_dir)
        return 2

    print("Self-test against %s (mutating catalog.json)\n" % current_dir)
    failures = []

    f, checked = run(current_dir)
    if f.ok:
        print("  ok    the real capture passes (%d services)" % len(checked))
    else:
        failures.append("the real capture does not pass")
        print("  FAIL  the real capture has %d findings" % len(f.items))
        for s, k, d in f.items[:6]:
            print("          [%s] %s: %s" % (s, k, d))

    original = load(current_dir / "catalog.json")
    for name, mutate, expected_kind in _mutations():
        spec = copy.deepcopy(original)
        mutate(spec)
        tmp = tempfile.mkdtemp()
        Path(tmp, "catalog.json").write_text(json.dumps(spec, indent=2), encoding="utf-8")
        f, _ = run(tmp, services=["catalog"])
        kinds = {k for _, k, _ in f.items}
        if expected_kind in kinds:
            print("  ok    caught: %s" % name)
        else:
            failures.append(name)
            print("  FAIL  missed: %s -- expected '%s', got %s"
                  % (name, expected_kind, sorted(kinds) or "NOTHING"))

    total = 1 + len(_mutations())
    print()
    if failures:
        print("SELF-TEST FAILED: %d/%d checks bad" % (len(failures), total))
        return 1
    print("SELF-TEST PASSED: %d/%d checks" % (total, total))
    return 0


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("current", nargs="?", default=".codex-tmp/current/openapi")
    ap.add_argument("--self-test", action="store_true",
                    help="prove the audit can fail, by mutating a captured spec")
    args = ap.parse_args(argv)

    if not Path(args.current).is_dir():
        print("ERROR: directory not found: %s" % args.current)
        return 2

    if args.self_test:
        return self_test(args.current)

    f, checked = run(args.current)
    if not checked:
        print("ERROR: no specs audited")
        return 2
    report(f, checked, args.current)
    return 0 if f.ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
