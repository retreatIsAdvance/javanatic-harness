"""停1终态源码冻结、全量同窗计数与交付链接/字节核验。"""
from pathlib import Path
import datetime
import hashlib
import re
import subprocess
import sys
import urllib.parse
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[4]
EVIDENCE = Path(__file__).resolve().parent
MANIFEST = EVIDENCE / 'stop1-revision-source-sha256.txt'
SNAPSHOT = EVIDENCE / 'stop1-revision-source-snapshot.txt'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, text=True)


def snapshot():
    if MANIFEST.exists() or SNAPSHOT.exists():
        raise SystemExit('refuse overwrite source freeze')
    names = set(git('diff', '--name-only', 'HEAD').splitlines())
    names.update(git('ls-files', '--others', '--exclude-standard').splitlines())
    java = {name for name in names if name.endswith('.java')}
    java.update(str(p.relative_to(ROOT)) for p in (ROOT / 'workbench/rpc/src').rglob('*.java'))
    hashes = []
    parts = ['SOURCE FREEZE ' + datetime.datetime.now().isoformat(), 'HEAD=' + git('rev-parse', 'HEAD').strip()]
    for name in sorted(java):
        path = ROOT / name
        sha = digest(path)
        hashes.append(f'{sha}  {name}')
        parts.append(f'\n===== {name} SHA256={sha} =====\n' + path.read_text())
    MANIFEST.write_text('\n'.join(hashes) + '\n')
    SNAPSHOT.write_text('\n'.join(parts).rstrip() + '\n')
    print(f'FROZEN_JAVA={len(java)}\nMANIFEST={MANIFEST}\nSNAPSHOT={SNAPSHOT}')


def counts(phase):
    report = (EVIDENCE / f'stop1-{phase}-result.txt').read_text()
    fields = dict(line.split('=', 1) for line in report.splitlines() if '=' in line)
    start = datetime.datetime.fromisoformat(fields['START']).timestamp()
    end = datetime.datetime.fromisoformat(fields['END']).timestamp()
    total = [0, 0, 0, 0]
    rows = []
    stale = 0
    for xml in sorted(ROOT.glob('**/target/surefire-reports/TEST-*.xml')):
        if not start <= xml.stat().st_mtime <= end:
            stale += 1
            continue
        suite = ET.parse(xml).getroot()
        numbers = [int(suite.get(key, 0)) for key in ('tests', 'failures', 'errors', 'skipped')]
        total = [left + right for left, right in zip(total, numbers)]
        rows.append(f'{xml.relative_to(ROOT)} {numbers} mtime={datetime.datetime.fromtimestamp(xml.stat().st_mtime).isoformat()}')
    out = EVIDENCE / f'stop1-{phase}-xml-counts.txt'
    if out.exists():
        raise SystemExit('refuse overwrite ' + str(out))
    lines = [f'COMMAND=python3 docs/plan/evidence/iteration-38/stop1-audit.py counts {phase}', 'MAVEN_COMMAND=' + fields['COMMAND'], 'START=' + fields['START'], 'END=' + fields['END'], 'MAVEN_EXIT=' + fields['EXIT'], 'XML_COLUMNS=tests failures errors skipped', *rows, f'FRESH_XML={len(rows)}', f'EXCLUDED_STALE_XML={stale}', f'FRESH_TOTAL={total}', 'NOTE=仅Surefire最终文件同窗求和；JUnit/jqwik同名XML覆盖，不等于引擎所有调用次数。']
    out.write_text('\n'.join(lines) + '\n')
    print('\n'.join(lines[-5:]))


def audit():
    failures = []
    verified = []
    for line in MANIFEST.read_text().splitlines():
        sha, name = line.split('  ', 1)
        if digest(ROOT / name) != sha:
            failures.append('source changed: ' + name)
        verified.append(name)
    docs = [ROOT / 'docs/plan/current-state.md', ROOT / 'docs/plan/iteration-38.md', EVIDENCE / 'stop1-write-seam-design.md', EVIDENCE / 'stop1-review-packet.md']
    links = []
    for doc in docs:
        for link in re.findall(r'\]\(([^)]+)\)', doc.read_text()):
            if link.startswith(('http:', 'https:', '#', 'mailto:')):
                continue
            target = urllib.parse.unquote(link.split('#', 1)[0])
            if not target:
                continue
            resolved = (doc.parent / target).resolve()
            links.append(f'{doc.relative_to(ROOT)} -> {link}')
            if not resolved.exists():
                failures.append('missing link: ' + links[-1])
    diff = subprocess.run(['git', 'diff', '--check'], cwd=ROOT, capture_output=True, text=True)
    if diff.returncode:
        failures.append(diff.stdout + diff.stderr)
    for path in [*docs, *(EVIDENCE.glob('stop1-*.py'))]:
        data = path.read_bytes()
        if not data.endswith(b'\n') or data.endswith(b'\n\n'):
            failures.append('final newline: ' + str(path.relative_to(ROOT)))
        if any(line.rstrip(b' \t') != line for line in data.splitlines()):
            failures.append('trailing whitespace: ' + str(path.relative_to(ROOT)))
    logs = sorted(EVIDENCE.glob('stop1-*.log'))
    (EVIDENCE / 'stop1-revision-log-sha256.txt').write_text('\n'.join(f'{digest(path)}  {path.name}' for path in logs) + '\n')
    lines = ['COMMAND=python3 docs/plan/evidence/iteration-38/stop1-audit.py audit', 'TIME=' + datetime.datetime.now().isoformat(), 'HEAD=' + git('rev-parse', 'HEAD').strip(), f'SOURCE_HASH_MATCH={len(verified)}', f'LOCAL_LINKS={len(links)}', f'RAW_LOG_HASHES={len(logs)}', f'GIT_DIFF_CHECK_EXIT={diff.returncode}', *links, 'FAILURES=' + repr(failures), f'EXIT={int(bool(failures))}']
    (EVIDENCE / 'stop1-revision-audit.txt').write_text('\n'.join(lines) + '\n')
    print('\n'.join(lines[:7] + lines[-2:]))
    if failures:
        raise SystemExit(1)


if sys.argv[1] == 'snapshot':
    snapshot()
elif sys.argv[1] == 'counts':
    counts(sys.argv[2])
elif sys.argv[1] == 'audit':
    audit()
else:
    raise SystemExit('usage: snapshot | counts PHASE | audit')
