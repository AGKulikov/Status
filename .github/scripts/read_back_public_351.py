"""Verify the stable pair using anonymous GETs against the same release list as Natro."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
from http.client import IncompleteRead
import json
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.request


REPO = 'AGKulikov/Status'
WEB = 'https://github.com/' + REPO + '/releases'
REPO_API = 'https://api.github.com/repos/' + REPO
API = REPO_API + '/releases'
CERT = '6e9855aedc008bbdd8a7fbf3f490be07f964b7ac658a837a1592647a08365c75'
VERSION_CODES = {'3.5.1': (208021384, 739564632), '3.5.2': (208021385, 739564633), '3.5.3': (208021386, 739564634), '3.5.4': (208021387, 739564635)}


def targets_for(version):
    app_code, navigator_code = VERSION_CODES[version]
    return {
        'natro': ('ru.natro.statuswidget', app_code, version, f'Natro-{version}-signed.apk', 'release-report.json', 128),
        'navigator': ('ru.yandex.yandexnavi', navigator_code, '30.3.0 Natro ' + version,
                      f'Navigator-30.3.0-Natro-{version}-signed.apk', 'navigator-release-report.json', 256),
    }


def require(condition, message):
    if not condition:
        raise ValueError(message)


def retry(action, attempts=4):
    for i in range(attempts):
        try:
            return action()
        except (OSError, ValueError, IncompleteRead) as error:
            if i + 1 == attempts:
                raise
            print(f'Retrying public GET/check ({i + 1}/{attempts}): {type(error).__name__}: {error}', flush=True)
            time.sleep(min(3 * (2 ** i), 20))


def open_url(url):
    request = urllib.request.Request(url, headers={'User-Agent': 'Natro-Public-Release-Check/1', 'Cache-Control': 'no-cache'})
    return urllib.request.urlopen(request, timeout=60)


def small(url, limit=65536):
    with open_url(url) as response:
        data = response.read(limit + 1)
    require(len(data) <= limit, 'Response exceeds updater limit at ' + url)
    return data


def digest(data):
    return hashlib.sha256(data).hexdigest()


def verify_asset(asset, name, tag, length, sha):
    require((asset['name'], asset['state'], asset['browser_download_url'], asset['size'], asset['digest']) ==
            (name, 'uploaded', f'{WEB}/download/{tag}/{name}', length, 'sha256:' + sha),
            'Release asset metadata differs from GET bytes: ' + name)


def release_list(source, version='3.5.1'):
    tag = 'natro-v' + version
    targets = (source, 'release/natro-3.5.1-signed') if version == '3.5.1' else (source,)
    latest = json.loads(small(API + '/latest', 512 * 1024))
    releases = json.loads(small(API + '?per_page=100', 4 * 1024 * 1024))
    public = [r for r in releases if not r['draft'] and not r['prerelease']]
    listed = next((r for r in public if r['tag_name'] == tag), None)
    require(listed is not None and latest['tag_name'] == tag and latest['id'] == listed['id']
            and not latest['draft'] and not latest['prerelease'] and latest['published_at']
            and latest['target_commitish'] == listed['target_commitish']
            and latest['target_commitish'] in targets
            and latest['html_url'] == listed['html_url'] == f'{WEB}/tag/{tag}', 'Expected release is not public/latest yet')
    def inventory(r):
        return {a['name']: (a['id'], a['size'], a['digest'], a['browser_download_url']) for a in r['assets']}
    require(inventory(listed) == inventory(latest), 'Latest and updater listings differ')
    ref = json.loads(small(f'{REPO_API}/git/ref/tags/{tag}'))
    target = ref['object']
    require(ref['ref'] == f'refs/tags/{tag}' and target['type'] == 'commit'
            and re.fullmatch(r'[0-9a-f]{40}', source) and target['sha'] == source,
            'Public tag does not directly identify the verified source commit')
    return latest, public, target['sha']


def read_manifest(entry):
    app, tag, asset, targets = entry
    name = app + '-update.json'
    base = f'{WEB}/download/{tag}/'
    require(asset['browser_download_url'] == base + name, 'Unexpected manifest URL')
    raw = retry(lambda: small(asset['browser_download_url']))
    verify_asset(asset, name, tag, len(raw), digest(raw))
    value = json.loads(raw)
    package, _, _, _, _, max_mib = targets[app]
    require(value['schema'] == 1 and value['channel'] == 'stable' and value['package'] == package
            and value['versionCode'] > 0 and value['versionName'] and value['certificateSha256'] == CERT
            and 0 < value['size'] <= max_mib * 1024 * 1024 and re.fullmatch('[0-9a-f]{64}', value['sha256'])
            and value['url'].rsplit('/', 1)[0] + '/' == base and value['url'].endswith('.apk')
            and not any(c in value['url'] for c in ('..', '?', '#')), 'Invalid updater manifest: ' + asset['browser_download_url'])
    return app, tag, raw, value


def get_apk(url, path, size, expected):
    hasher = hashlib.sha256()
    total = 0
    with open_url(url) as response, path.open('wb') as output:
        while True:
            block = response.read(1024 * 1024)
            if not block:
                break
            total += len(block)
            require(total <= size, 'Public APK is larger than its manifest')
            hasher.update(block)
            output.write(block)
    require(total == size and hasher.hexdigest() == expected, 'Public APK differs from its manifest: ' + path.name)
    return total, hasher.hexdigest()


def verify(sdk, verified, source, version='3.5.1'):
    expected_tag = 'natro-v' + version
    targets = targets_for(version)
    latest, releases, _ = retry(lambda: release_list(source, version), attempts=7)
    def latest_page():
        with open_url(WEB + '/latest') as response:
            require(response.status == 200 and response.geturl() == latest['html_url'], 'Public latest page redirects elsewhere')
            return response.geturl()
    page = retry(latest_page, attempts=7)
    by_name = {a['name']: a for a in latest['assets']}
    entries = [(app, r['tag_name'], a, targets) for r in releases for a in r['assets'] for app in targets
               if a['name'] == app + '-update.json']
    with ThreadPoolExecutor(max_workers=4) as pool:
        manifests = list(pool.map(read_manifest, entries))
    checks = {}
    with tempfile.TemporaryDirectory() as tmp:
        for app, (package, code, application_version, name, report_name, _) in targets.items():
            found = [m for m in manifests if m[0] == app]
            winner = max(found, key=lambda m: m[3]['versionCode'])
            _, tag, raw, value = winner
            require(tag == expected_tag and (value['versionCode'], value['versionName'], value['url']) ==
                    (code, application_version, f'{WEB}/download/{expected_tag}/{name}'), 'Natro would select a different release: ' + app)
            manifest_name = app + '-update.json'
            def compare_alias():
                require(small(f'{WEB}/latest/download/{manifest_name}') == raw, 'Latest alias points to another manifest')
            retry(compare_alias, attempts=7)
            require(raw == (verified / manifest_name).read_bytes(), 'Manifest differs from signed release inputs')
            report_raw = retry(lambda: small(by_name[report_name]['browser_download_url']))
            verify_asset(by_name[report_name], report_name, expected_tag, len(report_raw), digest(report_raw))
            require(report_raw == (verified / report_name).read_bytes(), 'Release report differs from verified inputs')
            report = json.loads(report_raw)
            path = Path(tmp) / name
            size, sha = retry(lambda: get_apk(value['url'], path, value['size'], value['sha256']))
            verify_asset(by_name[name], name, expected_tag, size, sha)
            require(report['sourceCommit'] == source and report['certificateSha256'] == CERT
                    and report.get('signedSha256', report.get('sha256')) == sha, 'Published report does not identify downloaded APK')
            def run(*cmd):
                return subprocess.check_output(cmd, text=True)
            # On Android 9 a valid v3 block takes precedence; a separate API 24
            # probe verifies v2 instead of requiring both flags from a single run.
            def signer_at(api):
                return run(sdk / 'apksigner', 'verify', '--verbose', '--print-certs',
                           '--min-sdk-version', str(api), '--max-sdk-version', str(api), path)
            signer = signer_at(28)
            fingerprints = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)$', signer, re.M)
            v2 = signer_at(24)
            require(fingerprints == [CERT] and re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)$', v2, re.M) == [CERT]
                    and re.search(r'^Verified using v3 scheme.*: true$', signer, re.M)
                    and re.search(r'^Verified using v2 scheme.*: true$', v2, re.M), 'Unexpected APK signer: ' + name)
            badging = run(sdk / 'aapt', 'dump', 'badging', path)
            identity = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
            min_sdk = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
            require(identity and identity.groups() == (package, str(code), application_version) and min_sdk and int(min_sdk[1]) <= 28,
                    'Downloaded APK package, version or minSdk mismatch: ' + name)
            checks[app] = dict(eligibleManifests=len(found), manifestUrl=by_name[manifest_name]['browser_download_url'],
                               manifestSha256=digest(raw), releaseReportUrl=by_name[report_name]['browser_download_url'],
                               reportSha256=digest(report_raw), apkUrl=value['url'], bytes=size, sha256=sha,
                               package=identity[1], versionCode=int(identity[2]), versionName=identity[3],
                               minSdk=int(min_sdk[1]), certificateSha256=fingerprints[0], exactApi28=True, signatureV2=True, signatureV3=True)
    final, _, tag_commit = retry(lambda: release_list(source, version))
    require(final['id'] == latest['id'], 'Latest release changed during verification')
    return dict(checkedAt=datetime.now(timezone.utc).isoformat(), latestUrl=API + '/latest', latestPageUrl=page,
                updaterUrl=API + '?per_page=100', releaseUrl=latest['html_url'], tag=latest['tag_name'],
                sourceCommit=tag_commit, publishedAt=latest['published_at'], releaseId=latest['id'],
                draft=latest['draft'], prerelease=latest['prerelease'], targets=checks)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--verified', type=Path, required=True)
    parser.add_argument('--source', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--version', choices=VERSION_CODES, default='3.5.1')
    args = parser.parse_args()
    result = verify(args.sdk.resolve(), args.verified, args.source, args.version)
    data = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
    args.output.write_text(data)
    print(data)
