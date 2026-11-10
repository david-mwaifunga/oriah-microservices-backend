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
  * owns a database  <- at least one Flyway migration AND a flyway dependency
                        in its pom (a folder alone proves nothing)
  * its database     <- application-local.yaml (host port, database, user,
                        password) and application-docker.yaml (host name)
  * its main class   <- the class annotated @SpringBootApplication

A service that owns no database (shipping and checkout today) is therefore
expected to have NO postgres-<name> container, NO pgAdmin entry and so on,
without any hand-kept "these are stateless" list in this file.

The checks are EXACT, not "is it mentioned somewhere": each list must hold every
service, with the right port, and nothing extra and nothing twice.

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
guard exits 0 on an untouched copy.

The part that damages the files (the MUTATOR, below the line marked "SELF-TEST")
shares NO parsing code with the guard above that line: it uses plain line
deletion and text substitution. If both used the same parser, one blind spot
would hide a missing entry from both of them.
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
        return p.read_text(encoding="utf-8-sig")

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
        t = f.read_text(encoding="utf-8", errors="replace")
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
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    return re.findall(r"<module>\s*([^<\s]+)\s*</module>", text)


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
            port = yaml_child(app.read_text(encoding="utf-8-sig"), "server", "port")
            svc.port = int(port) if port and port.isdigit() else None
        if svc.port is None:
            rep.add(name, "service-facts", "services/%s/src/main/resources/application.yaml" % name,
                    "no server.port found")
        mig = res / "db" / "migration"
        svc.migrations = sorted(p.name for p in mig.glob("V*__*.sql")) if mig.is_dir() else []
        pom = (mod / "pom.xml").read_text(encoding="utf-8-sig")
        svc.flyway = re.search(r"<artifactId>[^<]*flyway[^<]*</artifactId>", pom) is not None
        svc.owns_db = bool(svc.migrations) and svc.flyway
        if bool(svc.migrations) != svc.flyway:
            rep.add(name, "service-facts", "services/%s/pom.xml" % name,
                    "%s: it has %d migration file(s) but %s a flyway dependency; a database owner has both"
                    % (name, len(svc.migrations), "has" if svc.flyway else "NO"))
        if svc.owns_db:
            loc = res / "application-local.yaml"
            dock = res / "application-docker.yaml"
            lt = loc.read_text(encoding="utf-8-sig") if loc.is_file() else ""
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
            dt = dock.read_text(encoding="utf-8-sig") if dock.is_file() else ""
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
            port = yaml_child(app.read_text(encoding="utf-8-sig"), "server", "port")
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
        t = routes.read_text(encoding="utf-8-sig")
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


def check_config_repo(ctx, rep):
    if not ctx.cfg.is_dir():
        rep.add("(all)", "config-repo", "../config-repo", "the config-repo folder was not found next to oriah-api")
        return
    present = {p.name[:-len(".yaml")] for p in ctx.cfg.glob("*.yaml")}
    for n in ctx.services:
        if n not in present:
            rep.add(n, "config-repo", "../config-repo/%s.yaml" % n,
                    "no configuration file for %s (the Config Server serves committed files only)" % n)
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
            add(s, "compose-postgres", "port", A + "docker-compose/default/docker-compose.yml",
                m_replace(r'"127\.0\.0\.1:%d:5432"' % h, lambda m, hw=hw: '"127.0.0.1:%d:5432"' % hw))
            add(s, "pgadmin", "port", A + "local-dev/pgadmin-servers.json",
                m_replace(r'("Name":\s*"%s"\s*,[^\n]*?"Port":\s*)%d\b' % (e, h),
                          lambda m, hw=hw: m.group(1) + str(hw)))
        else:
            add(s, "local-test-nodb", "remove", A + "scripts/local-dev-test.ps1",
                m_remove_token(r"'%s'" % e, (r"^\s*\$NoDatabase\s*=\s*@\(", r"\)")))
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
