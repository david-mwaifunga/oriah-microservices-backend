<#
.SYNOPSIS
    Static validation of the Keycloak realm import file.

.DESCRIPTION
    Catches realm-import errors without needing a fresh Keycloak, which is the
    whole point: these errors only surface on a FRESH import (which comes from
    removing only Keycloak's volume, default_postgres-keycloak-data -- never
    `docker compose down -v`, which wipes all twelve databases), so a stack
    running against an existing volume will look perfectly healthy while the
    realm file is broken and nobody can stand the stack up from scratch.

    That is exactly what happened. `default-roles-oriah` was made a composite
    referencing the realm roles `offline_access` and `uma_authorization`, which
    Keycloak normally auto-creates for a new realm -- but an import that
    supplies `roles.realm` explicitly replaces the defaults, so they were never
    created. Keycloak then refused to boot with:

        ERROR: Unable to find composite realm role: uma_authorization

    The session that introduced it had Keycloak already imported in a docker
    volume, so it never saw the failure.

    Checks performed:
      1. The file is well-formed JSON.
      2. Every realm role named in a composite's `composites.realm` is itself
         declared in `roles.realm`.
      3. Every client named in a composite's `composites.client` is either
         declared in `clients` or is one of Keycloak's built-in clients
         (which ARE auto-created even when roles are supplied explicitly).
      4. `defaultRole` names a role that exists.
      5. Every role assigned to a user in `users[].realmRoles` is declared.
      6. No realm role carries a field outside Keycloak's RoleRepresentation,
         since its Jackson config rejects unknown properties outright (this is
         why the file cannot carry "_comment" notes).

.EXAMPLE
    powershell.exe -ExecutionPolicy Bypass -File .\scripts\validate-realm.ps1
#>
# -UsersFiles: Keycloak users files imported beside the realm -- the dev
# stack's keycloak/dev/oriah-users-0.json (MVP_PLAN Phase 0, item 5). A users
# file declares no roles of its own, so it can only be checked AGAINST the
# realm: a role it names that the realm does not declare stops Keycloak from
# importing it.
#
# Both defaults are found from this script's own folder, so the check runs the
# same from anywhere. A path you pass is taken as you give it.
param(
    [string] $RealmFile = "",
    [string[]] $UsersFiles = @()
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
if (-not $RealmFile) { $RealmFile = Join-Path $repo "keycloak/import/oriah-realm.json" }
if ($UsersFiles.Count -eq 0) { $UsersFiles = @(Join-Path $repo "keycloak/dev/oriah-users-0.json") }

if (-not (Test-Path $RealmFile)) {
    Write-Output "FAIL: realm file not found at $RealmFile"
    exit 1
}

$validator = @'
import json, sys

path = sys.argv[1]

# Clients Keycloak creates for every realm regardless of what the import says.
BUILTIN_CLIENTS = {
    "account", "account-console", "admin-cli", "broker",
    "realm-management", "security-admin-console",
}

# Keycloak deserializes roles into RoleRepresentation with Jackson configured to
# REJECT unknown properties, so a stray key fails the whole import with
# "Unrecognized field ... not marked as ignorable". In particular the realm file
# cannot carry explanatory comments: there is no comment syntax in JSON, and a
# "_comment" key is an unknown property. (Learned the hard way while fixing the
# composite-role problem above.) Explanations belong in this script or in
# docs/ARCHITECTURE.md instead.
ROLE_FIELDS = {
    "clientRole", "composite", "scopeParamRequired", "id", "description",
    "attributes", "name", "composites", "containerId",
}

try:
    with open(path, encoding="utf-8") as fh:
        realm = json.load(fh)
except json.JSONDecodeError as exc:
    print(f"FAIL: {path} is not valid JSON: {exc}")
    sys.exit(1)

problems = []
realm_roles = (realm.get("roles") or {}).get("realm") or []
declared_realm = {r.get("name") for r in realm_roles}
client_roles = (realm.get("roles") or {}).get("client") or {}
declared_clients = {c.get("clientId") for c in (realm.get("clients") or [])}

for role in realm_roles:
    unknown = set(role) - ROLE_FIELDS
    if unknown:
        problems.append(
            f"realm role '{role.get('name')}' carries unknown field(s) "
            f"{sorted(unknown)}. Keycloak rejects the entire import with "
            f"\"Unrecognized field ... not marked as ignorable\". JSON has no "
            f"comment syntax, so notes cannot live in this file."
        )

    if not role.get("composite"):
        continue
    composites = role.get("composites") or {}
    for target in composites.get("realm") or []:
        if target not in declared_realm:
            problems.append(
                f"composite realm role '{role.get('name')}' references realm role "
                f"'{target}', which is not declared in roles.realm. Keycloak will "
                f"refuse to start: \"Unable to find composite realm role: {target}\". "
                f"Declare it explicitly -- supplying roles.realm replaces the "
                f"built-in defaults."
            )
    for client, names in (composites.get("client") or {}).items():
        if client not in declared_clients and client not in BUILTIN_CLIENTS:
            problems.append(
                f"composite realm role '{role.get('name')}' references client "
                f"'{client}', which is neither declared in clients nor a Keycloak "
                f"built-in client"
            )
        for name in names:
            if client in declared_clients:
                known = {r.get("name") for r in client_roles.get(client) or []}
                if known and name not in known:
                    problems.append(
                        f"composite realm role '{role.get('name')}' references client "
                        f"role '{client}:{name}', which is not declared in "
                        f"roles.client.{client}"
                    )

default_role = (realm.get("defaultRole") or {}).get("name")
if default_role and default_role not in declared_realm:
    problems.append(
        f"defaultRole is '{default_role}', which is not declared in roles.realm"
    )

for user in realm.get("users") or []:
    for name in user.get("realmRoles") or []:
        if name not in declared_realm:
            problems.append(
                f"user '{user.get('username')}' is assigned realm role '{name}', "
                f"which is not declared in roles.realm"
            )

# Users files imported beside the realm (<realm>-users-<n>.json).
for users_path in sys.argv[2:]:
    try:
        with open(users_path, encoding="utf-8") as fh:
            users_file = json.load(fh)
    except (OSError, json.JSONDecodeError) as exc:
        problems.append(f"users file {users_path} cannot be read as JSON: {exc}")
        continue
    if users_file.get("realm") != realm.get("realm"):
        problems.append(
            f"users file {users_path} is for realm '{users_file.get('realm')}', "
            f"not '{realm.get('realm')}'"
        )
    extra = set(users_file) - {"realm", "users"}
    if extra:
        problems.append(
            f"users file {users_path} carries {sorted(extra)}: a users file holds "
            f"people only. Anything more belongs in the realm -- a second realm "
            f"file REPLACES the first on import, it is never merged"
        )
    for user in users_file.get("users") or []:
        for name in user.get("realmRoles") or []:
            if name not in declared_realm:
                problems.append(
                    f"user '{user.get('username')}' in {users_path} is assigned realm "
                    f"role '{name}', which is not declared in the realm"
                )
    names = ", ".join(u.get("username", "?") for u in users_file.get("users") or [])
    print(f"users file:     {users_path} -> {names}")

print(f"realm:          {realm.get('realm')}")
print(f"realm roles:    {len(declared_realm)} declared -> {', '.join(sorted(n for n in declared_realm if n))}")
print(f"clients:        {', '.join(sorted(c for c in declared_clients if c))}")
print(f"users:          {', '.join(u.get('username','?') for u in realm.get('users') or [])}")

if problems:
    print(f"\nFAIL: {len(problems)} realm-import problem(s):")
    for p in problems:
        print(f"  - {p}")
    sys.exit(1)

print("\nPASS: every composite, defaultRole and user role assignment resolves.")
sys.exit(0)
'@
$validatorPath = Join-Path $env:TEMP "oriah-validate-realm.py"
Set-Content -Path $validatorPath -Value $validator -Encoding utf8

foreach ($usersFile in $UsersFiles) {
    if (-not (Test-Path $usersFile)) {
        Write-Output "FAIL: users file not found at $usersFile"
        exit 1
    }
}
python $validatorPath $RealmFile @UsersFiles
exit $LASTEXITCODE
