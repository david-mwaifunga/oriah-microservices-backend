#!/usr/bin/env python3
"""Service registry guard: every domain service is wired into every list that names the services.

WHY THIS EXISTS
---------------
Adding a service to this repo means touching about twenty files that each keep
their OWN list of the services: the compose file, the image build, Prometheus,
the dev-stack scripts, the OpenAPI tools, the Postman and pgAdmin files, the
smoke test, the IntelliJ run configurations, the gateway's Swagger list and the
config repo. Forget one and nothing breaks loudly. For example, a service left
out of the smoke test's port table still ends the run green, printing "24/24
probe endpoints UP" for twelve services, because the number is simply wrong.

So the lists are checked here, against ONE source of truth: the service
directories under services/ (each holds a pom.xml), which must equal the
<module> list in services/pom.xml. Everything else is derived from each
service's OWN files:

  * its port         <- src/main/resources/application.yaml (server.port)
  * owns a database  <- at least one Flyway migration AND, in its pom, a direct
                        spring-boot-starter-flyway dependency that is not test
                        or provided scope (a folder alone proves nothing, and
                        neither does a pom that merely has "flyway" in an
                        artifact name)
  * its database     <- application-local.yaml (host port, database, user,
                        password) and application-docker.yaml (host name)
  * its main class   <- the class annotated @SpringBootApplication

A service that owns no database (shipping and checkout today) is therefore
expected to have NO postgres-<name> container, NO pgAdmin entry and so on,
without any hand-kept "these are stateless" list in this file.

The checks are EXACT, not "is it mentioned somewhere": each list must hold every
service, with the right port, and nothing extra and nothing twice.

A COMMENT IS NOT AN ENTRY. Taking a service out of a list "for now" usually means
commenting its entry out, so every watched file is read with its comments removed
(strip_comments: PowerShell and Python "#", <# #>, YAML "#", XML <!-- -->, Java
// and /* */; one-line strings are left alone, so a "#" inside a URL or a value
survives). NEITHER IS A MULTI-LINE STRING: the body of a Python triple-quoted
string, a PowerShell here-string (@' '@, @" "@) and a Java text block is blanked
too (delimiters and line breaks kept), so an entry moved into a docstring does
not count, and an old copy of a whole list quoted in a docstring above the real
one is not found first. No watched list is written as a multi-line string.
Likewise a flyway artifact only counts as a dependency when it is a real,
direct <dependency> of the service that runs the migrations (has_main_flyway):
spring-boot-starter-flyway, in a scope other than test and provided. Spring
Boot 4 turns Flyway on only through that starter (its auto-configuration lives in
spring-boot-flyway, which the starter brings), so bare flyway-core does NOT make
a service run its migrations. The -test starter,
flyway-database-postgresql on its own (the driver module, not a switch) and
anything inside a comment do not make a service a database owner. A config-repo
file counts only when it holds at least one top-level key (an empty file, or one
of nothing but comments, serves the service nothing).

USAGE
    python scripts/check_service_registry.py              # check the tree
    python scripts/check_service_registry.py --selftest   # prove the guard can fail

Exit 0 when every list agrees, 1 on any problem (one line per problem: the
service, the list and the file), 2 on a usage error.

THE SELF-TEST
-------------
A guard that cannot fail is worse than none, because it is believed. --selftest
copies the tree to a temp folder and, for every service and every watched list,
removes that service's entry (and, for ports, corrupts the port), then runs
this script as a SUBPROCESS on the damaged copy and requires a non-zero exit
and a new problem line naming that service and that list. It also proves the
guard exits 0 on an untouched copy. The damage comes in kinds, counted in the
summary: remove (the entry is deleted), comment (the entry is commented out,
in every list whose file can hold a comment, and the whole config-repo file),
port / health (a wrong number), empty (a config-repo file with nothing in it),
herestring / docstring / textblock (the entry moved into a
PowerShell here-string, a Python triple-quoted string or a Java text block, in
every list of a file of that language; docstring-copy quotes an old copy of the
whole list above the real one, which lost the entry), and flyway-test-only /
flyway-commented / flyway-no-starter / flyway-provided / flyway-test-unscoped
(the service's own flyway dependencies are taken away leaving only the test-scope
one, are wrapped in a comment, lose only the starter, go provided, or leave an
un-scoped -test starter behind).

The part that damages the files (the MUTATOR, below the line marked "SELF-TEST")
shares NO parsing code with the guard above that line: it uses plain line
deletion and text substitution. If both used the same parser, one blind spot
would hide a missing entry from both of them.

KNOWN GAPS
----------
Six ways to read a file differently from its own language, or to count prose as
an entry, are known and NOT closed, on purpose:

  (a) XML CDATA sections are not blanked in has_main_flyway. A <dependency>
      written inside <![CDATA[ ... ]]> in a pom is text to Maven but a
      dependency to this guard.
  (b) The PowerShell line-comment rule (a "#" starts a comment only when the
      character before it is not a word character, "$", ":", "/", "\\", "." or
      "-") disagrees with PowerShell's own parser for a "#" glued to the end of
      a token: in "search = 8092#; procurement = 8093" PowerShell reads
      "#; procurement = 8093" as a comment, this guard does not strip it.
  (c) The PowerShell double-quote rule does not understand "$( )" nesting with a
      quote of the OTHER kind inside: in "a $('it"s') b" # c the string is
      closed one quote too early, so the quote pairing after it is off by one
      and a trailing comment can be left in (or a "#" inside a string taken as
      one).
  (d) An entry written INSIDE an ordinary string still counts as an element: the
      list readers take any quoted word, so `'skip "procurement" for now'` in
      $JavaApps reads as a procurement entry. An ordinary string that runs over a
      line break is NOT blanked either. That was tried and removed: together
      with (c) it turned valid PowerShell into a hard false red that blocks
      honest work, while hiding an entry that way takes a deliberate, odd edit.
      (Here-strings, Python triple-quoted strings and Java text blocks, which
      are written to hold prose, ARE blanked.)
  (e) A flyway starter that exists only inside a <profiles> block of a pom counts
      as a live dependency, although Maven puts it on the classpath only when
      the profile is active.
  (f) A config-repo file is held to block-style YAML with an unindented key, so
      a valid file written as a flow mapping, or indented as a whole, is refused
      with "holds no top-level key" (loud, never silent).

Why they are accepted: this guard exists to catch an honestly forgotten entry,
not an entry hidden on purpose, and each of the six needs an edit nobody
makes by accident (or fails loudly, which (f) does). On the REAL tree the stripper agrees exactly with the
languages' own tokenizers, and that is differential-tested rather than assumed:
every comment of every watched .ps1 is compared with PowerShell's own parser
(System.Management.Automation.Language.Parser), every comment and every
triple-quoted string of the .py files with Python's tokenize, and every comment
and text block of the repo's .java files with an independent Java lexer, with no
difference (the test harness is not part of the repo). Run such a comparison
again when a new kind of watched file, or a new quoting style in one, appears.
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
from collections import Counter, OrderedDict, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve()
DEFAULT_ROOT = HERE.parent.parent          # oriah-api/

# ---- the watched files (relative to oriah-api/) ----------------------------
COMPOSE = "docker-compose/default/docker-compose.yml"
BUILD_IMAGES = "build-images.ps1"
PROMETHEUS = "monitoring/prometheus.yml"
DEV_STACK = "scripts/dev-stack.ps1"
LOCAL_TEST = "scripts/local-dev-test.ps1"
CAPTURE = "scripts/capture-openapi.ps1"
AUDIT = "scripts/openapi_audit.py"
DIFF = "scripts/openapi_diff.py"
POSTMAN_GEN = "scripts/local-dev/postman_from_openapi.py"
POSTMAN_ENV = "local-dev/postman/oriah-local.postman_environment.json"
PGADMIN = "local-dev/pgadmin-servers.json"
SMOKE = "smoke-test.ps1"
RUN_ALL = ".run/2-services-local.run.xml"
GATEWAY_YAML = "infra/gatewayserver/src/main/resources/application.yaml"
GATEWAY_ROUTES_DIR = "infra/gatewayserver/src/main/java"

# ---- the few things that cannot be derived (kept minimal on purpose) -------
# Keycloak's own database is in the compose file but is no domain service's.
NON_SERVICE_POSTGRES = {"keycloak"}
# The storefront image is built from the sibling oriah-web repo.
NON_MODULE_IMAGES = {"oriah-web"}
# Every domain service extends the same compose base, and every database the
# same Postgres base. These are house rules, not facts about one service.
DOMAIN_BASE = "microservice-keycloak-config"
POSTGRES_BASE = "postgres-base-config"

LIST_IDS = [
    "pom-modules", "service-facts",
    "compose-service", "compose-postgres", "compose-volume",
    "build-images", "prometheus",
    "dev-stack-apps", "dev-stack-backing",
    "local-test-platform", "local-test-services", "local-test-ports",
    "local-test-postgres", "local-test-nodb",
    "capture-openapi", "openapi-audit", "openapi-diff",
    "postman-platform", "postman-services", "postman-env", "pgadmin",
    "smoke-routeprobes", "smoke-expected", "smoke-ports",
    "run-compound", "run-file",
    "gateway-docs", "gateway-swagger", "config-repo",
]


# ===========================================================================
# Reporting
# ===========================================================================
class Report:
    def __init__(self):
        self._items = set()

    def add(self, service, list_id, path, detail):
        self._items.add((service, list_id, path, detail))

    def lines(self):
        return ["PROBLEM [%s] %s (%s): %s" % item for item in sorted(self._items)]

    def __len__(self):
        return len(self._items)


class Svc:
    """What one module's own files say about it."""

    def __init__(self, name):
        self.name = name
        self.port = None
        self.migrations = []
        self.flyway = False
        self.owns_db = False
        self.db_host_port = None
        self.db_name = None
        self.db_user = None
        self.db_password = None
        self.docker_db_host = None
        self.docker_db_name = None
        self.main_class = None


class Ctx:
    def __init__(self, root):
        self.root = Path(root).resolve()
        self.cfg = self.root.parent / "config-repo"
        self.services = OrderedDict()   # name -> Svc, domain services
        self.infra = OrderedDict()      # name -> Svc, platform modules
        self.modules = []               # <module> entries of services/pom.xml

    def read(self, rel):
        p = self.root / rel
        if not p.is_file():
            return None
        return read_code(p)

    @property
    def owners(self):
        return [n for n, s in self.services.items() if s.owns_db]

    @property
    def stateless(self):
        return [n for n, s in self.services.items() if not s.owns_db]

    def port_of(self, name):
        s = self.services.get(name) or self.infra.get(name)
        return s.port if s else None


def need(ctx, rep, list_id, rel):
    text = ctx.read(rel)
    if text is None:
        rep.add("(all)", list_id, rel, "watched file not found")
    return text


# ===========================================================================
# Small readers shared by the checks
# ===========================================================================
def yaml_child(text, parent, child):
    """The value of `child:` written directly under top-level `parent:`.

    A plain indentation scan, enough for the flat application.yaml shapes here.
    (A regex for "port:" alone would also catch redis.port, a different thing.)
    """
    in_parent = False
    child_indent = None
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        indent = len(line) - len(line.lstrip(" "))
        if indent == 0:
            in_parent = re.match(re.escape(parent) + r"\s*:\s*(#.*)?$", line) is not None
            child_indent = None
            continue
        if in_parent:
            if child_indent is None:
                child_indent = indent
            if indent == child_indent:
                m = re.match(r"\s*" + re.escape(child) + r"\s*:\s*(\S+)", line)
                if m:
                    return m.group(1)
    return None


def find_main_class(mod_dir):
    src = mod_dir / "src" / "main" / "java"
    if not src.is_dir():
        return None
    # Look at the files named *Application.java first: reading every source
    # file of every module made this guard take seconds, and it runs at the
    # start of the smoke test and hundreds of times in --selftest.
    files = [Path(d) / f for d, _, names in os.walk(str(src)) for f in names if f.endswith(".java")]
    files.sort(key=lambda p: (not p.name.endswith("Application.java"), str(p)))
    for f in files:
        t = strip_comments(f.read_text(encoding="utf-8", errors="replace"), "java")
        if "@SpringBootApplication" in t:
            pkg = re.search(r"^package\s+([\w.]+)\s*;", t, re.M)
            return (pkg.group(1) + "." if pkg else "") + f.stem
        if not f.name.endswith("Application.java"):
            break          # the *Application.java files were all checked; do not read the rest
    return None


def module_dirs(base):
    if not base.is_dir():
        return []
    return sorted(d.name for d in base.iterdir() if d.is_dir() and (d / "pom.xml").is_file())


def pom_modules(text):
    # (read through read_code(), so the comments are already gone; kept for a raw string)
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    return re.findall(r"<module>\s*([^<\s]+)\s*</module>", text)


# ---- comments are not entries -------------------------------------------------
# Every list below is read from a file that people edit by hand, and the usual
# way to take a service out of a list "for now" is to comment its entry out. A
# comment must therefore never count as an entry: every watched file is read
# through strip_comments() first, and only then searched.
#
# The scan is token based, not a bare "from # to the end of the line", because a
# "#" or a "//" or a "/*" can sit inside a string: a URL fragment, a compose
# value, the gateway path "/api/v1/**". Strings are therefore matched first
# and left alone; only what is left outside a string and starts a comment goes.
#
# A MULTI-LINE STRING IS NOT AN ENTRY EITHER. A Python triple-quoted string, a
# PowerShell here-string and a Java text block are written to hold prose or a
# sample (a docstring that quotes the old list, say), never one of the watched
# lists, and no watched list is written as one. Left in, they let an entry that
# is only text pass as a live one ("procurement", moved into a docstring, still
# reads as a list element), and let an old copy of a whole list sit above the
# real one, where the first-match search of region() would pick it. Their
# BODY is therefore blanked (delimiters and line breaks kept) before any
# search; ordinary strings are untouched, including one that runs over a line
# break (see KNOWN GAPS for why that is not blanked).
_PS_TOKENS = re.compile(r"""
      (?P<block><\#.*?\#>)
    | (?P<here>@(?P<q>['"])[ \t]*\r?\n(?P<hbody>(?:.*?\r?\n)??)(?P=q)@)
    | (?P<str>'[^']*(?:''[^']*)*'|"(?:`.|[^"`])*")
    | (?P<line>(?<![\w$:/\\.-])\#[^\r\n]*)
""", re.X | re.S)

_PY_TOKENS = re.compile(r"""
      (?P<tstr>'''(?:\\.|[^\\])*?'''|\"\"\"(?:\\.|[^\\])*?\"\"\")
    | (?P<str>'(?:\\.|[^'\\\n])*'|"(?:\\.|[^"\\\n])*")
    | (?P<line>\#[^\r\n]*)
""", re.X | re.S)

_JAVA_TOKENS = re.compile(r"""
      (?P<tstr>\"\"\"(?:\\.|[^\\])*?\"\"\")
    | (?P<str>"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])+')
    | (?P<block>/\*.*?\*/)
    | (?P<line>//[^\r\n]*)
""", re.X | re.S)


def _blank(body):
    """`body` with every character gone except its line breaks (lines stay lines)."""
    return re.sub(r"[^\r\n]", "", body)


def _drop_comments(pattern, text):
    """Comments go, multi-line strings keep only their delimiters, one-line strings stay.

    A block comment becomes a blank, a line comment nothing; a triple-quoted
    string, here-string or text block keeps its opening and closing delimiter
    and its line breaks but loses everything between them.
    """
    def sub(m):
        g = m.groupdict()
        if g.get("block") is not None:
            return " "
        if g.get("line") is not None:
            return ""
        if g.get("tstr") is not None:
            s = m.group(0)
            return s[:3] + _blank(s[3:-3]) + s[-3:]
        if g.get("here") is not None:
            s = m.group(0)
            return (s[:m.start("hbody") - m.start(0)] + _blank(m.group("hbody"))
                    + s[m.end("hbody") - m.start(0):])
        # An ordinary string is left exactly as written, even one that runs over a line break
        # (legal in PowerShell). Blanking those too was tried and REMOVED: combined with the
        # known quote-pairing gap (c) below it turned valid PowerShell into a hard false red
        # that blocks honest work, whereas hiding an entry in such a string takes a deliberate,
        # odd edit. See KNOWN GAPS.
        return m.group(0)
    return pattern.sub(sub, text)


def _yaml_quote_end(line, i):
    """Index just past the quoted scalar that opens at line[i], or len(line) if it never closes."""
    q = line[i]
    j = i + 1
    while j < len(line):
        c = line[j]
        if q == '"' and c == "\\":
            j += 2
            continue
        if c == q:
            if q == "'" and line[j + 1:j + 2] == "'":
                j += 2
                continue
            return j + 1
        j += 1
    return len(line)


def _strip_yaml(text):
    """YAML: "#" starts a comment at the start of a line or after a blank, outside a quoted scalar.

    A quote opens a scalar only where a scalar can begin (line start, after ": ", "- ",
    "[", "{" or ","), so the apostrophe in "it's" does not hide a comment behind it.
    """
    out = []
    for line in text.split("\n"):
        if "#" in line:
            i, prev = 0, None
            while i < len(line):
                c = line[i]
                if c in "\"'" and (prev is None or prev in ":-[{,?"):
                    i = _yaml_quote_end(line, i)
                    prev = c
                    continue
                if c == "#" and (i == 0 or line[i - 1] in " \t"):
                    line = line[:i].rstrip()
                    break
                if c not in " \t":
                    prev = c
                i += 1
        out.append(line)
    return "\n".join(out)


def strip_comments(text, kind):
    """`text` without its comments (None or an unknown kind: unchanged).

    kind: "ps" (# and <# #>, here-strings), "py", "yaml", "xml" (<!-- -->), "java" (// and /* */).
    Lines stay lines, so a line anchored with ^ still means the same line.
    For "ps", "py" and "java" the body of a here-string, triple-quoted string or
    text block is blanked too (see the note above _PS_TOKENS).
    """
    if kind == "ps":
        return _drop_comments(_PS_TOKENS, text)
    if kind == "py":
        return _drop_comments(_PY_TOKENS, text)
    if kind == "java":
        return _drop_comments(_JAVA_TOKENS, text)
    if kind == "yaml":
        return _strip_yaml(text)
    if kind == "xml":
        return re.sub(r"<!--.*?-->", " ", text, flags=re.S)
    return text


_KIND_BY_SUFFIX = {".ps1": "ps", ".py": "py", ".yml": "yaml", ".yaml": "yaml",
                   ".xml": "xml", ".java": "java"}


def read_code(path):
    """A watched file's text with its comments removed."""
    p = Path(path)
    return strip_comments(p.read_text(encoding="utf-8-sig"), _KIND_BY_SUFFIX.get(p.suffix.lower()))


# The artifact that makes a service run its own Flyway migrations. Spring Boot 4
# switches Flyway on through its STARTER (spring-boot-starter-flyway), whose
# auto-configuration lives in the spring-boot-flyway module the starter brings.
# flyway-core on its own is only the plain library: with it and no starter the
# migrations are never run at start-up, so it does NOT make a service a database
# owner. Everything else with "flyway" in its name is NOT enough either:
# flyway-database-postgresql is only the database driver module, and the -test
# starter is a test helper. Matched on the whole artifactId, so
# spring-boot-starter-flyway-test never counts.
FLYWAY_OWNER_ARTIFACTS = ("spring-boot-starter-flyway",)
# A dependency in one of these scopes is not on the service's runtime classpath.
FLYWAY_IGNORED_SCOPES = ("test", "provided")


def has_main_flyway(pom):
    """True when the pom has a flyway dependency of its own that runs the migrations.

    That is a direct <dependency> on spring-boot-starter-flyway
    (FLYWAY_OWNER_ARTIFACTS) whose scope is not test and not provided.

    Not counted: a dependency in a comment (read_code has removed those), one in
    <dependencyManagement> or a plugin's own dependencies (<build>), an
    <exclusion>, any other artifact that merely has "flyway" in its name
    (flyway-database-postgresql alone, spring-boot-starter-flyway-test), and one in
    test or provided scope.
    """
    pom = re.sub(r"<dependencyManagement>.*?</dependencyManagement>|<build>.*?</build>", "", pom, flags=re.S)
    for dep in re.findall(r"<dependency>(.*?)</dependency>", pom, re.S):
        dep = re.sub(r"<exclusions>.*?</exclusions>", "", dep, flags=re.S)
        a = re.search(r"<artifactId>\s*([^<\s]+)\s*</artifactId>", dep)
        if not a or a.group(1) not in FLYWAY_OWNER_ARTIFACTS:
            continue
        s = re.search(r"<scope>\s*([^<\s]+)\s*</scope>", dep)
        if s and s.group(1).lower() in FLYWAY_IGNORED_SCOPES:
            continue
        return True
    return False


def region(text, start_re, end_re):
    """The text between the first start match and the next end match, or None."""
    m = re.search(start_re, text, re.M)
    if not m:
        return None
    e = re.search(end_re, text[m.end():], re.S)
    stop = m.end() + (e.start() if e else len(text) - m.end())
    return text[m.end():stop]


def quoted(text):
    return re.findall(r"""['"]([\w.:/-]+)['"]""", text)


def ps_ints(text):
    return [(k, int(v)) for k, v in re.findall(r"\b([A-Za-z][\w-]*)\s*=\s*(\d+)", text)]


def py_ints(text):
    return [(k, int(v)) for k, v in re.findall(r"""['"]([\w-]+)['"]\s*:\s*(\d+)""", text)]


# ===========================================================================
# Generic comparisons
# ===========================================================================
def check_pairs(rep, list_id, path, found, expected, label):
    """found: [(key, value)] in file order; expected: {key: value}.

    A value of None means "presence only". Reports missing, wrong, repeated
    and unexpected entries, one problem each.
    """
    counts = Counter(k for k, _ in found)
    by_key = defaultdict(list)
    for k, v in found:
        by_key[k].append(v)
    for key in sorted(expected):
        want = expected[key]
        if key not in by_key:
            hint = "" if want is None else " (expected %s)" % (want,)
            rep.add(key, list_id, path, "%s has no entry for %s%s" % (label, key, hint))
        elif want is not None and want not in by_key[key]:
            rep.add(key, list_id, path, "%s says %s = %s, but it should be %s"
                    % (label, key, by_key[key][0], want))
    for key, n in sorted(counts.items()):
        if n > 1:
            rep.add(key, list_id, path, "%s lists %s %d times" % (label, key, n))
        if key not in expected:
            rep.add(key, list_id, path,
                    "%s has an entry '%s' that matches no module" % (label, key))


def check_names(rep, list_id, path, found, expected, label):
    check_pairs(rep, list_id, path, [(n, None) for n in found], {n: None for n in expected}, label)


# ===========================================================================
# The service facts
# ===========================================================================
def discover(ctx, rep):
    sdir = ctx.root / "services"
    spom = ctx.read("services/pom.xml")
    ctx.modules = pom_modules(spom) if spom is not None else []
    if spom is None:
        rep.add("(all)", "pom-modules", "services/pom.xml", "file not found")

    dirs = module_dirs(sdir)
    check_pairs(rep, "pom-modules", "services/pom.xml",
                [(m, None) for m in ctx.modules], {d: None for d in dirs},
                "the <module> list")

    for name in dirs:
        svc = Svc(name)
        mod = sdir / name
        res = mod / "src" / "main" / "resources"
        app = (res / "application.yaml")
        if app.is_file():
            port = yaml_child(read_code(app), "server", "port")
            svc.port = int(port) if port and port.isdigit() else None
        if svc.port is None:
            rep.add(name, "service-facts", "services/%s/src/main/resources/application.yaml" % name,
                    "no server.port found")
        mig = res / "db" / "migration"
        svc.migrations = sorted(p.name for p in mig.glob("V*__*.sql")) if mig.is_dir() else []
        pom = read_code(mod / "pom.xml")
        svc.flyway = has_main_flyway(pom)
        svc.owns_db = bool(svc.migrations) and svc.flyway
        if bool(svc.migrations) != svc.flyway:
            rep.add(name, "service-facts", "services/%s/pom.xml" % name,
                    "%s: it has %d migration file(s) but %s; a database owner has both"
                    % (name, len(svc.migrations),
                       "a flyway dependency" if svc.flyway
                       else "no spring-boot-starter-flyway dependency of its own "
                            "(test and provided scope, comments, flyway-core and "
                            "flyway-database-postgresql alone do not count: Spring Boot 4 runs "
                            "the migrations through the starter)"))
        if svc.owns_db:
            loc = res / "application-local.yaml"
            dock = res / "application-docker.yaml"
            lt = read_code(loc) if loc.is_file() else ""
            m = re.search(r"jdbc:postgresql://localhost:(\d+)/(\w+)", lt)
            if m:
                svc.db_host_port, svc.db_name = int(m.group(1)), m.group(2)
            u = re.search(r"^\s*username:\s*(\S+)", lt, re.M)
            p = re.search(r"^\s*password:\s*(\S+)", lt, re.M)
            svc.db_user = u.group(1) if u else None
            svc.db_password = p.group(1) if p else None
            if svc.db_host_port is None or not svc.db_user or not svc.db_password:
                rep.add(name, "service-facts", "services/%s/src/main/resources/application-local.yaml" % name,
                        "no jdbc url, username and password for the local database")
            dt = read_code(dock) if dock.is_file() else ""
            m = re.search(r"jdbc:postgresql://([\w-]+):5432/(\w+)", dt)
            if m:
                svc.docker_db_host, svc.docker_db_name = m.group(1), m.group(2)
            if svc.docker_db_host != "postgres-" + name or svc.docker_db_name != svc.db_name:
                rep.add(name, "service-facts",
                        "services/%s/src/main/resources/application-docker.yaml" % name,
                        "jdbc url should be postgres-%s:5432/%s, found %s:5432/%s"
                        % (name, svc.db_name, svc.docker_db_host, svc.docker_db_name))
        svc.main_class = find_main_class(mod)
        if svc.main_class is None:
            rep.add(name, "service-facts", "services/%s/src/main/java" % name,
                    "no *Application.java class annotated @SpringBootApplication")
        ctx.services[name] = svc

    for name in module_dirs(ctx.root / "infra"):
        s = Svc(name)
        app = ctx.root / "infra" / name / "src" / "main" / "resources" / "application.yaml"
        if app.is_file():
            port = yaml_child(read_code(app), "server", "port")
            s.port = int(port) if port and port.isdigit() else None
        s.main_class = find_main_class(ctx.root / "infra" / name)
        ctx.infra[name] = s

    # two modules must never claim the same port, or the same database port
    by_port = defaultdict(list)
    for n, s in list(ctx.services.items()) + list(ctx.infra.items()):
        if s.port is not None:
            by_port[s.port].append(n)
    for port, names in by_port.items():
        if len(names) > 1:
            for n in names:
                rep.add(n, "service-facts", "application.yaml",
                        "port %d is claimed by %s" % (port, ", ".join(sorted(names))))
    by_db = defaultdict(list)
    for n in ctx.owners:
        if ctx.services[n].db_host_port is not None:
            by_db[ctx.services[n].db_host_port].append(n)
    for port, names in by_db.items():
        if len(names) > 1:
            for n in names:
                rep.add(n, "service-facts", "application-local.yaml",
                        "database host port %d is claimed by %s" % (port, ", ".join(sorted(names))))


# ===========================================================================
# docker-compose
# ===========================================================================
def parse_compose(text):
    """service blocks (name -> lines), duplicate service keys, top-level volume names."""
    blocks = OrderedDict()
    dups = []
    volumes = []
    section = None
    cur = None
    for line in text.splitlines():
        m = re.match(r"^([A-Za-z_][\w-]*):\s*(#.*)?$", line)
        if m:
            section = m.group(1)
            cur = None
            continue
        if section == "services":
            m = re.match(r"^  ([A-Za-z0-9_.-]+):\s*(#.*)?$", line)
            if m:
                name = m.group(1)
                if name in blocks:
                    dups.append(name)
                    cur = None
                else:
                    blocks[name] = []
                    cur = name
                continue
            if cur is not None:
                blocks[cur].append(line)
        elif section == "volumes":
            m = re.match(r"^  ([A-Za-z0-9_.-]+):\s*(#.*)?$", line)
            if m:
                volumes.append(m.group(1))
    return blocks, dups, volumes


def block_scalar(lines, key, indent=4):
    for l in lines:
        m = re.match(r"^ {%d}%s:\s*(\S.*?)\s*$" % (indent, re.escape(key)), l)
        if m:
            return m.group(1).strip("\"'")
    return None


def block_env(lines, key):
    for l in lines:
        m = re.match(r"^\s{6}%s:\s*(\S+)\s*$" % re.escape(key), l)
        if m:
            return m.group(1)
    return None


def list_under(lines, key, indent=4):
    out = []
    on = False
    for l in lines:
        if re.match(r"^ {%d}%s:\s*$" % (indent, re.escape(key)), l):
            on = True
            continue
        if on:
            m = re.match(r"^ {%d}-\s*(.+?)\s*$" % (indent + 2), l)
            if m:
                out.append(m.group(1).strip("\"'"))
                continue
            if l.strip() and not l.lstrip().startswith("#"):
                on = False
    return out


def depends_on(lines):
    deps = OrderedDict()
    on = False
    cur = None
    for l in lines:
        if re.match(r"^    depends_on:\s*$", l):
            on = True
            continue
        if on:
            m = re.match(r"^      ([\w.-]+):\s*$", l)
            if m:
                cur = m.group(1)
                deps[cur] = None
                continue
            m = re.match(r"^        condition:\s*(\S+)", l)
            if m and cur:
                deps[cur] = m.group(1)
                continue
            if l.strip() and not l.lstrip().startswith("#") and not l.startswith("      "):
                on = False
    return deps


def extends_of(lines):
    text = "\n".join(lines)
    m = re.search(r"^    extends:\s*\n\s+file:\s*(\S+)\s*\n\s+service:\s*(\S+)", text, re.M)
    return (m.group(1), m.group(2)) if m else (None, None)


def check_compose(ctx, rep):
    text = need(ctx, rep, "compose-service", COMPOSE)
    if text is None:
        return
    blocks, dups, volumes = parse_compose(text)
    for n in sorted(set(dups)):
        rep.add(n, "compose-service", COMPOSE, "the service key %s is defined more than once" % n)

    for name in sorted(ctx.services):
        s = ctx.services[name]
        b = blocks.get(name)
        if b is None:
            rep.add(name, "compose-service", COMPOSE, "no service block '%s'" % name)
        else:
            want = {"image": "oriah/%s:dev" % name, "container_name": "oriah-%s" % name}
            for key, val in want.items():
                got = block_scalar(b, key)
                if got != val:
                    rep.add(name, "compose-service", COMPOSE, "%s should be %s, found %s" % (key, val, got))
            ports = list_under(b, "ports")
            if ports != ["127.0.0.1:%s:%s" % (s.port, s.port)]:
                rep.add(name, "compose-service", COMPOSE,
                        "ports should be exactly [127.0.0.1:%s:%s], found %s" % (s.port, s.port, ports))
            f, base = extends_of(b)
            if base != DOMAIN_BASE or f != "../common-config.yml":
                rep.add(name, "compose-service", COMPOSE,
                        "should extend %s from ../common-config.yml, found %s from %s" % (DOMAIN_BASE, base, f))
            probe = re.search(r"/dev/tcp/localhost/(\d+)", "\n".join(b))
            if not probe or int(probe.group(1)) != s.port:
                rep.add(name, "compose-service", COMPOSE,
                        "healthcheck should probe port %s, found %s" % (s.port, probe.group(1) if probe else None))
            if block_scalar(b, "start_period", indent=6) is None:
                rep.add(name, "compose-service", COMPOSE, "healthcheck has no start_period")
            deps = depends_on(b)
            pg = "postgres-" + name
            if s.owns_db:
                if deps.get(pg) != "service_healthy":
                    rep.add(name, "compose-service", COMPOSE,
                            "depends_on should hold %s with condition service_healthy" % pg)
                for key, val in (("SPRING_DATASOURCE_USERNAME", s.db_user),
                                 ("SPRING_DATASOURCE_PASSWORD", s.db_password)):
                    got = block_env(b, key)
                    if got != val:
                        rep.add(name, "compose-service", COMPOSE, "%s should be %s, found %s" % (key, val, got))
            else:
                stray = [d for d in deps if d.startswith("postgres-")]
                if stray:
                    rep.add(name, "compose-service", COMPOSE,
                            "it owns no database (no migrations) but depends on %s" % ", ".join(stray))

    for name in ctx.owners:
        s = ctx.services[name]
        pg = "postgres-" + name
        b = blocks.get(pg)
        if b is None:
            rep.add(name, "compose-postgres", COMPOSE, "no database block '%s'" % pg)
        else:
            want = {"container_name": "oriah-" + pg}
            for key, val in want.items():
                got = block_scalar(b, key)
                if got != val:
                    rep.add(name, "compose-postgres", COMPOSE, "%s should be %s, found %s" % (key, val, got))
            for key, val in (("POSTGRES_DB", s.db_name), ("POSTGRES_USER", s.db_user),
                             ("POSTGRES_PASSWORD", s.db_password)):
                got = block_env(b, key)
                if got != val:
                    rep.add(name, "compose-postgres", COMPOSE, "%s should be %s, found %s" % (key, val, got))
            ports = list_under(b, "ports")
            if ports != ["127.0.0.1:%s:5432" % s.db_host_port]:
                rep.add(name, "compose-postgres", COMPOSE,
                        "ports should be exactly [127.0.0.1:%s:5432] (the host port its application-local.yaml "
                        "connects to), found %s" % (s.db_host_port, ports))
            vols = list_under(b, "volumes")
            if vols != ["%s-data:/var/lib/postgresql/data" % pg]:
                rep.add(name, "compose-postgres", COMPOSE,
                        "volumes should be exactly [%s-data:/var/lib/postgresql/data], found %s" % (pg, vols))
            hc = "\n".join(b)
            if ("pg_isready -U %s -d %s" % (s.db_user, s.db_name)) not in hc:
                rep.add(name, "compose-postgres", COMPOSE,
                        "healthcheck should run pg_isready -U %s -d %s" % (s.db_user, s.db_name))
            f, base = extends_of(b)
            if base != POSTGRES_BASE:
                rep.add(name, "compose-postgres", COMPOSE, "should extend %s, found %s" % (POSTGRES_BASE, base))
        n = volumes.count(pg + "-data")
        if n == 0:
            rep.add(name, "compose-volume", COMPOSE, "top-level volume %s-data is not declared" % pg)
        elif n > 1:
            rep.add(name, "compose-volume", COMPOSE, "top-level volume %s-data is declared %d times" % (pg, n))

    # extras: a database or image for something that is not a module
    owner_pg = {"postgres-" + n for n in ctx.owners} | {"postgres-" + k for k in NON_SERVICE_POSTGRES}
    for name in blocks:
        if name.startswith("postgres-") and name not in owner_pg and name != POSTGRES_BASE:
            rep.add(name[len("postgres-"):], "compose-postgres", COMPOSE,
                    "database block %s has no service that owns a database under that name" % name)
    owner_vol = {p + "-data" for p in owner_pg}
    for v in sorted(set(volumes)):
        if re.match(r"^postgres-.+-data$", v) and v not in owner_vol:
            rep.add(v[len("postgres-"):-len("-data")], "compose-volume", COMPOSE,
                    "volume %s belongs to no database owner" % v)
    known = set(ctx.services) | set(ctx.infra) | NON_MODULE_IMAGES
    for name, b in blocks.items():
        img = block_scalar(b, "image") or ""
        m = re.match(r"^oriah/([\w.-]+):", img)
        if m and m.group(1) not in known:
            rep.add(m.group(1), "compose-service", COMPOSE,
                    "service %s runs image %s, which matches no module" % (name, img))

    # one host port, one owner
    owners_of = defaultdict(list)
    for name, b in blocks.items():
        for p in list_under(b, "ports"):
            m = re.match(r"^(?:[\d.]+:)?(\d+):\d+$", p)
            if m:
                owners_of[m.group(1)].append(name)
    for hp, names in sorted(owners_of.items()):
        if len(names) > 1:
            for n in names:
                rep.add(n, "compose-service", COMPOSE,
                        "host port %s is published by %s" % (hp, ", ".join(sorted(names))))


# ===========================================================================
# The other lists
# ===========================================================================
def modules_expected(ctx):
    """Services and platform modules with their ports: the lists that name both."""
    exp = {n: s.port for n, s in ctx.services.items()}
    exp.update({n: s.port for n, s in ctx.infra.items()})
    return exp


def check_build_images(ctx, rep):
    t = need(ctx, rep, "build-images", BUILD_IMAGES)
    if t is None:
        return
    # Keyed by the module's own name so a problem line names the service; the
    # value is (build path, image tag), so a wrong path or tag fails as well.
    found = [(path.split("/")[-1], (path, image))
             for path, image in re.findall(r'Path\s*=\s*"([^"]+)"\s*;\s*Image\s*=\s*"([^"]+)"', t)]
    exp = {}
    for n in ctx.services:
        exp[n] = ("services/" + n, "oriah/%s:dev" % n)
    for n in ctx.infra:
        exp[n] = ("infra/" + n, "oriah/%s:dev" % n)
    check_pairs(rep, "build-images", BUILD_IMAGES, found, exp, "$modules")


def check_prometheus(ctx, rep):
    t = need(ctx, rep, "prometheus", PROMETHEUS)
    if t is None:
        return
    jobs = OrderedDict()
    cur = None
    for line in t.splitlines():
        m = re.match(r"^\s*-\s*job_name:\s*(\S+)", line)
        if m:
            cur = m.group(1)
            jobs[cur] = []
            continue
        m = re.match(r"""^\s*-\s*['"]([\w.-]+):(\d+)['"]\s*$""", line)
        if m and cur:
            jobs[cur].append((m.group(1), int(m.group(2))))
    check_pairs(rep, "prometheus", PROMETHEUS, jobs.get("oriah-services", []),
                {n: s.port for n, s in ctx.services.items()}, "job oriah-services")
    check_pairs(rep, "prometheus", PROMETHEUS, jobs.get("oriah-infra", []),
                {n: s.port for n, s in ctx.infra.items()}, "job oriah-infra")


def check_dev_stack(ctx, rep):
    t = need(ctx, rep, "dev-stack-apps", DEV_STACK)
    if t is None:
        return
    apps = region(t, r"^\s*\$JavaApps\s*=\s*@\(", r"\)")
    if apps is None:
        rep.add("(all)", "dev-stack-apps", DEV_STACK, "no $JavaApps list found")
    else:
        check_names(rep, "dev-stack-apps", DEV_STACK, quoted(apps),
                    list(ctx.infra) + list(ctx.services), "$JavaApps")
    backing = region(t, r"^\s*\$Backing\s*=\s*@\(", r"\)")
    if backing is None:
        rep.add("(all)", "dev-stack-backing", DEV_STACK, "no $Backing list found")
        return
    pgs = [q for q in quoted(backing) if q.startswith("postgres-")]
    exp = ["postgres-" + n for n in ctx.owners] + ["postgres-" + k for k in sorted(NON_SERVICE_POSTGRES)]
    # key by the service name, so a problem line names the service
    check_pairs(rep, "dev-stack-backing", DEV_STACK,
                [(p[len("postgres-"):], None) for p in pgs],
                {p[len("postgres-"):]: None for p in exp}, "$Backing (postgres-<name>)")


def check_local_test(ctx, rep):
    t = need(ctx, rep, "local-test-services", LOCAL_TEST)
    if t is None:
        return
    plat = region(t, r"^\s*\$Platform\s*=\s*\[ordered\]@\{", r"\}")
    if plat is None:
        rep.add("(all)", "local-test-platform", LOCAL_TEST, "no $Platform table found")
    else:
        check_pairs(rep, "local-test-platform", LOCAL_TEST, ps_ints(plat),
                    {n: s.port for n, s in ctx.infra.items()}, "$Platform")
    svcs = region(t, r"\$Services\s*=\s*@\(", r"\)")
    if svcs is None:
        rep.add("(all)", "local-test-services", LOCAL_TEST, "no $Services default found")
    else:
        check_names(rep, "local-test-services", LOCAL_TEST, quoted(svcs), list(ctx.services), "$Services")
    ports = region(t, r"^\s*\$Ports\s*=\s*@\{", r"\}")
    if ports is None:
        rep.add("(all)", "local-test-ports", LOCAL_TEST, "no $Ports table found")
    else:
        check_pairs(rep, "local-test-ports", LOCAL_TEST, ps_ints(ports),
                    {n: s.port for n, s in ctx.services.items()}, "$Ports")
    nodb = region(t, r"^\s*\$NoDatabase\s*=\s*@\(", r"\)")
    if nodb is None:
        rep.add("(all)", "local-test-nodb", LOCAL_TEST, "no $NoDatabase list found")
    else:
        check_names(rep, "local-test-nodb", LOCAL_TEST, quoted(nodb), ctx.stateless,
                    "$NoDatabase (services without a database)")
    back = region(t, r"^\s*\$backing\s*=", r"ForEach-Object")
    if back is None:
        rep.add("(all)", "local-test-postgres", LOCAL_TEST, "no $backing health list found")
    else:
        toks = quoted(back)
        names = [q for q in toks if not q.startswith("oriah-")]
        extra_pg = [q[len("oriah-postgres-"):] for q in toks
                    if q.startswith("oriah-postgres-") and q[len("oriah-postgres-"):] not in NON_SERVICE_POSTGRES]
        check_names(rep, "local-test-postgres", LOCAL_TEST, names + extra_pg, ctx.owners,
                    "the Postgres health list")


def check_capture_and_py(ctx, rep):
    t = need(ctx, rep, "capture-openapi", CAPTURE)
    if t is not None:
        r = region(t, r"^\s*\$services\s*=\s*@\(", r"\)")
        if r is None:
            rep.add("(all)", "capture-openapi", CAPTURE, "no $services list found")
        else:
            check_names(rep, "capture-openapi", CAPTURE, quoted(r), list(ctx.services), "$services")
    for rel, lid in ((AUDIT, "openapi-audit"), (DIFF, "openapi-diff")):
        t = need(ctx, rep, lid, rel)
        if t is None:
            continue
        r = region(t, r"^SERVICES\s*=\s*\[", r"\]")
        if r is None:
            rep.add("(all)", lid, rel, "no SERVICES list found")
        else:
            check_names(rep, lid, rel, quoted(r), list(ctx.services), "SERVICES")


def check_postman(ctx, rep):
    t = need(ctx, rep, "postman-services", POSTMAN_GEN)
    if t is not None:
        r = region(t, r"^SERVICES\s*=\s*\{", r"\}")
        if r is None:
            rep.add("(all)", "postman-services", POSTMAN_GEN, "no SERVICES map found")
        else:
            check_pairs(rep, "postman-services", POSTMAN_GEN, py_ints(r),
                        {n: s.port for n, s in ctx.services.items()}, "SERVICES")
        r = region(t, r"^PLATFORM\s*=\s*\{", r"\}")
        if r is None:
            rep.add("(all)", "postman-platform", POSTMAN_GEN, "no PLATFORM map found")
        else:
            check_pairs(rep, "postman-platform", POSTMAN_GEN, py_ints(r),
                        {n: s.port for n, s in ctx.infra.items()}, "PLATFORM")
    t = need(ctx, rep, "postman-env", POSTMAN_ENV)
    if t is not None:
        try:
            values = json.loads(t)["values"]
        except (ValueError, KeyError) as exc:
            rep.add("(all)", "postman-env", POSTMAN_ENV, "cannot read the environment file: %s" % exc)
            return
        found = []
        for v in values:
            k = v.get("key", "")
            if k.endswith("Url") and k not in ("baseUrl", "keycloakUrl"):
                found.append((k[:-3], v.get("value")))
        check_pairs(rep, "postman-env", POSTMAN_ENV, found,
                    {n: "http://localhost:%s" % s.port for n, s in ctx.services.items()},
                    "the environment (<service>Url)")


def check_pgadmin(ctx, rep):
    t = need(ctx, rep, "pgadmin", PGADMIN)
    if t is None:
        return
    try:
        servers = json.loads(t)["Servers"]
    except (ValueError, KeyError) as exc:
        rep.add("(all)", "pgadmin", PGADMIN, "cannot read the servers file: %s" % exc)
        return
    entries = [v for v in servers.values() if not str(v.get("Name", "")).startswith("keycloak")]
    check_names(rep, "pgadmin", PGADMIN, [v.get("Name") for v in entries], ctx.owners, "the server list")
    for v in entries:
        s = ctx.services.get(v.get("Name"))
        if s is None or not s.owns_db:
            continue
        for key, want in (("Port", s.db_host_port), ("MaintenanceDB", s.db_name),
                          ("Username", s.db_user), ("Host", "localhost")):
            if v.get(key) != want:
                rep.add(s.name, "pgadmin", PGADMIN, "server %s: %s is %s, but it should be %s"
                        % (s.name, key, v.get(key), want))
    ports = Counter(v.get("Port") for v in servers.values())
    for v in entries:
        if ports[v.get("Port")] > 1:
            rep.add(str(v.get("Name")), "pgadmin", PGADMIN, "port %s is used by more than one server" % v.get("Port"))


def check_smoke(ctx, rep):
    t = need(ctx, rep, "smoke-routeprobes", SMOKE)
    if t is None:
        return
    r = region(t, r"^\s*\$routeProbes\s*=\s*\[ordered\]@\{", r"\}")
    if r is None:
        rep.add("(all)", "smoke-routeprobes", SMOKE, "no $routeProbes table found")
    else:
        keys = re.findall(r"^\s*([A-Za-z][\w-]*)\s*=", r, re.M)
        check_names(rep, "smoke-routeprobes", SMOKE, keys, list(ctx.services), "$routeProbes")
    r = region(t, r"^\s*\$expected\s*=\s*@\(", r"\)")
    if r is None:
        rep.add("(all)", "smoke-expected", SMOKE, "no $expected list found")
    else:
        pairs = []
        for item in quoted(r):
            name, _, port = item.partition(":")
            pairs.append((name, item))
        check_pairs(rep, "smoke-expected", SMOKE, pairs,
                    {n: "%s:%s" % (n, s.port) for n, s in ctx.services.items()}, "$expected (name:port)")
    r = region(t, r"^\s*\$servicePorts\s*=\s*\[ordered\]@\{", r"\}")
    if r is None:
        rep.add("(all)", "smoke-ports", SMOKE, "no $servicePorts table found")
    else:
        check_pairs(rep, "smoke-ports", SMOKE, ps_ints(r),
                    {n: s.port for n, s in ctx.services.items()}, "$servicePorts")


def check_run(ctx, rep):
    t = need(ctx, rep, "run-compound", RUN_ALL)
    if t is not None:
        found = re.findall(r'<toRun\s+name="([^"]+)"', t)
        label = "the compound run configuration"
        counts = Counter(found)
        for n, s in ctx.services.items():
            want = "%s :%s (local)" % (n, s.port)
            if counts.get(want, 0) == 0:
                others = [f for f in found if re.match(r"^%s :" % re.escape(n), f)]
                rep.add(n, "run-compound", RUN_ALL,
                        "%s has no entry '%s'%s" % (label, want, (" (found '%s')" % others[0]) if others else ""))
            elif counts[want] > 1:
                rep.add(n, "run-compound", RUN_ALL, "%s lists '%s' %d times" % (label, want, counts[want]))
        known_prefix = {n for n in ctx.services}
        for f in found:
            m = re.match(r"^([\w-]+) :", f)
            if not m or m.group(1) not in known_prefix:
                rep.add(m.group(1) if m else f, "run-compound", RUN_ALL,
                        "%s has an entry '%s' that matches no service" % (label, f))

    expected_files = {n for n in ctx.services}
    for name, s in ctx.services.items():
        rel = ".run/%s-local.run.xml" % name
        t = ctx.read(rel)
        if t is None:
            rep.add(name, "run-file", rel, "no IntelliJ run configuration for %s" % name)
            continue
        want = "%s :%s (local)" % (name, s.port)
        m = re.search(r'<configuration\b[^>]*\bname="([^"]*)"', t)
        if not m or m.group(1) != want:
            rep.add(name, "run-file", rel, "configuration name should be '%s', found %s"
                    % (want, ("'%s'" % m.group(1)) if m else None))
        m = re.search(r'<module\s+name="([^"]*)"', t)
        if not m or m.group(1) != name:
            rep.add(name, "run-file", rel, "module should be %s, found %s" % (name, m.group(1) if m else None))
        m = re.search(r'name="MAIN_CLASS_NAME"\s+value="([^"]*)"', t)
        if not m or m.group(1) != s.main_class:
            rep.add(name, "run-file", rel, "main class should be %s, found %s"
                    % (s.main_class, m.group(1) if m else None))
        m = re.search(r'<env\s+name="SPRING_PROFILES_ACTIVE"\s+value="([^"]*)"', t)
        if not m or m.group(1) != "local":
            rep.add(name, "run-file", rel, "SPRING_PROFILES_ACTIVE should be local")
    run_dir = ctx.root / ".run"
    if run_dir.is_dir():
        known = expected_files | set(ctx.infra)
        for f in sorted(run_dir.glob("*-local.run.xml")):
            stem = f.name[:-len("-local.run.xml")]
            if re.match(r"^\d+-", stem):
                continue                      # the two compound configurations
            if stem not in known:
                rep.add(stem, "run-file", ".run/" + f.name, "a run configuration for a module that does not exist")


def check_gateway(ctx, rep):
    routes = None
    base = ctx.root / GATEWAY_ROUTES_DIR
    if base.is_dir():
        hits = sorted(base.rglob("GatewayRoutesConfig.java"))
        if len(hits) == 1:
            routes = hits[0]
    if routes is None:
        rep.add("(all)", "gateway-docs", GATEWAY_ROUTES_DIR, "GatewayRoutesConfig.java not found (or not unique)")
    else:
        rel = routes.relative_to(ctx.root).as_posix()
        t = read_code(routes)
        r = region(t, r"DOCS_SERVICES\s*=\s*List\.of\(", r"\)\s*;")
        if r is None:
            rep.add("(all)", "gateway-docs", rel, "no DOCS_SERVICES list found")
        else:
            check_names(rep, "gateway-docs", rel, quoted(r), list(ctx.services), "DOCS_SERVICES")
    t = need(ctx, rep, "gateway-swagger", GATEWAY_YAML)
    if t is not None:
        found = re.findall(r"-\s*\{\s*name:\s*([\w-]+)\s*,\s*url:\s*(\S+?)\s*\}", t)
        check_pairs(rep, "gateway-swagger", GATEWAY_YAML, found,
                    {n: "/v3/api-docs/%s" % n for n in ctx.services}, "springdoc.swagger-ui.urls")


# A top-level YAML key: starts in column 0 (not a comment, a list dash or a
# document marker) and is followed by a colon and a blank or the end of the line.
_YAML_TOP_KEY = re.compile(r"""^(?:[A-Za-z_][\w.-]*|"[^"\r\n]+"|'[^'\r\n]+')[ \t]*:(?:[ \t]|$)""")


def check_config_repo(ctx, rep):
    if not ctx.cfg.is_dir():
        rep.add("(all)", "config-repo", "../config-repo", "the config-repo folder was not found next to oriah-api")
        return
    present = {p.name[:-len(".yaml")] for p in ctx.cfg.glob("*.yaml")}
    for n in ctx.services:
        rel = "../config-repo/%s.yaml" % n
        if n not in present:
            rep.add(n, "config-repo", rel,
                    "no configuration file for %s (the Config Server serves committed files only)" % n)
            continue
        # A file that exists is not yet a configuration: an empty one, or one that
        # holds only comments, serves the service nothing. Read it as the other
        # watched files are (comments removed) and require a top-level key.
        try:
            body = read_code(ctx.cfg / (n + ".yaml"))
        except (OSError, UnicodeDecodeError) as exc:
            rep.add(n, "config-repo", rel, "cannot read the configuration file for %s: %s" % (n, exc))
            continue
        if not any(_YAML_TOP_KEY.match(line) for line in body.splitlines()):
            rep.add(n, "config-repo", rel,
                    "the configuration file for %s holds no top-level key (it is empty, or only blank lines "
                    "and comments), so the Config Server would serve it nothing" % n)
    for n in sorted(present - {"application"} - set(ctx.services)):
        rep.add(n, "config-repo", "../config-repo/%s.yaml" % n, "a configuration file for a service that does not exist")


def run_guard(root):
    ctx = Ctx(root)
    rep = Report()
    discover(ctx, rep)
    check_compose(ctx, rep)
    check_build_images(ctx, rep)
    check_prometheus(ctx, rep)
    check_dev_stack(ctx, rep)
    check_local_test(ctx, rep)
    check_capture_and_py(ctx, rep)
    check_postman(ctx, rep)
    check_pgadmin(ctx, rep)
    check_smoke(ctx, rep)
    check_run(ctx, rep)
    check_gateway(ctx, rep)
    check_config_repo(ctx, rep)
    return ctx, rep


def guard_main(root):
    root = Path(root).resolve()
    if not (root / "services" / "pom.xml").is_file():
        print("ERROR: %s does not look like oriah-api (no services/pom.xml)" % root)
        return 2
    ctx, rep = run_guard(root)
    for line in rep.lines():
        print(line)
    if len(rep) == 0:
        print("check_service_registry: OK - %d services (%d own a database), %d lists agree"
              % (len(ctx.services), len(ctx.owners), len(LIST_IDS)))
        return 0
    print("check_service_registry: FAIL - %d problem(s) across %d module(s)"
          % (len(rep), len(ctx.services)))
    return 1


# ###########################################################################
# SELF-TEST -- the mutator. Nothing above this line is used below it, except
# the file locations, which a wrong guess would make fail loudly (a mutation
# that matches nothing is reported as a self-test failure, never skipped).
# ###########################################################################
class MutationError(Exception):
    pass


def _norm(text):
    return text.replace("\r\n", "\n")


def _window(text, start_re, end_re, op):
    """Apply op to the text between start_re and the next end_re; start must match once."""
    starts = list(re.finditer(start_re, text, re.M))
    if len(starts) != 1:
        raise MutationError("region start %r matches %d times" % (start_re, len(starts)))
    s = starts[0].end()
    e = re.search(end_re, text[s:], re.S)
    stop = s + (e.start() if e else len(text) - s)
    return text[:s] + op(text[s:stop]) + text[stop:]


def _with_region(region_spec, op):
    if region_spec is None:
        return op
    start_re, end_re = region_spec
    return lambda text: _window(text, start_re, end_re, op)


def m_delete_block(header_re):
    """Delete a two-space-indented YAML block: its header line to just before the next key."""
    def fn(text):
        ls = text.split("\n")
        hits = [i for i, l in enumerate(ls) if re.match(header_re, l)]
        if len(hits) != 1:
            raise MutationError("%d lines match %r" % (len(hits), header_re))
        i = hits[0]
        j = i + 1
        while j < len(ls) and not re.match(r"^ {0,2}\S", ls[j]):
            j += 1
        return "\n".join(ls[:i] + ls[j:])
    return fn


def m_delete_line(line_re, region=None, json_commas=False):
    def op(text):
        ls = text.split("\n")
        hits = [i for i, l in enumerate(ls) if re.search(line_re, l)]
        if len(hits) != 1:
            raise MutationError("%d lines match %r" % (len(hits), line_re))
        i = hits[0]
        gone = ls[i]
        del ls[i]
        if json_commas and not gone.rstrip().endswith(","):
            k = i - 1
            while k >= 0 and not ls[k].strip():
                k -= 1
            if k >= 0 and ls[k].rstrip().endswith(","):
                ls[k] = ls[k].rstrip()[:-1]
        return "\n".join(ls)
    return _with_region(region, op)


def m_remove_token(token_re, region=None):
    """Remove one list element (and its trailing separator) wherever it sits."""
    def op(text):
        pat = re.compile(r"(?:" + token_re + r")[ \t]*[,;]?[ \t]*")
        n = len(pat.findall(text))
        if n != 1:
            raise MutationError("%d elements match %r" % (n, token_re))
        return pat.sub("", text, count=1)
    return _with_region(region, op)


def m_replace(pattern, group_fn, region=None):
    """Replace exactly one match; group_fn(match) gives the new text for the whole match."""
    def op(text):
        pat = re.compile(pattern)
        n = len(pat.findall(text))
        if n != 1:
            raise MutationError("%d matches for %r" % (n, pattern))
        return pat.sub(lambda m: group_fn(m), text, count=1)
    return _with_region(region, op)


def _span(text, region_spec):
    """(lo, hi) of the window a region spec names; the start must match once."""
    if region_spec is None:
        return 0, len(text)
    starts = list(re.finditer(region_spec[0], text, re.M))
    if len(starts) != 1:
        raise MutationError("region start %r matches %d times" % (region_spec[0], len(starts)))
    lo = starts[0].end()
    e = re.search(region_spec[1], text[lo:], re.S)
    return lo, lo + (e.start() if e else len(text) - lo)


def m_comment_token(token_re, marker, region_spec=None):
    """Comment one list element out: it moves to a line of its own behind `marker`.

    The element keeps its trailing separator inside the comment. Whatever shared
    its line (the opening bracket before it, the closing bracket after it) stays
    live on lines of their own, so only the entry stops counting.
    """
    def fn(text):
        lo, hi = _span(text, region_spec)
        pat = re.compile(r"(?:" + token_re + r")[ \t]*[,;]?[ \t]*")
        hits = list(pat.finditer(text, lo, hi))
        if len(hits) != 1:
            raise MutationError("%d elements match %r" % (len(hits), token_re))
        m = hits[0]
        ls = text.rfind("\n", 0, m.start()) + 1
        le = text.find("\n", m.end())
        le = len(text) if le < 0 else le
        indent = re.match(r"[ \t]*", text[ls:]).group(0)
        before = text[ls:m.start()].rstrip(" \t")
        after = text[m.end():le].strip()
        pieces = []
        if before.strip():
            pieces.append(before)
        pieces.append(indent + marker + m.group(0).strip())
        if after:
            pieces.append(indent + after)
        return text[:ls] + "\n".join(pieces) + text[le:]
    return fn


def m_comment_line(line_re, marker="# "):
    """Put a line comment marker in front of the one line that matches."""
    def fn(text):
        ls = text.split("\n")
        hits = [i for i, l in enumerate(ls) if re.search(line_re, l)]
        if len(hits) != 1:
            raise MutationError("%d lines match %r" % (len(hits), line_re))
        i = hits[0]
        indent = re.match(r"[ \t]*", ls[i]).group(0)
        ls[i] = indent + marker + ls[i][len(indent):]
        return "\n".join(ls)
    return fn


def m_comment_block(header_re):
    """Comment a two-space-indented YAML block out, header line to just before the next key."""
    def fn(text):
        ls = text.split("\n")
        hits = [i for i, l in enumerate(ls) if re.match(header_re, l)]
        if len(hits) != 1:
            raise MutationError("%d lines match %r" % (len(hits), header_re))
        i = hits[0]
        j = i + 1
        while j < len(ls) and not re.match(r"^ {0,2}\S", ls[j]):
            j += 1
        for k in range(i, j):
            if ls[k].strip():
                ls[k] = "#" + ls[k]
        return "\n".join(ls)
    return fn


def m_wrap_line(line_re):
    """Wrap the one line that matches in an XML comment."""
    def fn(text):
        ls = text.split("\n")
        hits = [i for i, l in enumerate(ls) if re.search(line_re, l)]
        if len(hits) != 1:
            raise MutationError("%d lines match %r" % (len(hits), line_re))
        i = hits[0]
        indent = re.match(r"[ \t]*", ls[i]).group(0)
        ls[i] = indent + "<!-- " + ls[i].strip() + " -->"
        return "\n".join(ls)
    return fn


def m_wrap_file(text):
    """The whole file inside one XML comment."""
    return "<!--\n" + text.rstrip("\n") + "\n-->\n"


_DEPENDENCY = re.compile(r"[ \t]*<dependency>.*?</dependency>[ \t]*\n?", re.S)


def m_flyway_main(mode):
    """Take the main flyway dependencies out of a pom, leaving the -test one as a decoy.

    mode "remove": delete them. mode "comment": wrap each in an XML comment (the
    dependency is still in the file, but it is not a dependency). Either way the
    service has migrations and no flyway dependency of its own.
    """
    def fn(text):
        deps = list(_DEPENDENCY.finditer(text))
        main = [m for m in deps if "flyway" in m.group(0) and "<scope>test</scope>" not in m.group(0)]
        decoy = [m for m in deps if "flyway" in m.group(0) and "<scope>test</scope>" in m.group(0)]
        if not main:
            raise MutationError("no main flyway dependency in the pom")
        if not decoy:
            raise MutationError("no test-scope flyway dependency to leave behind as the decoy")
        out, pos = [], 0
        for m in main:
            out.append(text[pos:m.start()])
            if mode == "comment":
                body = m.group(0)
                indent = body[:len(body) - len(body.lstrip())]
                out.append(indent + "<!-- " + body.strip() + " -->\n")
            pos = m.end()
        out.append(text[pos:])
        return "".join(out)
    return fn


def m_flyway_variant(mode):
    """Damage a pom's flyway dependencies in one of the ways that only LOOKS like a database owner.

    "no-starter":      delete only spring-boot-starter-flyway; the driver module
                       (flyway-database-postgresql) and the -test starter stay, so the
                       word "flyway" is still all over the pom but Spring Boot 4 has
                       no Flyway auto-configuration and the migrations never run.
    "provided":        <scope>provided</scope> on the starter and the driver module.
    "test-unscoped":   delete the starter and the driver module and drop the
                       <scope>test</scope> from the -test starter (a test helper
                       that now sits on the main classpath).
    """
    def fn(text):
        deps = list(_DEPENDENCY.finditer(text))
        starter = [m for m in deps if "<artifactId>spring-boot-starter-flyway</artifactId>" in m.group(0)]
        driver = [m for m in deps if "<artifactId>flyway-database-postgresql</artifactId>" in m.group(0)]
        helper = [m for m in deps if "<artifactId>spring-boot-starter-flyway-test</artifactId>" in m.group(0)]
        if not starter:
            raise MutationError("no spring-boot-starter-flyway dependency in the pom")
        if mode in ("no-starter", "provided") and not driver:
            raise MutationError("no flyway-database-postgresql dependency to leave behind as the decoy")
        if mode == "test-unscoped" and not helper:
            raise MutationError("no spring-boot-starter-flyway-test dependency in the pom")
        edits = []                                   # (match, replacement text)
        if mode == "no-starter":
            edits = [(m, "") for m in starter]
        elif mode == "provided":
            for m in starter + driver:
                if "<scope>" in m.group(0):
                    raise MutationError("a flyway dependency already has a scope")
                edits.append((m, m.group(0).replace("</dependency>", "<scope>provided</scope></dependency>")))
        elif mode == "test-unscoped":
            edits = [(m, "") for m in starter + driver]
            edits += [(m, m.group(0).replace("<scope>test</scope>", "")) for m in helper]
        else:
            raise MutationError("unknown flyway mode " + mode)
        out, pos = [], 0
        for m, new in sorted(edits, key=lambda e: e[0].start()):
            out.append(text[pos:m.start()])
            out.append(new)
            pos = m.end()
        out.append(text[pos:])
        return "".join(out)
    return fn


# ---- multi-line strings: the entry (or a whole old copy of the list) is moved into one ----
def _wrap_delims(lang, content):
    """(opener, closer, closer must start in column 0) of a multi-line string in `lang`."""
    if lang == "ps":
        return "@'", "'@", True                      # a here-string closes only in column 0
    if lang == "java":
        return '"""', '"""', False                   # a text block
    if lang == "psq":                                # an ordinary PowerShell string that runs over lines
        if '"' in content and "'" in content:
            raise MutationError("the text uses both quote kinds, so no plain string can hold it")
        q = "'" if '"' in content else '"'
        return q, q, False
    q = "'''" if '"' in content else '"""'           # python: the quote style the content does not use
    return q, q, False


def m_wrap_token(token_re, lang, region_spec=None):
    """Move one list element into a multi-line string on lines of its own.

    lang "ps": a here-string, "psq": an ordinary PowerShell string that runs over
    lines, "py": a triple-quoted string, "java": a text block.
    The element's separator (the comma or semicolon) is left after the closing
    delimiter, so the list around the string keeps its shape. The element is no
    longer a list element: it is the text of a string.
    """
    def fn(text):
        lo, hi = _span(text, region_spec)
        pat = re.compile(r"(?:" + token_re + r")[ \t]*[,;]?[ \t]*")
        hits = list(pat.finditer(text, lo, hi))
        if len(hits) != 1:
            raise MutationError("%d elements match %r" % (len(hits), token_re))
        m = hits[0]
        ls = text.rfind("\n", 0, m.start()) + 1
        le = text.find("\n", m.end())
        le = len(text) if le < 0 else le
        indent = re.match(r"[ \t]*", text[ls:]).group(0)
        before = text[ls:m.start()].rstrip(" \t")
        after = text[m.end():le].strip()
        elem = m.group(0).strip()
        sep = ""
        if elem and elem[-1] in ",;":
            sep, elem = elem[-1], elem[:-1].rstrip()
        opener, closer, flush = _wrap_delims(lang, elem)
        pieces = []
        if before.strip():
            pieces.append(before)
        pieces.append(indent + opener)
        pieces.append(indent + elem)
        pieces.append((closer if flush else indent + closer) + sep)
        if after:
            pieces.append(indent + after)
        return text[:ls] + "\n".join(pieces) + text[le:]
    return fn


def m_herestring_line(line_re, lang="ps"):
    """Wrap the one line that matches in a PowerShell here-string (lang "ps") or multi-line plain string ("psq")."""
    def fn(text):
        ls = text.split("\n")
        hits = [i for i, l in enumerate(ls) if re.search(line_re, l)]
        if len(hits) != 1:
            raise MutationError("%d lines match %r" % (len(hits), line_re))
        i = hits[0]
        indent = re.match(r"[ \t]*", ls[i]).group(0)
        opener, closer, flush = _wrap_delims(lang, ls[i])
        ls[i:i + 1] = [indent + opener, ls[i], closer if flush else indent + closer]
        return "\n".join(ls)
    return fn


def m_stale_copy(start_re, end_re, token_re, lang):
    """An old copy of the whole list sits in a multi-line string above the real list, which lost the entry.

    The statement from the start match to the end match is quoted verbatim in a
    triple-quoted string (or here-string or text block) in front of itself; the
    real statement then has the element taken out. A reader that searches for
    the FIRST `SERVICES = [` finds the stale copy, which still holds the entry.
    """
    def fn(text):
        starts = list(re.finditer(start_re, text, re.M))
        if len(starts) != 1:
            raise MutationError("region start %r matches %d times" % (start_re, len(starts)))
        s = starts[0]
        e = re.search(end_re, text[s.end():], re.S)
        if not e:
            raise MutationError("region end %r not found" % end_re)
        a = text.rfind("\n", 0, s.start()) + 1
        b = s.end() + e.end()
        stmt = text[a:b]
        pat = re.compile(r"(?:" + token_re + r")[ \t]*[,;]?[ \t]*")
        if len(pat.findall(stmt)) != 1:
            raise MutationError("%d elements match %r" % (len(pat.findall(stmt)), token_re))
        real = pat.sub("", stmt, count=1)
        opener, closer, _ = _wrap_delims(lang, stmt)
        return text[:a] + opener + "\n" + stmt + "\n" + closer + "\n" + real + text[b:]
    return fn


def m_comment_all(text):
    """Every non-blank line of a file commented out with a YAML/PowerShell '#'."""
    return "\n".join(("# " + l) if l.strip() else l for l in text.split("\n"))


def m_empty(text):
    """The file is still there, but holds nothing."""
    return ""


def _facts_for_mutator(root):
    """Port and database facts, read with their own small regexes (not the guard's)."""
    out = OrderedDict()
    sdir = root / "services"
    for name in sorted(os.listdir(sdir)):
        mod = sdir / name
        if not (mod / "pom.xml").is_file():
            continue
        res = mod / "src" / "main" / "resources"
        yml = _norm((res / "application.yaml").read_text(encoding="utf-8"))
        m = re.search(r"^server:[ \t]*\n(?:[ \t]+[^\n]*\n|[ \t]*\n)*?[ \t]+port:[ \t]*(\d+)", yml, re.M)
        if not m:
            raise MutationError("cannot find the port of " + name)
        pom = (mod / "pom.xml").read_text(encoding="utf-8")
        migs = [f for f in os.listdir(res / "db" / "migration")] if (res / "db" / "migration").is_dir() else []
        owns = bool(migs) and "flyway" in pom
        info = {"port": int(m.group(1)), "owns": owns, "dbport": None}
        if owns:
            loc = _norm((res / "application-local.yaml").read_text(encoding="utf-8"))
            d = re.search(r"localhost:(\d+)/", loc)
            info["dbport"] = int(d.group(1))
        out[name] = info
    return out


def build_mutations(root):
    """[(service, list_id, kind, path relative to the tree base, fn or None to delete the file)]."""
    A = "oriah-api/"
    muts = []
    facts = _facts_for_mutator(root)

    def add(svc, lid, kind, rel, fn):
        muts.append((svc, lid, kind, rel, fn))

    def ps_wrapped(svc, lid, rel, token_re=None, region_spec=None, line_re=None):
        """A PowerShell list entry moved into a here-string (a plain string that runs over lines is a known gap)."""
        for kind, lang in (("herestring", "ps"),):
            fn = m_herestring_line(line_re, lang) if line_re else m_wrap_token(token_re, lang, region_spec)
            add(svc, lid, kind, rel, fn)

    for s, f in facts.items():
        e = re.escape(s)
        p = f["port"]
        w = p + 500
        # --- removals, every service -------------------------------------
        add(s, "pom-modules", "remove", A + "services/pom.xml", m_delete_line(r"<module>%s</module>" % e))
        add(s, "compose-service", "remove", A + "docker-compose/default/docker-compose.yml",
            m_delete_block(r"^  %s:\s*$" % e))
        add(s, "build-images", "remove", A + "build-images.ps1",
            m_delete_line(r'Path\s*=\s*"services/%s"' % e))
        add(s, "prometheus", "remove", A + "monitoring/prometheus.yml",
            m_delete_line(r"^\s*-\s*'%s:\d+'\s*$" % e))
        add(s, "dev-stack-apps", "remove", A + "scripts/dev-stack.ps1",
            m_remove_token(r"'%s'" % e, (r"^\s*\$JavaApps\s*=\s*@\(", r"\)")))
        add(s, "local-test-services", "remove", A + "scripts/local-dev-test.ps1",
            m_remove_token(r"'%s'" % e, (r"\$Services\s*=\s*@\(", r"\)")))
        add(s, "local-test-ports", "remove", A + "scripts/local-dev-test.ps1",
            m_remove_token(r"\b%s\s*=\s*\d+" % e, (r"^\s*\$Ports\s*=\s*@\{", r"\}")))
        add(s, "capture-openapi", "remove", A + "scripts/capture-openapi.ps1",
            m_remove_token(r'"%s"' % e, (r"^\s*\$services\s*=\s*@\(", r"\)")))
        add(s, "openapi-audit", "remove", A + "scripts/openapi_audit.py",
            m_remove_token(r'"%s"' % e, (r"^SERVICES\s*=\s*\[", r"\]")))
        add(s, "openapi-diff", "remove", A + "scripts/openapi_diff.py",
            m_remove_token(r'"%s"' % e, (r"^SERVICES\s*=\s*\[", r"\]")))
        add(s, "postman-services", "remove", A + "scripts/local-dev/postman_from_openapi.py",
            m_remove_token(r"'%s'\s*:\s*\d+" % e, (r"^SERVICES\s*=\s*\{", r"\}")))
        add(s, "postman-env", "remove", A + "local-dev/postman/oriah-local.postman_environment.json",
            m_delete_line(r'"key":\s*"%sUrl"' % e, json_commas=True))
        add(s, "smoke-routeprobes", "remove", A + "smoke-test.ps1",
            m_delete_line(r"^\s*%s\s*=" % e, (r"^\s*\$routeProbes\s*=\s*\[ordered\]@\{", r"\}")))
        add(s, "smoke-expected", "remove", A + "smoke-test.ps1",
            m_remove_token(r'"%s:\d+"' % e, (r"^\s*\$expected\s*=\s*@\(", r"\)")))
        add(s, "smoke-ports", "remove", A + "smoke-test.ps1",
            m_remove_token(r"\b%s\s*=\s*\d+" % e, (r"^\s*\$servicePorts\s*=\s*\[ordered\]@\{", r"\}")))
        add(s, "run-compound", "remove", A + ".run/2-services-local.run.xml",
            m_delete_line(r'<toRun\s+name="%s :\d+ \(local\)"' % e))
        add(s, "run-file", "remove", A + ".run/%s-local.run.xml" % s, None)
        add(s, "gateway-docs", "remove",
            A + "infra/gatewayserver/src/main/java/com/oriah/gatewayserver/config/GatewayRoutesConfig.java",
            m_remove_token(r'"%s"' % e, (r"DOCS_SERVICES\s*=\s*List\.of\(", r"\)\s*;")))
        add(s, "gateway-swagger", "remove", A + "infra/gatewayserver/src/main/resources/application.yaml",
            m_delete_line(r"-\s*\{\s*name:\s*%s\s*," % e))
        add(s, "config-repo", "remove", "config-repo/%s.yaml" % s, None)
        # --- commented out, every list that can hold a comment -------------
        # A comment is not an entry. (postman-env and pgadmin are JSON, which has no
        # comments; config-repo is a file name, not a list.)
        add(s, "pom-modules", "comment", A + "services/pom.xml", m_wrap_line(r"<module>%s</module>" % e))
        add(s, "compose-service", "comment", A + "docker-compose/default/docker-compose.yml",
            m_comment_block(r"^  %s:\s*$" % e))
        add(s, "build-images", "comment", A + "build-images.ps1",
            m_comment_line(r'Path\s*=\s*"services/%s"' % e))
        add(s, "prometheus", "comment", A + "monitoring/prometheus.yml",
            m_comment_line(r"^\s*-\s*'%s:\d+'\s*$" % e))
        add(s, "dev-stack-apps", "comment", A + "scripts/dev-stack.ps1",
            m_comment_token(r"'%s'" % e, "# ", (r"^\s*\$JavaApps\s*=\s*@\(", r"\)")))
        add(s, "local-test-services", "comment", A + "scripts/local-dev-test.ps1",
            m_comment_token(r"'%s'" % e, "# ", (r"\$Services\s*=\s*@\(", r"\)")))
        add(s, "local-test-ports", "comment", A + "scripts/local-dev-test.ps1",
            m_comment_token(r"\b%s\s*=\s*\d+" % e, "# ", (r"^\s*\$Ports\s*=\s*@\{", r"\}")))
        add(s, "capture-openapi", "comment", A + "scripts/capture-openapi.ps1",
            m_comment_token(r'"%s"' % e, "# ", (r"^\s*\$services\s*=\s*@\(", r"\)")))
        add(s, "openapi-audit", "comment", A + "scripts/openapi_audit.py",
            m_comment_token(r'"%s"' % e, "# ", (r"^SERVICES\s*=\s*\[", r"\]")))
        add(s, "openapi-diff", "comment", A + "scripts/openapi_diff.py",
            m_comment_token(r'"%s"' % e, "# ", (r"^SERVICES\s*=\s*\[", r"\]")))
        add(s, "postman-services", "comment", A + "scripts/local-dev/postman_from_openapi.py",
            m_comment_token(r"'%s'\s*:\s*\d+" % e, "# ", (r"^SERVICES\s*=\s*\{", r"\}")))
        add(s, "smoke-routeprobes", "comment", A + "smoke-test.ps1",
            m_comment_line(r"^\s*%s\s*=\s*'/" % e))
        add(s, "smoke-expected", "comment", A + "smoke-test.ps1",
            m_comment_token(r'"%s:\d+"' % e, "# ", (r"^\s*\$expected\s*=\s*@\(", r"\)")))
        add(s, "smoke-ports", "comment", A + "smoke-test.ps1",
            m_comment_token(r"\b%s\s*=\s*\d+" % e, "# ", (r"^\s*\$servicePorts\s*=\s*\[ordered\]@\{", r"\}")))
        add(s, "run-compound", "comment", A + ".run/2-services-local.run.xml",
            m_wrap_line(r'<toRun\s+name="%s :\d+ \(local\)"' % e))
        add(s, "run-file", "comment", A + ".run/%s-local.run.xml" % s, m_wrap_file)
        add(s, "gateway-docs", "comment",
            A + "infra/gatewayserver/src/main/java/com/oriah/gatewayserver/config/GatewayRoutesConfig.java",
            m_comment_token(r'"%s"' % e, "// ", (r"DOCS_SERVICES\s*=\s*List\.of\(", r"\)\s*;")))
        add(s, "gateway-swagger", "comment", A + "infra/gatewayserver/src/main/resources/application.yaml",
            m_comment_line(r"-\s*\{\s*name:\s*%s\s*," % e))
        # a configuration file that exists but says nothing (config-repo is a folder next
        # to oriah-api; "comment" here comments the WHOLE file out, "empty" empties it)
        add(s, "config-repo", "comment", "config-repo/%s.yaml" % s, m_comment_all)
        add(s, "config-repo", "empty", "config-repo/%s.yaml" % s, m_empty)
        # --- the entry moved into a multi-line string: text, not a list element ---------
        # herestring: PowerShell here-strings; docstring: Python triple-quoted strings
        # (docstring-copy: an old copy of the whole list quoted above the real one, which
        # lost the entry); textblock: the Java text block. A comment is not an entry, and
        # neither is the body of a multi-line string.
        ps_wrapped(s, "build-images", A + "build-images.ps1", line_re=r'Path\s*=\s*"services/%s"' % e)
        ps_wrapped(s, "dev-stack-apps", A + "scripts/dev-stack.ps1",
                   r"'%s'" % e, (r"^\s*\$JavaApps\s*=\s*@\(", r"\)"))
        ps_wrapped(s, "local-test-services", A + "scripts/local-dev-test.ps1",
                   r"'%s'" % e, (r"\$Services\s*=\s*@\(", r"\)"))
        ps_wrapped(s, "local-test-ports", A + "scripts/local-dev-test.ps1",
                   r"\b%s\s*=\s*\d+" % e, (r"^\s*\$Ports\s*=\s*@\{", r"\}"))
        ps_wrapped(s, "capture-openapi", A + "scripts/capture-openapi.ps1",
                   r'"%s"' % e, (r"^\s*\$services\s*=\s*@\(", r"\)"))
        ps_wrapped(s, "smoke-routeprobes", A + "smoke-test.ps1", line_re=r"^\s*%s\s*=\s*'/" % e)
        ps_wrapped(s, "smoke-expected", A + "smoke-test.ps1",
                   r'"%s:\d+"' % e, (r"^\s*\$expected\s*=\s*@\(", r"\)"))
        ps_wrapped(s, "smoke-ports", A + "smoke-test.ps1",
                   r"\b%s\s*=\s*\d+" % e, (r"^\s*\$servicePorts\s*=\s*\[ordered\]@\{", r"\}"))
        add(s, "openapi-audit", "docstring", A + "scripts/openapi_audit.py",
            m_wrap_token(r'"%s"' % e, "py", (r"^SERVICES\s*=\s*\[", r"\]")))
        add(s, "openapi-diff", "docstring", A + "scripts/openapi_diff.py",
            m_wrap_token(r'"%s"' % e, "py", (r"^SERVICES\s*=\s*\[", r"\]")))
        add(s, "postman-services", "docstring", A + "scripts/local-dev/postman_from_openapi.py",
            m_wrap_token(r"'%s'\s*:\s*\d+" % e, "py", (r"^SERVICES\s*=\s*\{", r"\}")))
        add(s, "openapi-audit", "docstring-copy", A + "scripts/openapi_audit.py",
            m_stale_copy(r"^SERVICES\s*=\s*\[", r"\]", r'"%s"' % e, "py"))
        add(s, "openapi-diff", "docstring-copy", A + "scripts/openapi_diff.py",
            m_stale_copy(r"^SERVICES\s*=\s*\[", r"\]", r'"%s"' % e, "py"))
        add(s, "postman-services", "docstring-copy", A + "scripts/local-dev/postman_from_openapi.py",
            m_stale_copy(r"^SERVICES\s*=\s*\{", r"\}", r"'%s'\s*:\s*\d+" % e, "py"))
        add(s, "gateway-docs", "textblock",
            A + "infra/gatewayserver/src/main/java/com/oriah/gatewayserver/config/GatewayRoutesConfig.java",
            m_wrap_token(r'"%s"' % e, "java", (r"DOCS_SERVICES\s*=\s*List\.of\(", r"\)\s*;")))
        # --- wrong port, every service ------------------------------------
        add(s, "compose-service", "port", A + "docker-compose/default/docker-compose.yml",
            m_replace(r'"127\.0\.0\.1:%d:%d"' % (p, p), lambda m, w=w, p=p: '"127.0.0.1:%d:%d"' % (p, w)))
        add(s, "compose-service", "health", A + "docker-compose/default/docker-compose.yml",
            m_replace(r"/dev/tcp/localhost/%d\"" % p, lambda m, w=w: '/dev/tcp/localhost/%d"' % w))
        add(s, "prometheus", "port", A + "monitoring/prometheus.yml",
            m_replace(r"'%s:%d'" % (e, p), lambda m, w=w, s=s: "'%s:%d'" % (s, w)))
        add(s, "local-test-ports", "port", A + "scripts/local-dev-test.ps1",
            m_replace(r"\b(%s\s*=\s*)%d\b" % (e, p), lambda m, w=w: m.group(1) + str(w),
                      (r"^\s*\$Ports\s*=\s*@\{", r"\}")))
        add(s, "postman-services", "port", A + "scripts/local-dev/postman_from_openapi.py",
            m_replace(r"('%s'\s*:\s*)%d\b" % (e, p), lambda m, w=w: m.group(1) + str(w),
                      (r"^SERVICES\s*=\s*\{", r"\}")))
        add(s, "postman-env", "port", A + "local-dev/postman/oriah-local.postman_environment.json",
            m_replace(r'("key":\s*"%sUrl"[^\n]*?localhost:)%d\b' % (e, p), lambda m, w=w: m.group(1) + str(w)))
        add(s, "smoke-expected", "port", A + "smoke-test.ps1",
            m_replace(r'"%s:%d"' % (e, p), lambda m, w=w, s=s: '"%s:%d"' % (s, w),
                      (r"^\s*\$expected\s*=\s*@\(", r"\)")))
        add(s, "smoke-ports", "port", A + "smoke-test.ps1",
            m_replace(r"\b(%s\s*=\s*)%d\b" % (e, p), lambda m, w=w: m.group(1) + str(w),
                      (r"^\s*\$servicePorts\s*=\s*\[ordered\]@\{", r"\}")))
        add(s, "run-compound", "port", A + ".run/2-services-local.run.xml",
            m_replace(r'(<toRun\s+name="%s :)%d( \(local\)")' % (e, p),
                      lambda m, w=w: m.group(1) + str(w) + m.group(2)))
        # --- database owners only -----------------------------------------
        if f["owns"]:
            h = f["dbport"]
            hw = h + 500
            add(s, "compose-postgres", "remove", A + "docker-compose/default/docker-compose.yml",
                m_delete_block(r"^  postgres-%s:\s*$" % e))
            add(s, "compose-volume", "remove", A + "docker-compose/default/docker-compose.yml",
                m_delete_line(r"^  postgres-%s-data:\s*$" % e))
            add(s, "dev-stack-backing", "remove", A + "scripts/dev-stack.ps1",
                m_remove_token(r"'postgres-%s'" % e, (r"^\s*\$Backing\s*=\s*@\(", r"\)")))
            add(s, "local-test-postgres", "remove", A + "scripts/local-dev-test.ps1",
                m_remove_token(r"'%s'" % e, (r"^\s*\$backing\s*=", r"ForEach-Object")))
            add(s, "pgadmin", "remove", A + "local-dev/pgadmin-servers.json",
                m_delete_line(r'"Name":\s*"%s"\s*,' % e, json_commas=True))
            add(s, "compose-postgres", "comment", A + "docker-compose/default/docker-compose.yml",
                m_comment_block(r"^  postgres-%s:\s*$" % e))
            add(s, "compose-volume", "comment", A + "docker-compose/default/docker-compose.yml",
                m_comment_line(r"^  postgres-%s-data:\s*$" % e))
            add(s, "dev-stack-backing", "comment", A + "scripts/dev-stack.ps1",
                m_comment_token(r"'postgres-%s'" % e, "# ", (r"^\s*\$Backing\s*=\s*@\(", r"\)")))
            add(s, "local-test-postgres", "comment", A + "scripts/local-dev-test.ps1",
                m_comment_token(r"'%s'" % e, "# ", (r"^\s*\$backing\s*=", r"ForEach-Object")))
            # The Flyway dependency is what makes a service a database owner. A -test
            # dependency (test scope) is not one, and neither is one in a comment.
            add(s, "service-facts", "flyway-test-only", A + "services/%s/pom.xml" % s, m_flyway_main("remove"))
            add(s, "service-facts", "flyway-commented", A + "services/%s/pom.xml" % s, m_flyway_main("comment"))
            # Having "flyway" in the pom is not enough: Spring Boot 4 runs the migrations
            # through spring-boot-starter-flyway only. Take only the starter
            # away, make the dependencies provided, or leave only an un-scoped -test starter.
            add(s, "service-facts", "flyway-no-starter", A + "services/%s/pom.xml" % s, m_flyway_variant("no-starter"))
            add(s, "service-facts", "flyway-provided", A + "services/%s/pom.xml" % s, m_flyway_variant("provided"))
            add(s, "service-facts", "flyway-test-unscoped", A + "services/%s/pom.xml" % s,
                m_flyway_variant("test-unscoped"))
            ps_wrapped(s, "dev-stack-backing", A + "scripts/dev-stack.ps1",
                       r"'postgres-%s'" % e, (r"^\s*\$Backing\s*=\s*@\(", r"\)"))
            ps_wrapped(s, "local-test-postgres", A + "scripts/local-dev-test.ps1",
                       r"'%s'" % e, (r"^\s*\$backing\s*=", r"ForEach-Object"))
            add(s, "compose-postgres", "port", A + "docker-compose/default/docker-compose.yml",
                m_replace(r'"127\.0\.0\.1:%d:5432"' % h, lambda m, hw=hw: '"127.0.0.1:%d:5432"' % hw))
            add(s, "pgadmin", "port", A + "local-dev/pgadmin-servers.json",
                m_replace(r'("Name":\s*"%s"\s*,[^\n]*?"Port":\s*)%d\b' % (e, h),
                          lambda m, hw=hw: m.group(1) + str(hw)))
        else:
            add(s, "local-test-nodb", "remove", A + "scripts/local-dev-test.ps1",
                m_remove_token(r"'%s'" % e, (r"^\s*\$NoDatabase\s*=\s*@\(", r"\)")))
            add(s, "local-test-nodb", "comment", A + "scripts/local-dev-test.ps1",
                m_comment_token(r"'%s'" % e, "# ", (r"^\s*\$NoDatabase\s*=\s*@\(", r"\)")))
            ps_wrapped(s, "local-test-nodb", A + "scripts/local-dev-test.ps1",
                       r"'%s'" % e, (r"^\s*\$NoDatabase\s*=\s*@\(", r"\)"))
    return muts


_PROBLEM = re.compile(r"^PROBLEM \[([^\]]*)\] ([\w-]+) \((.*?)\): (.*)$")


def _problems(output):
    return [m.groups() for m in (_PROBLEM.match(l) for l in output.splitlines()) if m]


_IGNORE = shutil.ignore_patterns(".git", "target", ".idea", ".codex-tmp", "node_modules", "docs", ".mvn", "test")


def _make_tree(src_root, dest_base):
    """A copy of oriah-api and of its sibling config-repo, without the heavy folders."""
    shutil.copytree(src_root, dest_base / "oriah-api", ignore=_IGNORE)
    cfg = src_root.parent / "config-repo"
    if cfg.is_dir():
        shutil.copytree(cfg, dest_base / "config-repo", ignore=shutil.ignore_patterns(".git"))


def _run_guard_subprocess(tree_base):
    p = subprocess.run([sys.executable, str(HERE), "--root", str(tree_base / "oriah-api")],
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    return p.returncode, p.stdout + p.stderr


def selftest(root):
    root = Path(root).resolve()
    if not (root / "services" / "pom.xml").is_file():
        print("ERROR: %s does not look like oriah-api" % root)
        return 2
    tmp = Path(tempfile.mkdtemp(prefix="registry-selftest-"))
    try:
        pristine = tmp / "pristine"
        pristine.mkdir()
        _make_tree(root, pristine)

        code, out = _run_guard_subprocess(pristine)
        base_problems = set((s, l) for s, l, _, _ in _problems(out))
        base_lines = set(out.splitlines())
        ok_untouched = code == 0
        if ok_untouched:
            print("selftest: untouched copy -> guard exits 0 (as it must)")
        else:
            print("selftest: untouched copy -> guard exits %d (it MUST exit 0): %d problem line(s)"
                  % (code, len(_problems(out))))
            for l in out.splitlines()[:12]:
                print("    " + l)

        muts = build_mutations(pristine / "oriah-api")
        jobs, skipped = [], []
        for m in muts:
            if (m[0], m[1]) in base_problems:
                skipped.append(m)           # that service/list is already red; it cannot show a NEW failure
            else:
                jobs.append(m)

        workers = max(1, min(4, os.cpu_count() or 1))
        trees = []
        for i in range(workers):
            d = tmp / ("w%d" % i)
            d.mkdir()
            shutil.copytree(pristine / "oriah-api", d / "oriah-api")
            if (pristine / "config-repo").is_dir():
                shutil.copytree(pristine / "config-repo", d / "config-repo")
            trees.append(d)
        free = list(trees)
        lock = threading.Lock()

        def run_one(job):
            svc, lid, kind, rel, fn = job
            with lock:
                base = free.pop()
            try:
                path = base / rel
                if not path.is_file():
                    return job, False, "target file %s does not exist" % rel
                orig = path.read_bytes()
                try:
                    if fn is None:
                        path.unlink()
                    else:
                        old = _norm(orig.decode("utf-8"))
                        new = fn(old)
                        if new == old:
                            return job, False, "the mutation changed nothing"
                        path.write_bytes(new.encode("utf-8"))
                    code, out = _run_guard_subprocess(base)
                except MutationError as exc:
                    return job, False, "mutation did not apply cleanly: %s" % exc
                finally:
                    path.write_bytes(orig)
                new_lines = [l for l in out.splitlines() if l not in base_lines]
                named = [p for p in _problems("\n".join(new_lines)) if p[0] == svc and p[1] == lid]
                if code == 0:
                    return job, False, "guard still exits 0"
                if not named:
                    return job, False, "guard went red but reported no new problem for [%s] in %s" % (svc, lid)
                if not re.search(r"\b%s\b" % re.escape(svc), "\n".join(new_lines)):
                    return job, False, "the output does not name the service"
                return job, True, ""
            finally:
                with lock:
                    free.append(base)

        with ThreadPoolExecutor(max_workers=workers) as pool:
            results = list(pool.map(run_one, jobs))

        caught = sum(1 for _, ok, _ in results if ok)
        missed = [(j, why) for j, ok, why in results if not ok]
        by_list = Counter(j[1] for j, ok, _ in results if ok)
        for lid in LIST_IDS:
            if by_list.get(lid):
                print("  %-22s %3d caught" % (lid, by_list[lid]))
        for (svc, lid, kind, rel, _), why in missed:
            print("  MISSED [%s] %s (%s): %s" % (svc, lid, kind, why))
        if skipped:
            pairs = sorted(set((m[0], m[1]) for m in skipped))
            print("selftest: %d mutation(s) not run because the guard already reports a problem there: %s"
                  % (len(skipped), ", ".join("[%s] %s" % p for p in pairs[:8]) + (" ..." if len(pairs) > 8 else "")))
        kinds_caught = Counter(j[2] for j, ok, _ in results if ok)
        kinds_all = Counter(j[2] for j in jobs)
        print("selftest: by kind: " + ", ".join("%s %d/%d" % (k, kinds_caught.get(k, 0), kinds_all[k])
                                                for k in sorted(kinds_all)))
        print("selftest: %d/%d mutations caught" % (caught, len(jobs)))
        return 0 if (caught == len(jobs) and ok_untouched and jobs) else 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main(argv):
    ap = argparse.ArgumentParser(description="Check that every domain service is wired into every list that names the services.")
    ap.add_argument("--root", default=str(DEFAULT_ROOT), help="the oriah-api folder (default: this script's parent)")
    ap.add_argument("--selftest", action="store_true", help="prove the guard can fail, on a temp copy of the tree")
    args = ap.parse_args(argv)
    try:
        sys.stdout.reconfigure(errors="replace")
    except AttributeError:
        pass
    if args.selftest:
        return selftest(args.root)
    return guard_main(args.root)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
