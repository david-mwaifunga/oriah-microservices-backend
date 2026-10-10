#!/usr/bin/env python3
"""Every role the code guards on must be a role the realm actually declares.

WHY THIS IS A GUARD AND NOT A CONSTANT
--------------------------------------
Role names are duplicated heavily -- `hasRole('USER')` appears 12 times across
the services and `ROLE_USER` 51 times across their tests. The obvious fix is a
shared constant, and the course's answer is a per-service `XxxConstants`. Both
miss the actual failure mode.

The realm is the source of truth, and it lives in a different file in a
different language: `keycloak/import/oriah-realm.json`. A Java constant cannot
be wrong about itself; it can only be wrong about the realm. So the thing worth
building is the cross-language check, and once that exists the duplication is
harmless -- it cannot drift without failing.

WHAT GOES WRONG WITHOUT IT
--------------------------
A typo'd role fails **closed**. `@PreAuthorize("hasRole('CATALOG_ADMN')")`
throws no error, logs nothing, and compiles fine: the endpoint simply becomes
uncallable by anyone, forever, and the only symptom is a 403 that looks like
correct behaviour. The same typo in a test grants an authority nobody can hold,
so a test asserting 200 would pass against a rule no real token can satisfy.

Renaming a role in the realm has the same shape from the other direction, and
the realm file is edited by hand.

CHECKS
  1. every role named in MAIN sources is declared in the realm        (FAIL)
  2. realm roles that no code guards on                               (report)

MAIN SOURCES ONLY, and that is a considered limit rather than an oversight.
The first version also scanned tests, and of its six findings five were
legitimate: the converter and auditor unit tests exercise generic machinery
with deliberately arbitrary authority names (`ROLE_X`, `ROLE_ADMIN`,
`ROLE_ANONYMOUS`), and catalog's 403 test grants a non-admin role on purpose.
Worse, one finding came from PROSE INSIDE AN ASSERTION MESSAGE --
`.as("the 12 services all guard with hasRole('X') ...")` -- which no amount of
comment stripping can tell apart from a rule.

In main sources that ambiguity does not arise: a `hasRole('...')` string
literal there IS the SpEL rule, because that is exactly how `@PreAuthorize`
takes it. Prose about roles lives in comments, which are stripped.

The failure mode a test-side check would have caught -- a test granting a role
no real token can hold, so an assertion passes against a rule production cannot
satisfy -- is real but cannot be distinguished statically from the legitimate
cases above. A guard with a five-in-six false-positive rate is a guard people
switch off, so it is not included.

Comparison is CASE-INSENSITIVE. The realm declares `offline_access` lowercase
while KeycloakRoleConverter emits `ROLE_` + uppercase, so a case-sensitive
check reported the realm's own builtin roles as unknown.

USAGE
    python scripts/validate_roles.py [REPO_ROOT]
    python scripts/validate_roles.py --self-test [REPO_ROOT]

Exit 0 on pass, 1 on any finding, 2 on a usage/IO error.
"""

import argparse
import json
import re
import sys
from pathlib import Path

# Roles Keycloak manages itself. Code never guards on these, and they are not
# interesting in the "guards nothing" report.
BUILTIN_ROLES = {"default-roles-oriah", "offline_access", "uma_authorization"}

# Roles that exist in the realm on purpose but guard nothing yet. Recorded
# rather than reported every run: the Tier 3 sweep established that
# /api/v1/admin/payments has no admin surface, so PAYMENT_ADMIN is provisioned
# ahead of one. Listed here so a NEW unused role still shows up.
KNOWN_UNUSED = {"PAYMENT_ADMIN"}

LINE_COMMENT = re.compile(r"//[^\n]*")
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)

# hasRole('X') / hasRole("X") in SpEL or Java, and hasAuthority('ROLE_X')
HAS_ROLE = re.compile(r"""hasRole\(\s*["']([A-Za-z0-9_]+)["']\s*\)""")
HAS_AUTHORITY = re.compile(r"""hasAuthority\(\s*["']ROLE_([A-Za-z0-9_]+)["']\s*\)""")


def strip_comments(src):
    """Blanks comments while PRESERVING line numbers.

    Replacing a multi-line javadoc with a single space collapses the file and
    every reported line number after it is wrong -- which is how the first run
    of this script pointed at line 28 for a match that was really on line 42.
    Each comment is replaced by its own newlines instead.
    """
    def blank(m):
        return "\n" * m.group(0).count("\n")

    return LINE_COMMENT.sub("", BLOCK_COMMENT.sub(blank, src))


def realm_roles(realm_file):
    with open(realm_file, encoding="utf-8") as fh:
        realm = json.load(fh)
    return {r["name"] for r in (realm.get("roles") or {}).get("realm") or []}


def scan(root, declared):
    """Returns (findings, used_roles) over MAIN sources only."""
    findings, used = [], set()
    declared_ci = {d.upper() for d in declared}

    for java in sorted(Path(root).rglob("*.java")):
        parts = java.parts
        if "target" in parts or "test" in parts:
            continue

        src = strip_comments(java.read_text(encoding="utf-8", errors="replace"))
        rel = str(java.relative_to(root)).replace("\\", "/")

        for m in list(HAS_ROLE.finditer(src)) + list(HAS_AUTHORITY.finditer(src)):
            name = m.group(1)
            used.add(name)
            if name.upper() not in declared_ci:
                line = src[:m.start()].count("\n") + 1
                findings.append(
                    (rel, line, name,
                     "guards on a role the realm does not declare. A role check "
                     "against a name that does not exist fails CLOSED: the "
                     "endpoint becomes uncallable by anyone, and the only symptom "
                     "is a 403 that looks like correct behaviour."))

    return findings, used


def report(declared, findings, used):
    print("realm roles:  %s" % ", ".join(sorted(declared - BUILTIN_ROLES)))
    print("code guards:  %s" % ", ".join(sorted(used)))
    print()

    used_ci = {u.upper() for u in used}
    unused = {r for r in (declared - BUILTIN_ROLES) - KNOWN_UNUSED
              if r.upper() not in used_ci}
    if unused:
        print("REPORT: %d realm role(s) guard nothing: %s"
              % (len(unused), ", ".join(sorted(unused))))
        print("  Not a failure -- a role can be provisioned before its endpoints "
              "exist. PAYMENT_ADMIN is already known and recorded that way.")
        print()

    if not findings:
        print("PASS: every role the code guards on is declared in the realm.")
        return True

    print("FAIL: %d role(s) do not match the realm" % len(findings))
    for rel, line, name, why in findings:
        print("  ! %s:%d  '%s' %s" % (rel, line, name, why))
    return False


def run(root, realm_file):
    declared = realm_roles(realm_file)
    findings, used = scan(root, declared)
    return declared, findings, used


def self_test(root, realm_file):
    """A guard nobody has watched fail gets believed when it is broken."""
    import tempfile
    import shutil

    print("Self-test\n")
    failures = []

    declared, findings, used = run(root, realm_file)
    if not findings:
        print("  ok    the real tree passes")
    else:
        failures.append("the real tree does not pass")
        print("  FAIL  the real tree has %d finding(s)" % len(findings))
        for rel, line, name, _ in findings[:5]:
            print("          %s:%d '%s'" % (rel, line, name))

    tmp = Path(tempfile.mkdtemp())
    try:
        # a typo'd guard in main code
        d = tmp / "src" / "main" / "java"
        d.mkdir(parents=True)
        (d / "Bad.java").write_text(
            '@PreAuthorize("hasRole(\'CATALOG_ADMN\')") class Bad {}', encoding="utf-8")
        _, fs, _ = run(tmp, realm_file)
        if any(n == "CATALOG_ADMN" for _, _, n, _ in fs):
            print("  ok    caught: a typo'd role in main code")
        else:
            failures.append("missed a typo'd main-code role")
            print("  FAIL  missed: a typo'd role in main code")

        # a comment must NOT be scanned -- KeycloakRoleConverter's javadoc
        # explains the prefix convention with a literal hasRole("X").
        (d / "Bad.java").write_text(
            '/** use hasRole("X") without the prefix */ class Ok {}', encoding="utf-8")
        _, fs, _ = run(tmp, realm_file)
        if not fs:
            print("  ok    ignored: hasRole in a javadoc comment")
        else:
            failures.append("scanned a comment")
            print("  FAIL  scanned a javadoc comment as a rule")

        # line numbers must survive a multi-line comment above the match
        (d / "Bad.java").write_text(
            "/**\n * a\n * javadoc\n * spanning lines\n */\n"
            "@PreAuthorize(\"hasRole('NOPE')\")\nclass Bad {}", encoding="utf-8")
        _, fs, _ = run(tmp, realm_file)
        if fs and fs[0][1] == 6:
            print("  ok    line numbers survive a multi-line comment")
        else:
            failures.append("line number wrong after a comment")
            print("  FAIL  expected line 6, got %s"
                  % (fs[0][1] if fs else "no finding"))

        # a realm role differing only in case must NOT be reported
        (d / "Bad.java").write_text(
            '@PreAuthorize("hasRole(\'OFFLINE_ACCESS\')") class Ok2 {}', encoding="utf-8")
        _, fs, _ = run(tmp, realm_file)
        if not fs:
            print("  ok    case-insensitive against the realm's lowercase builtins")
        else:
            failures.append("case-sensitive comparison")
            print("  FAIL  reported a realm role that differs only in case")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print()
    if failures:
        print("SELF-TEST FAILED: %d/5 checks bad" % len(failures))
        return 1
    print("SELF-TEST PASSED: 5/5 checks")
    return 0


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("root", nargs="?", default=".",
                    help="repo root to scan (default: cwd)")
    ap.add_argument("--realm", default=None,
                    help="realm JSON (default: <root>/keycloak/import/oriah-realm.json)")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)

    root = Path(args.root)
    realm_file = Path(args.realm) if args.realm else root / "keycloak" / "import" / "oriah-realm.json"

    if not root.is_dir():
        print("ERROR: root not found: %s" % root)
        return 2
    if not realm_file.exists():
        print("ERROR: realm file not found: %s" % realm_file)
        return 2

    if args.self_test:
        return self_test(root, realm_file)

    declared, findings, used = run(root, realm_file)
    return 0 if report(declared, findings, used) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
