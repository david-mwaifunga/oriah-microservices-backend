#!/usr/bin/env python3
"""Additions-only OpenAPI diff gate for the section_12 refactor.

Tier 5 adds @Tag / @Operation / @ApiResponses annotations across all 12
services and introduces ResponseDto / ErrorResponseDto as documented schema
types. The acceptance bar is that the diff versus the Tier 0 baseline shows
ADDITIONS ONLY.

WHAT ACTUALLY COUPLES, measured rather than assumed
---------------------------------------------------
The Tier 5 plan justified this gate by "oriah-web consumes generated
TypeScript clients, so a renamed operationId silently renames a client
method". Reading oriah-web shows that is not how it is wired:

  * scripts/codegen.mjs runs `openapi-typescript` into
    src/lib/api/generated/<svc>.ts as a `prebuild` step;
  * that directory is gitignored (.gitignore:34) and NOTHING in src/ or
    e2e/ imports from it -- verified by grep for `from "...generated`;
  * the real client surface is HAND-WRITTEN: src/lib/api/<svc>.ts fetch
    wrappers with hardcoded path strings, validating responses against
    hand-written Zod schemas in src/lib/api/schemas/<svc>.ts whose header
    comments say they "mirror" the generated output.

So the genuine coupling is PATH STRINGS (hardcoded in the wrappers, so a
change breaks at runtime, not at build time) and RESPONSE FIELD
NAMES/TYPES (Zod `.parse` throws on a mismatch, surfacing as ApiError).
`operationId` couples to nothing today, and `tags` do not appear in
openapi-typescript output at all.

This gate still holds operationIds, for two reasons. They are the stable
identity of an operation across a spec rewrite, so an operationId change is
the cheapest available signal that a handler was renamed or re-bound; and
the generated artifact is one `import` away from being load-bearing. But
the checks are labelled so the ones that break the running frontend today
-- removed paths, removed or retyped response properties -- say so.

CLASSIFICATION
--------------
breaking: a removed path, operation, response status, parameter, schema or
schema property; a changed response/request/property schema; a parameter or
property becoming required; a removed enum value; a renamed operationId.

additive: new paths, operations, response statuses, schemas, optional
properties, optional parameters; new or changed tags, summaries,
descriptions and examples.

USAGE
    python scripts/openapi_diff.py BASELINE_DIR CURRENT_DIR
    python scripts/openapi_diff.py --self-test

Exit 0 when additions-only, 1 when anything breaking is found, 2 on a
usage/IO error.
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
]

METHODS = ("get", "put", "post", "delete", "options", "head", "patch", "trace")


class Findings:
    """Collects breaking changes and additions, grouped by service.

    A breaking finding that matches an entry in the allowlist is moved to
    `allowed` instead of `breaking`. That is deliberately a separate bucket
    rather than a silent filter: the report still prints every one, so a
    reviewer sees what was waived and can go read why.
    """

    def __init__(self, allowlist=None):
        self.breaking = []
        self.additive = []
        self.allowed = []
        self._allowlist = allowlist or set()

    def broke(self, service, kind, detail):
        # The allowlist is matched on the stable prefix of the detail -- the
        # part that identifies WHICH finding this is -- because several
        # findings append an explanatory sentence after it.
        key = (service, kind, detail.split(' -- ')[0].strip())
        if key in self._allowlist:
            self.allowed.append((service, kind, detail))
        else:
            self.breaking.append((service, kind, detail))

    def added(self, service, kind, detail):
        self.additive.append((service, kind, detail))

    @property
    def ok(self):
        return not self.breaking


def _ops(path_item):
    """The HTTP operations on a path item, ignoring $ref/parameters/servers."""
    return {m: v for m, v in path_item.items() if m in METHODS and isinstance(v, dict)}


def _schema_of(media_map):
    """The schema from a content map, preferring a concrete media type.

    springdoc emits `*/*` for a handler that declares no `produces`, and
    `application/json` once one is declared. Adding `produces` narrows the
    media type -- a change in the spec, but not a break for a client that
    was already sending `Accept: application/json`. So the schema is
    compared across whatever single media type each side offers rather than
    being keyed on the media type string.
    """
    if not isinstance(media_map, dict):
        return None, None
    for mt in ("application/json", "application/problem+json", "*/*"):
        if mt in media_map and isinstance(media_map[mt], dict):
            return mt, media_map[mt].get("schema")
    for mt, body in media_map.items():
        if isinstance(body, dict):
            return mt, body.get("schema")
    return None, None


def _norm(schema):
    """Canonical form for comparing two schemas.

    Drops documentation-only keys, so that adding @Schema(description=...)
    in Tier 5 is not reported as a breaking schema change.
    """
    if isinstance(schema, list):
        return [_norm(v) for v in schema]
    if not isinstance(schema, dict):
        return schema
    doc_only = {"description", "title", "example", "examples", "externalDocs",
                "deprecated", "readOnly", "writeOnly", "$comment"}
    return {k: _norm(v) for k, v in sorted(schema.items()) if k not in doc_only}


def _param_key(param):
    return (param.get("in"), param.get("name"))


def compare_service(service, base, cur, f):
    base_paths = base.get("paths") or {}
    cur_paths = cur.get("paths") or {}

    # ---- paths & operations -------------------------------------------
    for path, base_item in base_paths.items():
        if path not in cur_paths:
            f.broke(service, "path removed",
                    path + " -- hardcoded in oriah-web's fetch wrappers, so "
                    "this breaks at runtime rather than at build time")
            continue

        cur_item = cur_paths[path]
        base_ops, cur_ops = _ops(base_item), _ops(cur_item)

        for method, base_op in base_ops.items():
            if method not in cur_ops:
                f.broke(service, "operation removed", method.upper() + " " + path)
                continue
            compare_operation(service, method.upper() + " " + path,
                              base_op, cur_ops[method], f)

        for method in sorted(cur_ops.keys() - base_ops.keys()):
            f.added(service, "operation added", method.upper() + " " + path)

    for path in sorted(cur_paths.keys() - base_paths.keys()):
        f.added(service, "path added", path)

    # ---- component schemas --------------------------------------------
    base_schemas = ((base.get("components") or {}).get("schemas")) or {}
    cur_schemas = ((cur.get("components") or {}).get("schemas")) or {}

    for name, base_schema in base_schemas.items():
        if name not in cur_schemas:
            f.broke(service, "schema removed",
                    name + " -- mirrored by a hand-written Zod schema in "
                    "oriah-web/src/lib/api/schemas/")
            continue
        compare_schema(service, name, base_schema, cur_schemas[name], f)

    for name in sorted(cur_schemas.keys() - base_schemas.keys()):
        f.added(service, "schema added", name)


def compare_operation(service, label, base_op, cur_op, f):
    # operationId -- the identity of the operation across a spec rewrite.
    base_id = base_op.get("operationId")
    cur_id = cur_op.get("operationId")
    if base_id and cur_id != base_id:
        if cur_id is None:
            f.broke(service, "operationId removed",
                    label + " had '" + base_id + "'")
        else:
            f.broke(service, "operationId renamed",
                    label + ": '" + base_id + "' -> '" + cur_id + "' -- pin it "
                    "with @Operation(operationId = \"" + base_id + "\") unless "
                    "the rename is deliberate and coordinated with oriah-web")

    # responses
    base_resp = base_op.get("responses") or {}
    cur_resp = cur_op.get("responses") or {}
    for status, base_body in base_resp.items():
        if status not in cur_resp:
            f.broke(service, "response status removed", label + " -> " + status)
            continue
        _, base_schema = _schema_of((base_body or {}).get("content"))
        _, cur_schema = _schema_of((cur_resp[status] or {}).get("content"))
        if base_schema is not None and _norm(base_schema) != _norm(cur_schema):
            f.broke(service, "response schema changed",
                    label + " -> " + status + ": "
                    + json.dumps(_norm(base_schema)) + " -> "
                    + json.dumps(_norm(cur_schema)))
    for status in sorted(cur_resp.keys() - base_resp.keys()):
        f.added(service, "response status added", label + " -> " + status)

    # parameters
    base_params = {_param_key(p): p for p in (base_op.get("parameters") or [])}
    cur_params = {_param_key(p): p for p in (cur_op.get("parameters") or [])}
    for key, base_param in base_params.items():
        where = label + ": " + str(key[0]) + " '" + str(key[1]) + "'"
        if key not in cur_params:
            f.broke(service, "parameter removed", where)
            continue
        cur_param = cur_params[key]
        if not base_param.get("required", False) and cur_param.get("required", False):
            f.broke(service, "parameter became required",
                    where + " -- existing callers omit it")
        base_ps = _norm(base_param.get("schema"))
        if base_ps is not None and base_ps != _norm(cur_param.get("schema")):
            f.broke(service, "parameter schema changed", where)
    for key in sorted(cur_params.keys() - base_params.keys(), key=lambda k: str(k)):
        where = label + ": " + str(key[0]) + " '" + str(key[1]) + "'"
        if cur_params[key].get("required", False):
            f.broke(service, "required parameter added",
                    where + " -- existing callers do not send it")
        else:
            f.added(service, "optional parameter added", where)

    # request body
    base_rb, cur_rb = base_op.get("requestBody"), cur_op.get("requestBody")
    if base_rb:
        if not cur_rb:
            f.broke(service, "requestBody removed", label)
        else:
            if not base_rb.get("required", False) and cur_rb.get("required", False):
                f.broke(service, "requestBody became required", label)
            _, bs = _schema_of(base_rb.get("content"))
            _, cs = _schema_of(cur_rb.get("content"))
            if bs is not None and _norm(bs) != _norm(cs):
                f.broke(service, "requestBody schema changed", label)
    elif cur_rb and cur_rb.get("required", False):
        f.broke(service, "required requestBody added", label)


def compare_schema(service, name, base, cur, f):
    base_props = base.get("properties") or {}
    cur_props = cur.get("properties") or {}

    for prop, base_schema in base_props.items():
        if prop not in cur_props:
            f.broke(service, "schema property removed",
                    name + "." + prop + " -- oriah-web's Zod schema for this "
                    "type will throw on parse")
            continue
        if _norm(base_schema) != _norm(cur_props[prop]):
            f.broke(service, "schema property type changed",
                    name + "." + prop + ": " + json.dumps(_norm(base_schema))
                    + " -> " + json.dumps(_norm(cur_props[prop])))

    base_required = set(base.get("required") or [])
    cur_required = set(cur.get("required") or [])
    for prop in sorted(cur_required - base_required):
        f.broke(service, "property became required", name + "." + prop)

    for prop in sorted(cur_props.keys() - base_props.keys()):
        f.added(service, "schema property added", name + "." + prop)

    base_enum = base.get("enum")
    if isinstance(base_enum, list):
        cur_enum = cur.get("enum") or []
        for value in base_enum:
            if value not in cur_enum:
                f.broke(service, "enum value removed", name + ": '" + str(value) + "'")


def load(path):
    with open(path, encoding="utf-8-sig") as fh:
        return json.load(fh)


def load_allowlist(path):
    """Parses `service|kind|detail` lines, ignoring blanks and # comments."""
    if not path:
        return set()
    p = Path(path)
    if not p.exists():
        return set()
    out = set()
    for line in p.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = [x.strip() for x in line.split("|")]
        if len(parts) != 3:
            raise ValueError("malformed allowlist line (want service|kind|detail): " + line)
        out.add(tuple(parts))
    return out


def run(baseline_dir, current_dir, services=None, allowlist=None):
    f = Findings(allowlist)
    baseline_dir, current_dir = Path(baseline_dir), Path(current_dir)
    checked = []

    for service in (services or SERVICES):
        base_file = baseline_dir / (service + ".json")
        cur_file = current_dir / (service + ".json")
        if not base_file.exists():
            continue
        if not cur_file.exists():
            f.broke(service, "spec missing",
                    str(cur_file) + " was not captured -- rerun "
                    "capture-openapi.ps1; a missing spec is not an empty diff")
            continue
        compare_service(service, load(base_file), load(cur_file), f)
        checked.append(service)

    return f, checked


def counts(spec):
    paths = spec.get("paths") or {}
    return len(paths), sum(len(_ops(v)) for v in paths.values())


def report(f, checked, baseline_dir, current_dir):
    print("baseline: " + str(baseline_dir))
    print("current:  " + str(current_dir))
    print("compared: " + str(len(checked)) + " services (" + ", ".join(checked) + ")")
    print()

    totals = {}
    for directory, label in ((baseline_dir, "baseline"), (current_dir, "current")):
        p = o = 0
        for service in checked:
            cp, co = counts(load(Path(directory) / (service + ".json")))
            p, o = p + cp, o + co
        totals[label] = (p, o)
    bp, bo = totals["baseline"]
    cp, co = totals["current"]
    print("paths      {0} -> {1}".format(bp, cp))
    print("operations {0} -> {1}".format(bo, co))
    print()

    if f.additive:
        print("ADDITIONS ({0}) -- allowed".format(len(f.additive)))
        for service, kind, detail in sorted(f.additive):
            print("  + [{0}] {1}: {2}".format(service, kind, detail))
        print()

    if f.allowed:
        print("WAIVED ({0}) -- listed in scripts/openapi-allowed-breaks.txt"
              .format(len(f.allowed)))
        for service, kind, detail in sorted(f.allowed):
            print("  ~ [{0}] {1}: {2}".format(service, kind, detail))
        print()

    if f.breaking:
        print("BREAKING ({0}) -- the acceptance bar is additions only".format(
            len(f.breaking)))
        for service, kind, detail in sorted(f.breaking):
            print("  ! [{0}] {1}: {2}".format(service, kind, detail))
        print()
        print("FAIL: the contract regressed.")
    elif f.allowed:
        print("PASS: additions only, plus {0} waived break(s) -- read the reasons in "
              "scripts/openapi-allowed-breaks.txt.".format(len(f.allowed)))
    else:
        print("PASS: additions only.")


# ----------------------------------------------------------------------
# Self-test. A gate that cannot fail is worse than no gate, because it is
# believed -- this project has already shipped one such gate (the Tempo
# trace assertion that treated `{"trace":{}}` as success). So each mutation
# below is applied to the real baseline and MUST be caught, and each
# additive mutation MUST be allowed through.
# ----------------------------------------------------------------------

def _breaking_mutations():
    """(name, mutator, expected_kind) -- each must be reported breaking."""

    def drop_path(spec):
        del spec["paths"][sorted(spec["paths"])[0]]

    def drop_operation(spec):
        for _, item in sorted(spec["paths"].items()):
            ops = _ops(item)
            if ops:
                del item[sorted(ops)[0]]
                return

    def rename_operation_id(spec):
        for _, item in sorted(spec["paths"].items()):
            for _, op in sorted(_ops(item).items()):
                if "operationId" in op:
                    op["operationId"] = op["operationId"] + "Renamed"
                    return

    def drop_response_status(spec):
        for _, item in sorted(spec["paths"].items()):
            for _, op in sorted(_ops(item).items()):
                if op.get("responses"):
                    del op["responses"][sorted(op["responses"])[0]]
                    return

    def change_response_schema(spec):
        for _, item in sorted(spec["paths"].items()):
            for _, op in sorted(_ops(item).items()):
                for _, body in sorted((op.get("responses") or {}).items()):
                    mt, schema = _schema_of((body or {}).get("content"))
                    if mt and isinstance(schema, dict):
                        body["content"][mt]["schema"] = {"type": "string"}
                        return

    def drop_schema(spec):
        schemas = (spec.get("components") or {}).get("schemas") or {}
        if schemas:
            del schemas[sorted(schemas)[0]]

    def drop_schema_property(spec):
        schemas = (spec.get("components") or {}).get("schemas") or {}
        for _, schema in sorted(schemas.items()):
            props = schema.get("properties") or {}
            if props:
                del props[sorted(props)[0]]
                return

    def require_schema_property(spec):
        schemas = (spec.get("components") or {}).get("schemas") or {}
        for _, schema in sorted(schemas.items()):
            props = schema.get("properties") or {}
            unrequired = sorted(set(props) - set(schema.get("required") or []))
            if unrequired:
                schema["required"] = sorted(
                    set(schema.get("required") or []) | {unrequired[0]})
                return

    def add_required_parameter(spec):
        for _, item in sorted(spec["paths"].items()):
            for _, op in sorted(_ops(item).items()):
                op.setdefault("parameters", []).append(
                    {"in": "query", "name": "tenantId", "required": True,
                     "schema": {"type": "string"}})
                return

    def drop_parameter(spec):
        for _, item in sorted(spec["paths"].items()):
            for _, op in sorted(_ops(item).items()):
                if op.get("parameters"):
                    op["parameters"].pop(0)
                    return

    return [
        ("removed path", drop_path, "path removed"),
        ("removed operation", drop_operation, "operation removed"),
        ("renamed operationId", rename_operation_id, "operationId renamed"),
        ("removed response status", drop_response_status, "response status removed"),
        ("changed response schema", change_response_schema, "response schema changed"),
        ("removed component schema", drop_schema, "schema removed"),
        ("removed schema property", drop_schema_property, "schema property removed"),
        ("property became required", require_schema_property, "property became required"),
        ("added required parameter", add_required_parameter, "required parameter added"),
        ("removed parameter", drop_parameter, "parameter removed"),
    ]


def _additive_mutations():
    """(name, mutator) -- each must come back additions-only."""

    def add_path(spec):
        spec["paths"]["/api/v1/contact-info"] = {
            "get": {"operationId": "fetchContactInfo",
                    "responses": {"200": {"description": "OK", "content": {
                        "application/json": {"schema": {
                            "$ref": "#/components/schemas/ContactInfo"}}}}}}}

    def add_response_status(spec):
        for _, item in sorted(spec["paths"].items()):
            for _, op in sorted(_ops(item).items()):
                if op.get("responses") and "404" not in op["responses"]:
                    op["responses"]["404"] = {
                        "description": "Not Found", "content": {
                            "application/problem+json": {"schema": {
                                "$ref": "#/components/schemas/ProblemDetail"}}}}
                    return

    def add_schema(spec):
        spec.setdefault("components", {}).setdefault("schemas", {})["ErrorResponse"] = {
            "type": "object", "properties": {"apiPath": {"type": "string"}}}

    def add_optional_property(spec):
        schemas = (spec.get("components") or {}).get("schemas") or {}
        for _, schema in sorted(schemas.items()):
            if schema.get("properties") is not None:
                schema["properties"]["slug"] = {"type": "string"}
                return

    def add_optional_parameter(spec):
        for _, item in sorted(spec["paths"].items()):
            for _, op in sorted(_ops(item).items()):
                op.setdefault("parameters", []).append(
                    {"in": "query", "name": "includeArchived", "required": False,
                     "schema": {"type": "boolean"}})
                return

    def add_tag_and_descriptions(spec):
        for _, item in sorted(spec["paths"].items()):
            for _, op in sorted(_ops(item).items()):
                op["tags"] = ["Products"]
                op["summary"] = "Documented in Tier 5"
                op["description"] = "Long-form description added by @Operation"
        schemas = (spec.get("components") or {}).get("schemas") or {}
        for _, schema in sorted(schemas.items()):
            schema["description"] = "Documented by @Schema in Tier 5"
            for _, prop in sorted((schema.get("properties") or {}).items()):
                if isinstance(prop, dict):
                    prop["description"] = "Documented by @Schema in Tier 5"

    def pin_existing_operation_id(spec):
        # @Operation(operationId = "<the id springdoc already generated>")
        # must be a no-op. This is how Tier 5 pins ids WITHOUT a rename, and
        # if this check ever fails the whole pinning strategy is unsound.
        for _, item in sorted(spec["paths"].items()):
            for _, op in sorted(_ops(item).items()):
                if "operationId" in op:
                    op["operationId"] = str(op["operationId"])
                    return

    def narrow_media_type(spec):
        # Declaring `produces = application/json` narrows springdoc's `*/*`.
        # Not a break for a client already sending Accept: application/json.
        for _, item in sorted(spec["paths"].items()):
            for _, op in sorted(_ops(item).items()):
                for _, body in sorted((op.get("responses") or {}).items()):
                    content = (body or {}).get("content") or {}
                    if "*/*" in content:
                        content["application/json"] = content.pop("*/*")
                        return

    return [
        ("new path", add_path),
        ("new response status", add_response_status),
        ("new component schema", add_schema),
        ("new optional property", add_optional_property),
        ("new optional parameter", add_optional_parameter),
        ("@Tag + @Operation summaries + @Schema descriptions", add_tag_and_descriptions),
        ("operationId pinned to its existing value", pin_existing_operation_id),
        ("media type narrowed from */* to application/json", narrow_media_type),
    ]


def self_test(baseline_dir):
    baseline_dir = Path(baseline_dir)
    specs = sorted(baseline_dir.glob("*.json"))
    if not specs:
        print("SELF-TEST ERROR: no baseline specs in " + str(baseline_dir))
        return 2

    service = "catalog" if (baseline_dir / "catalog.json").exists() else specs[0].stem
    print("Self-test against " + str(baseline_dir)
          + " (mutating " + service + ".json)")
    print()

    failures = []

    # 1. Identity: the baseline against itself must be completely clean.
    f, checked = run(baseline_dir, baseline_dir)
    if f.breaking or f.additive:
        failures.append("identity diff was not clean")
        print("  FAIL  identity (baseline vs itself): {0} breaking, {1} additive"
              .format(len(f.breaking), len(f.additive)))
        for s, k, d in (f.breaking + f.additive)[:5]:
            print("          [{0}] {1}: {2}".format(s, k, d))
    else:
        print("  ok    identity (baseline vs itself) -- {0} services, no diff"
              .format(len(checked)))

    original = load(baseline_dir / (service + ".json"))

    def with_mutated(mutate):
        spec = copy.deepcopy(original)
        mutate(spec)
        tmp = tempfile.mkdtemp()
        Path(tmp, service + ".json").write_text(
            json.dumps(spec, sort_keys=True, indent=2), encoding="utf-8")
        return run(baseline_dir, tmp, services=[service])

    # 2. Every breaking mutation must be caught, under the right label.
    for name, mutate, expected_kind in _breaking_mutations():
        f, _ = with_mutated(mutate)
        kinds = {k for _, k, _ in f.breaking}
        if expected_kind in kinds:
            print("  ok    caught: " + name)
        else:
            failures.append(name + ": expected '" + expected_kind + "'")
            print("  FAIL  missed: " + name + " -- expected '" + expected_kind
                  + "', got " + str(sorted(kinds) or "NOTHING"))

    # 3. Every additive mutation must pass clean.
    for name, mutate in _additive_mutations():
        f, _ = with_mutated(mutate)
        if f.breaking:
            failures.append(name + ": wrongly reported breaking")
            print("  FAIL  false alarm: " + name)
            for s, k, d in f.breaking[:3]:
                print("          [{0}] {1}: {2}".format(s, k, d))
        else:
            print("  ok    allowed: " + name)

    # The allowlist itself has to be checked: it must waive exactly the finding
    # it names and leave every other one failing. An allowlist that swallows
    # more than its line says is the way a waiver quietly becomes a blanket.
    first_path = sorted(original["paths"])[0]
    waived_key = (service, "path removed", first_path)

    f, _ = with_mutated(lambda spec: spec["paths"].pop(first_path))
    if f.breaking and not f.allowed:
        print("  ok    a removal is breaking with no allowlist")
    else:
        failures.append("removal was not breaking without an allowlist")
        print("  FAIL  a removal should be breaking with no allowlist")

    spec = copy.deepcopy(original)
    spec["paths"].pop(first_path)
    tmp = tempfile.mkdtemp()
    Path(tmp, service + ".json").write_text(
        json.dumps(spec, sort_keys=True, indent=2), encoding="utf-8")

    f, _ = run(baseline_dir, tmp, services=[service], allowlist={waived_key})
    if f.ok and len(f.allowed) == 1:
        print("  ok    the allowlist waives exactly its own entry")
    else:
        failures.append("allowlist did not waive its entry")
        print("  FAIL  allowlist: breaking=%d allowed=%d (wanted 0 and 1)"
              % (len(f.breaking), len(f.allowed)))

    # ...and an unrelated entry must waive nothing.
    f, _ = run(baseline_dir, tmp, services=[service],
               allowlist={(service, "path removed", "/not/this/one")})
    if f.breaking and not f.allowed:
        print("  ok    an unrelated allowlist entry waives nothing")
    else:
        failures.append("allowlist matched something it does not name")
        print("  FAIL  an unrelated allowlist entry waived a finding")

    total = 4 + len(_breaking_mutations()) + len(_additive_mutations())
    print()
    if failures:
        print("SELF-TEST FAILED: {0}/{1} checks bad".format(len(failures), total))
        return 1
    print("SELF-TEST PASSED: {0}/{0} checks".format(total))
    return 0


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("baseline", nargs="?", default=".codex-tmp/baseline/openapi")
    ap.add_argument("current", nargs="?", default=".codex-tmp/current/openapi")
    ap.add_argument("--self-test", action="store_true",
                    help="prove the gate can fail, by mutating the baseline")
    ap.add_argument("--allow", default=str(Path(__file__).with_name("openapi-allowed-breaks.txt")),
                    help="file of deliberate exceptions (service|kind|detail per line)")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test(args.baseline)

    if not Path(args.baseline).is_dir():
        print("ERROR: baseline dir not found: " + args.baseline)
        return 2
    if not Path(args.current).is_dir():
        print("ERROR: current dir not found: " + args.current)
        print("Capture it first:")
        print("  powershell -File .\\scripts\\capture-openapi.ps1 -OutDir "
              + args.current)
        return 2

    try:
        allowlist = load_allowlist(args.allow)
    except ValueError as exc:
        print("ERROR: " + str(exc))
        return 2

    f, checked = run(args.baseline, args.current, allowlist=allowlist)
    if not checked:
        print("ERROR: no services compared -- check the directories")
        return 2
    report(f, checked, args.baseline, args.current)
    return 0 if f.ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
