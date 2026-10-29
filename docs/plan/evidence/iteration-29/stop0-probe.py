#!/usr/bin/env python3
"""独立裸进程实验客户端；不导入 JH，不作为产品协议实现。失败不更换 LS 候选。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import selectors
import shlex
import signal
import subprocess
import time
import traceback


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def group_alive(pgid):
    try:
        os.killpg(pgid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        # macOS 未回收的已终止组可报 EPERM；不把不确定当成已消失，交调用者 poll/wait。
        return True


def snapshot(root):
    result = {}
    for folder in ('project', 'index', 'home', 'tmp', 'config_mac_arm'):
        for path in sorted((root / folder).rglob('*')):
            if path.is_file() and not path.is_symlink():
                result[str(path.relative_to(root))] = {'bytes': path.stat().st_size, 'sha256': digest(path)}
    return result


class Peer:
    def __init__(self, process, journal):
        self.process = process
        self.journal = journal
        self.selector = selectors.DefaultSelector()
        self.selector.register(process.stdout, selectors.EVENT_READ)
        self.buffer = b''
        self.next_id = 0
        self.frames = []
        self.started = time.monotonic()
        self.folder = None
        self.settings = {}

    def record(self, direction, message):
        entry = {'seq': len(self.frames) + 1, 'seconds': round(time.monotonic() - self.started, 6),
                 'direction': direction, 'message': message}
        self.frames.append(entry)
        self.journal.write(json.dumps(entry, ensure_ascii=False) + '\n')
        self.journal.flush()
        return entry

    def send(self, message):
        data = json.dumps(message, ensure_ascii=False).encode()
        pending = memoryview(f'Content-Length: {len(data)}\r\n\r\n'.encode() + data)
        deadline = time.monotonic() + 10
        with selectors.DefaultSelector() as writer:
            writer.register(self.process.stdin, selectors.EVENT_WRITE)
            while pending:
                remaining = deadline - time.monotonic()
                if remaining <= 0 or not writer.select(remaining):
                    raise TimeoutError('bounded LSP write expired')
                try:
                    count = os.write(self.process.stdin.fileno(), pending)
                except BlockingIOError:
                    continue
                if not count:
                    raise BrokenPipeError('server stdin closed')
                pending = pending[count:]
        return self.record('client', message)

    def notify(self, method, params=None):
        message = {'jsonrpc': '2.0', 'method': method}
        if params is not None:
            message['params'] = params
        return self.send(message)

    def receive(self, deadline):
        while True:
            if b'\r\n\r\n' in self.buffer:
                header, body = self.buffer.split(b'\r\n\r\n', 1)
                fields = dict(line.split(b':', 1) for line in header.split(b'\r\n'))
                size = int(next(value.strip() for key, value in fields.items() if key.lower() == b'content-length'))
                if size < 0 or size > 8 * 1024 * 1024:
                    raise ValueError('probe frame size out of bounds')
                if len(body) >= size:
                    self.buffer = body[size:]
                    entry = self.record('server', json.loads(body[:size]))
                    message = entry['message']
                    if 'method' in message and 'id' in message:
                        self.reply(message)
                    return entry
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not self.selector.select(remaining):
                raise TimeoutError('bounded LSP wait expired')
            chunk = os.read(self.process.stdout.fileno(), 65536)
            if not chunk:
                raise EOFError('server stdout closed before awaited frame')
            self.buffer += chunk
            if len(self.buffer) > 9 * 1024 * 1024:
                raise ValueError('probe receive buffer exceeded')

    def reply(self, message):
        method = message['method']
        if method == 'workspace/configuration':
            result = []
            for item in message.get('params', {}).get('items', []):
                value = self.settings
                for part in item.get('section', '').split('.'):
                    if part:
                        value = value.get(part) if isinstance(value, dict) else None
                result.append(value)
        elif method == 'workspace/workspaceFolders':
            result = [self.folder]
        elif method in ('window/workDoneProgress/create', 'window/showMessageRequest'):
            result = None  # 标准 progress 原帧均入 journal；无人点击 message actions。
        elif method == 'workspace/applyEdit':
            result = {'applied': False, 'failureReason': 'probe does not apply server edits'}
        else:
            self.send({'jsonrpc': '2.0', 'id': message['id'],
                       'error': {'code': -32601, 'message': 'unsupported by isolated probe: ' + method}})
            return
        self.send({'jsonrpc': '2.0', 'id': message['id'], 'result': result})

    def request(self, method, params, timeout=120):
        self.next_id += 1
        request_id = self.next_id
        message = {'jsonrpc': '2.0', 'id': request_id, 'method': method}
        if params is not None:
            message['params'] = params
        self.send(message)
        deadline = time.monotonic() + timeout
        while True:
            entry = self.receive(deadline)
            message = entry['message']
            if message.get('id') == request_id and 'method' not in message:
                if 'error' in message:
                    raise RuntimeError(f'{method}: {message["error"]}')
                return entry

    def diagnostic(self, uri, after, empty, timeout=180):
        deadline = time.monotonic() + timeout
        while True:
            for entry in self.frames:
                message = entry['message']
                if entry['direction'] != 'server' or entry['seq'] <= after:
                    continue
                if message.get('method') != 'textDocument/publishDiagnostics':
                    continue
                params = message['params']
                if params.get('uri') != uri:
                    continue
                diagnostics = params.get('diagnostics')
                if not isinstance(diagnostics, list):
                    continue
                if empty and not diagnostics:
                    return entry
                if not empty and any(d.get('severity') == 1 and d['range']['start']['line'] == 4 for d in diagnostics):
                    return entry
            self.receive(deadline)


def run_probe(args):
    scratch, server = args.scratch.resolve(), args.server.resolve()
    evidence = args.evidence.resolve()
    launcher = server / 'plugins/org.eclipse.equinox.launcher_1.8.0.v20260804-1928.jar'
    bundle = server / 'plugins/org.eclipse.jdt.ls.core_1.63.0.202610191322.jar'
    configuration = Path(args.configuration).resolve()
    if configuration != scratch / 'config_mac_arm':
        raise ValueError('configuration must be the probe-owned config_mac_arm copy')
    user_settings = scratch / 'home/maven-user-settings.xml'
    global_settings = scratch / 'home/maven-global-settings.xml'
    for required in (launcher, bundle, configuration / 'config.ini', user_settings, global_settings):
        if not required.is_file():
            raise FileNotFoundError(required)
    project = scratch / 'project'
    app = project / 'src/main/java/probe/App.java'
    uri = app.as_uri()
    command = [str(args.java_home / 'bin/java'), '-Declipse.application=org.eclipse.jdt.ls.core.id1',
               '-Dosgi.bundles.defaultStartLevel=4', '-Declipse.product=org.eclipse.jdt.ls.core.product',
               '-Dlog.level=ALL', '-Xmx1G',
               '-Djdk.xml.maxGeneralEntitySizeLimit=0', '-Djdk.xml.totalEntitySizeLimit=0',
               '-Duser.home=' + str(scratch / 'home'), '-Djava.io.tmpdir=' + str(scratch / 'tmp'),
               '-Dmaven.repo.local=' + str(scratch / 'home/.m2/repository'),
               '--add-modules=ALL-SYSTEM', '--add-opens', 'java.base/java.util=ALL-UNNAMED',
               '--add-opens', 'java.base/java.lang=ALL-UNNAMED',
               '-jar', str(launcher), '-configuration', str(configuration),
               '-data', str(scratch / 'index')]
    env = {'PATH': str(args.java_home / 'bin') + ':/usr/bin:/bin', 'JAVA_HOME': str(args.java_home),
           'HOME': str(scratch / 'home'), 'TMPDIR': str(scratch / 'tmp'), 'LANG': 'en_US.UTF-8'}
    state = {'command': command, 'command_shell': shlex.join(command), 'environment': env,
             'launcher': launcher.name, 'launcher_sha256': digest(launcher),
             'server_bundle': bundle.name, 'server_bundle_sha256': digest(bundle),
             'phase': 'before-start', 'core_gate': False, 'observations': {}, 'failure': None}
    before = snapshot(scratch)
    (evidence / 'directories-before.txt').write_text(json.dumps(before, indent=2) + '\n')
    start = time.monotonic()
    process = None
    peer = None
    try:
        with (evidence / 'server-stderr.txt').open('xb') as stderr, (evidence / 'protocol.txt').open('x') as journal:
            process = subprocess.Popen(command, cwd=project, env=env, stdin=subprocess.PIPE,
                                       stdout=subprocess.PIPE, stderr=stderr, start_new_session=True, bufsize=0)
            os.set_blocking(process.stdin.fileno(), False)
            state.update(pid=process.pid, pgid=os.getpgid(process.pid), phase='initialize')
            assert state['pgid'] == process.pid
            peer = Peer(process, journal)
            peer.folder = {'uri': project.as_uri(), 'name': 'java25-fixture'}
            peer.settings = {'java': {'home': str(args.java_home),
                'configuration': {'updateBuildConfiguration': 'automatic',
                    'runtimes': [{'name': 'JavaSE-25', 'path': str(args.java_home), 'default': True}],
                    'maven': {'userSettings': str(user_settings), 'globalSettings': str(global_settings)}},
                'import': {'gradle': {'enabled': False}, 'maven': {'enabled': True, 'offline': {'enabled': False}}},
                'maven': {'downloadSources': False}, 'telemetry': {'enabled': False},
                'autobuild': {'enabled': True}}}
            init = peer.request('initialize', {'processId': os.getpid(), 'rootUri': project.as_uri(),
                'workspaceFolders': [peer.folder], 'clientInfo': {'name': 'jh-isolated-stop0-probe', 'version': '2'},
                'capabilities': {'workspace': {'configuration': True, 'workspaceFolders': True,
                                               'applyEdit': False, 'didChangeWatchedFiles': {'dynamicRegistration': False}},
                                 'window': {'workDoneProgress': True},
                                 'textDocument': {'publishDiagnostics': {'versionSupport': True},
                                                  'synchronization': {'dynamicRegistration': False, 'didSave': False},
                                                  'definition': {'linkSupport': False, 'dynamicRegistration': False},
                                                  'references': {'dynamicRegistration': False}},
                                 'general': {'positionEncodings': ['utf-16']}},
                'initializationOptions': {'settings': peer.settings, 'bundles': [],
                    'workspaceFolders': [project.as_uri()],
                    'extendedClientCapabilities': {'progressReportProvider': False,
                                                    'shouldLanguageServerExitOnShutdown': False}}})
            state['initialize'] = init
            state['initialize_seconds'] = round(time.monotonic() - start, 3)
            peer.notify('initialized', {})
            peer.notify('workspace/didChangeConfiguration', {'settings': peer.settings})
            updated = app.read_text()
            assert updated.count('String label = "ok";') == 1
            text = updated.replace('String label = "ok";', 'String label = 42;')
            state['disk_source'] = {'sha256': digest(app), 'text': updated}
            opened = peer.notify('textDocument/didOpen', {'textDocument': {'uri': uri, 'languageId': 'java',
                                                                        'version': 1, 'text': text}})
            state['phase'] = 'diagnostic-v1'
            state['source_v1'] = {'document_version': 1, 'sha256': hashlib.sha256(text.encode()).hexdigest(), 'text': text}
            state['diagnostic_v1'] = peer.diagnostic(uri, opened['seq'], False)
            state['diagnostic_v1_seconds'] = round(time.monotonic() - start, 3)
            state['phase'] = 'change-v2'
            changed = peer.notify('textDocument/didChange', {'textDocument': {'uri': uri, 'version': 2},
                                                            'contentChanges': [{'text': updated}]})
            state['source_v2'] = {'document_version': 2, 'sha256': hashlib.sha256(updated.encode()).hexdigest(), 'text': updated,
                                  'did_change_seq': changed['seq']}
            try:
                state['diagnostic_v2'] = peer.diagnostic(uri, changed['seq'], True, timeout=90)
            except TimeoutError as error:
                state['observations']['clear_diagnostics_gap'] = str(error)
            position = {'line': 5, 'character': updated.splitlines()[5].index('twice') + 1}
            caps = init['message']['result']['capabilities']
            for method, capability, params in (
                ('textDocument/definition', 'definitionProvider', {'textDocument': {'uri': uri}, 'position': position}),
                ('textDocument/references', 'referencesProvider', {'textDocument': {'uri': uri}, 'position': position,
                                                                  'context': {'includeDeclaration': True}})):
                if not caps.get(capability):
                    state['observations'][method] = {'unsupported': True}
                    continue
                try:
                    state['observations'][method] = peer.request(method, params, timeout=30)
                except (TimeoutError, RuntimeError) as error:
                    state['observations'][method] = {'gap': str(error)}
            state['phase'] = 'shutdown'
            state['shutdown_response'] = peer.request('shutdown', None, timeout=20)
            peer.notify('exit')
            process.stdin.close()
            tail = bytearray(peer.buffer)
            drain_deadline = time.monotonic() + 20
            state['stdout_eof'] = False
            while time.monotonic() < drain_deadline and peer.selector.select(max(0, drain_deadline - time.monotonic())):
                chunk = os.read(process.stdout.fileno(), 65536)
                if not chunk:
                    state['stdout_eof'] = True
                    break
                tail.extend(chunk)
                if len(tail) > 8 * 1024 * 1024:
                    raise ValueError('unexpected oversized stdout after exit')
            state['exit_code'] = process.wait(timeout=max(0.01, drain_deadline - time.monotonic()))
            state['total_seconds'] = round(time.monotonic() - start, 3)
            (evidence / 'stdout-after-shutdown.txt').write_text(json.dumps({'raw_hex': tail.hex()}) + '\n')
            # wait 已回收本子进程；再检查其专属进程组，不按名称触碰别的 Java 进程。
            try:
                os.killpg(process.pid, 0)
                state['process_group_gone'] = False
            except ProcessLookupError:
                state['process_group_gone'] = True
            state['core_gate'] = state['exit_code'] == 0 and state['process_group_gone'] and state['stdout_eof']
            state['phase'] = 'finished'
    except Exception:
        state['failure'] = traceback.format_exc()
    finally:
        if process is not None and (process.poll() is None or not state.get('process_group_gone', False)):
            state['forced_cleanup'] = True
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            deadline = time.monotonic() + 5
            while group_alive(process.pid) and time.monotonic() < deadline:
                process.poll()
                time.sleep(0.05)  # OS 无进程组 wait；短周期只等待本探针专属组回收。
            if group_alive(process.pid):
                os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)
            deadline = time.monotonic() + 3
            while group_alive(process.pid) and time.monotonic() < deadline:
                time.sleep(0.05)
            state['cleanup_group_gone'] = not group_alive(process.pid)
            state['core_gate'] = False
        if peer:
            peer.selector.close()
            state['frame_count'] = len(peer.frames)
        if process:
            for stream in (process.stdin, process.stdout):
                try:
                    stream.close()
                except OSError as error:
                    state.setdefault('close_errors', []).append(str(error))
                    state['core_gate'] = False
            state['reaped_exit_code'] = process.returncode
        after = snapshot(scratch)
        (evidence / 'directories-after.txt').write_text(json.dumps(after, indent=2) + '\n')
        state['directory_delta'] = {'created': sorted(set(after) - set(before)),
                                   'modified': sorted(k for k in set(before) & set(after) if before[k] != after[k]),
                                   'removed': sorted(set(before) - set(after))}
        state['probe_exit'] = 0 if state['core_gate'] else 1
        state['snapshot_scope'] = 'project/index/home/tmp/config_mac_arm only; not an OS filesystem or network audit'
        (evidence / 'result.txt').write_text(json.dumps(state, ensure_ascii=False, indent=2) + '\n')
        print('EVIDENCE=' + str(evidence))
        print('PHASE=' + state['phase'])
        print('EXIT=' + str(state['probe_exit']))
        if state['failure']:
            print(state['failure'])
    return state['probe_exit']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scratch', type=Path, required=True)
    parser.add_argument('--server', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--configuration', required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    try:
        args.evidence.mkdir()
    except OSError:
        traceback.print_exc()
        return 2
    try:
        return run_probe(args)
    except Exception:
        failure = traceback.format_exc()
        print(failure)
        try:
            (args.evidence / 'harness-failure.txt').write_text(failure + '\nEXIT=2\n')
            (args.evidence / 'result.txt').write_text(json.dumps(
                {'core_gate': False, 'probe_exit': 2, 'failure': failure,
                 'note': 'probe setup or evidence finalization failed; inspect partial logs'}, indent=2) + '\n')
        except OSError:
            traceback.print_exc()  # 证据目录不可写只报 stderr，绝不声称成功。
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
