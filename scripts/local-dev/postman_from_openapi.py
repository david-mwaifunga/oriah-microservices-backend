"""Builds the Postman collection for local development from the running apps.

Every endpoint of the 12 services, read from their own OpenAPI descriptions
(through the gateway's /v3/api-docs/<service>), so the collection is never
hand-maintained and never drifts. Each request goes through the gateway --
the path the storefront uses -- unless the gateway does not route that path,
in which case it goes straight to the service's port and says so. Every
request sends the token stored by the "0. Log in" requests.

Needs the platform and all 12 services running (docs/LOCAL_DEV.md).

    python scripts/local-dev/postman_from_openapi.py            # write the collection
    python scripts/local-dev/postman_from_openapi.py --check    # also call every
        parameter-free GET as maya and fail on a routing error (404 from the
        gateway, or any 5xx)
"""
import json
import pathlib
import re
import sys
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / 'local-dev' / 'postman' / 'oriah-local.postman_collection.json'
GATEWAY = 'http://localhost:8080'
PLATFORM = {'configserver': 8888, 'eurekaserver': 8761, 'gatewayserver': 8080}
SERVICES = {'catalog': 8081, 'inventory': 8082, 'customer': 8083, 'cart': 8084, 'payment': 8085,
            'order': 8086, 'promotion': 8087, 'shipping': 8088, 'checkout': 8089,
            'notification': 8090, 'review': 8091, 'search': 8092}
ROUTES_SRC = ROOT / 'infra' / 'gatewayserver' / 'src' / 'main' / 'java' / 'com' / 'oriah' / \
    'gatewayserver' / 'config' / 'GatewayRoutesConfig.java'


def gateway_patterns():
    """The gateway's route paths, read from its source so the two cannot disagree."""
    found = set(re.findall(r'"(/api/v1/[^"]+)"', ROUTES_SRC.read_text(encoding='utf-8')))
    regexes = []
    for p in found:
        rx = re.escape(p).replace(r'/\*\*', '(/.*)?').replace(r'\*', '[^/]+')
        regexes.append(re.compile('^' + rx + '$'))
    return regexes


def routed(path, regexes):
    concrete = re.sub(r'\{[^}]+\}', 'x', path)
    return any(r.match(concrete) for r in regexes)


def fetch_json(url, token=None):
    req = urllib.request.Request(url, headers={'Authorization': f'Bearer {token}'} if token else {})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode('utf-8'))


def example(schema, spec, depth=0):
    """A small JSON example for a schema, following $ref."""
    if depth > 6 or not isinstance(schema, dict):
        return None
    if '$ref' in schema:
        name = schema['$ref'].split('/')[-1]
        return example(spec.get('components', {}).get('schemas', {}).get(name, {}), spec, depth + 1)
    if 'example' in schema:
        return schema['example']
    if 'enum' in schema:
        return schema['enum'][0]
    for key in ('allOf', 'oneOf', 'anyOf'):
        if key in schema and schema[key]:
            return example(schema[key][0], spec, depth + 1)
    t = schema.get('type')
    if t == 'object' or 'properties' in schema:
        return {k: example(v, spec, depth + 1) for k, v in schema.get('properties', {}).items()}
    if t == 'array':
        item = example(schema.get('items', {}), spec, depth + 1)
        return [] if item is None else [item]
    if t == 'integer':
        return 1
    if t == 'number':
        return 0.0
    if t == 'boolean':
        return False
    fmt = schema.get('format')
    return {'uuid': '00000000-0000-0000-0000-000000000000', 'date-time': '2026-11-08T10:00:00Z',
            'date': '2026-11-08', 'binary': None}.get(fmt, '')


def request_item(svc, method, path, op, spec, regexes):
    through_gateway = routed(path, regexes)
    base = '{{baseUrl}}' if through_gateway else '{{' + svc + 'Url}}'
    pm_path = re.sub(r'\{([^}]+)\}', r':\1', path)
    params = op.get('parameters', [])
    query = [{'key': p['name'], 'value': '', 'disabled': not p.get('required', False),
              'description': p.get('description', '')} for p in params if p.get('in') == 'query']
    variables = [{'key': p['name'], 'value': '', 'description': p.get('description', '')}
                 for p in params if p.get('in') == 'path']
    headers = []
    for p in params:
        if p.get('in') == 'header':
            value = '{{$guid}}' if p['name'].lower() == 'idempotency-key' else ''
            headers.append({'key': p['name'], 'value': value, 'disabled': not p.get('required', False)
                            and value == '', 'description': p.get('description', '')})
    raw = base + pm_path + ('?' + '&'.join(f"{q['key']}=" for q in query if not q['disabled']) if any(not q['disabled'] for q in query) else '')
    url = {'raw': raw, 'host': [base], 'path': [s for s in pm_path.split('/') if s],
           'query': query, 'variable': variables}
    req = {'method': method.upper(), 'header': headers, 'url': url}
    content = op.get('requestBody', {}).get('content', {})
    if 'application/json' in content:
        body = example(content['application/json'].get('schema', {}), spec)
        req['body'] = {'mode': 'raw', 'raw': json.dumps(body, indent=2),
                       'options': {'raw': {'language': 'json'}}}
    elif 'multipart/form-data' in content:
        props = content['multipart/form-data'].get('schema', {}).get('properties', {})
        req['body'] = {'mode': 'formdata', 'formdata': [
            {'key': k, 'type': 'file' if v.get('format') == 'binary' else 'text', 'src': [] if v.get('format') == 'binary' else None,
             'value': '' if v.get('format') != 'binary' else None} for k, v in props.items()]}
    notes = [op.get('description') or '']
    if not through_gateway:
        notes.insert(0, f'**Not routed by the gateway** -- internal to the services, so this calls {svc} directly on port {SERVICES[svc]}.')
    req['description'] = '\n\n'.join(n for n in notes if n)
    return {'name': op.get('summary') or f'{method.upper()} {path}', 'request': req}


def login(user, role):
    return {
        'name': f'as {user} ({role})',
        'event': [{'listen': 'test', 'script': {'type': 'text/javascript', 'exec': [
            'const body = pm.response.json();',
            "pm.test('logged in', () => pm.expect(body.access_token, 'access_token').to.be.a('string'));",
            "pm.environment.set('bearerToken', body.access_token);",
            f"console.log('bearerToken is now {user}\\'s, valid ' + body.expires_in + ' s');"]}}],
        'request': {
            'auth': {'type': 'noauth'}, 'method': 'POST', 'header': [],
            'body': {'mode': 'urlencoded', 'urlencoded': [
                {'key': 'grant_type', 'value': 'password'},
                {'key': 'client_id', 'value': '{{clientId}}'},
                {'key': 'client_secret', 'value': '{{clientSecret}}'},
                {'key': 'username', 'value': user},
                {'key': 'password', 'value': '{{password}}'}]},
            'url': {'raw': '{{keycloakUrl}}/realms/oriah/protocol/openid-connect/token',
                    'host': ['{{keycloakUrl}}'], 'path': ['realms', 'oriah', 'protocol', 'openid-connect', 'token']},
            'description': f'Logs in as {user} and stores the token in the environment as bearerToken; '
                           'every other request in this collection sends it. Tokens last 30 minutes.'}}


def health(name, port):
    return {
        'name': f'{name} :{port}',
        'event': [{'listen': 'test', 'script': {'type': 'text/javascript', 'exec': [
            "pm.test('UP', () => pm.expect(pm.response.json().status).to.eql('UP'));"]}}],
        'request': {'auth': {'type': 'noauth'}, 'method': 'GET', 'header': [],
                    'url': {'raw': f'http://localhost:{port}/actuator/health', 'protocol': 'http',
                            'host': ['localhost'], 'port': str(port), 'path': ['actuator', 'health']}}}


def main():
    regexes = gateway_patterns()
    folders, gets = [], []
    for svc in SERVICES:
        spec = fetch_json(f'{GATEWAY}/v3/api-docs/{svc}')
        items = []
        for path in sorted(spec.get('paths', {})):
            for method, op in spec['paths'][path].items():
                items.append(request_item(svc, method, path, op, spec, regexes))
                if method == 'get' and '{' not in path and routed(path, regexes):
                    gets.append(path)
        folders.append({'name': svc, 'item': items})
    collection = {
        'info': {'name': 'Oriah local',
                 'description': 'Generated by scripts/local-dev/postman_from_openapi.py from the services\' own '
                                'OpenAPI descriptions -- regenerate after an API change. See docs/LOCAL_DEV.md.\n\n'
                                'Start with "0. Log in". Requests go through the gateway ({{baseUrl}}) unless marked.',
                 'schema': 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json'},
        'auth': {'type': 'bearer', 'bearer': [{'key': 'token', 'value': '{{bearerToken}}', 'type': 'string'}]},
        'item': [{'name': '0. Log in', 'item': [login('maya', 'staff, job role OWNER'),
                                                login('alice', 'customer')]},
                 {'name': '1. Is it up?', 'item': [health(n, p) for n, p in {**PLATFORM, **SERVICES}.items()]}]
                + folders}
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(collection, indent=2) + '\n', encoding='utf-8')
    count = sum(len(f['item']) for f in folders)
    print(f'wrote {OUT.relative_to(ROOT)}: {count} endpoints in {len(folders)} services')

    if '--check' in sys.argv:
        form = urllib.parse.urlencode({'grant_type': 'password', 'client_id': 'oriah-admin',
                                       'client_secret': 'oriah-admin-secret', 'username': 'maya',
                                       'password': 'password'}).encode()
        with urllib.request.urlopen('http://localhost:7080/realms/oriah/protocol/openid-connect/token', form) as r:
            token = json.loads(r.read())['access_token']
        bad = 0
        for path in sorted(set(gets)):
            req = urllib.request.Request(GATEWAY + path, headers={'Authorization': f'Bearer {token}'})
            body = ''
            try:
                with urllib.request.urlopen(req, timeout=20) as r:
                    status = r.status
            except urllib.error.HTTPError as e:
                status, body = e.code, e.read().decode('utf-8', 'replace')
            # A 404 from a service carries Oriah's problem type ("customer not
            # found" for maya, who has no profile yet) and means routing
            # worked; the gateway's own no-route 404 has an empty body.
            ok = status < 500 and (status != 404 or 'oriah.dev/problems/' in body)
            bad += not ok
            print(f"  {'ok  ' if ok else 'FAIL'} {status} GET {path}")
        print('check: PASS' if not bad else f'check: FAIL ({bad})')
        sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()
