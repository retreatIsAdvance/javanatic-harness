#!/usr/bin/env python3
"""只验证一次性探针客户端，不提供 JDT LS 可用性证据。"""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import runpy
import unittest
from unittest import mock


class ProtocolBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.peer_type = runpy.run_path(str(Path(__file__).with_name('stop0-probe.py')))['Peer']
        self.peer = self.peer_type.__new__(self.peer_type)
        self.sent = []
        self.peer.send = self.sent.append
        self.peer.frames = []
        self.peer.next_id = 0

    def test_unimplemented_registration_is_not_acknowledged(self):
        self.peer.reply({'id': 'server-register-1', 'method': 'client/registerCapability',
                         'params': {'registrations': [{'id': 'watch', 'method': 'workspace/didChangeWatchedFiles'}]}})
        self.assertEqual(self.sent[0]['id'], 'server-register-1')
        self.assertIn('error', self.sent[0])
        self.assertEqual(self.sent[0]['error']['code'], -32601)

    def test_missing_diagnostics_array_is_not_empty_result(self):
        self.peer.frames = [{'seq': 2, 'direction': 'server', 'message': {
            'method': 'textDocument/publishDiagnostics', 'params': {'uri': 'file:///fixture'}}}]
        def expire(deadline):
            raise TimeoutError('synthetic deadline')
        self.peer.receive = expire
        with self.assertRaises(TimeoutError):
            self.peer.diagnostic('file:///fixture', 1, True)

    def test_shutdown_omits_params(self):
        self.peer.receive = lambda deadline: {'message': {'jsonrpc': '2.0', 'id': 1, 'result': None}}
        self.peer.request('shutdown', None)
        self.assertNotIn('params', self.sent[0])

    def test_permission_error_is_not_group_gone(self):
        alive=runpy.run_path(str(Path(__file__).with_name('stop0-probe.py')))['group_alive']
        with mock.patch('os.killpg', side_effect=PermissionError(1, 'Operation not permitted')):
            self.assertTrue(alive(123456))


FAKE_SERVER = r'''
import json,sys
source_uri = None
def send(message):
    data=json.dumps(message).encode()
    sys.stdout.buffer.write(f'Content-Length: {len(data)}\r\n\r\n'.encode()+data)
    sys.stdout.buffer.flush()
while True:
    header=sys.stdin.buffer.readline()
    if not header: break
    size=int(header.split(b':',1)[1]); assert sys.stdin.buffer.readline()==b'\r\n'
    msg=json.loads(sys.stdin.buffer.read(size)); method=msg.get('method')
    if method=='initialize':
        send({'jsonrpc':'2.0','id':msg['id'],'result':{'capabilities':{'definitionProvider':True,'referencesProvider':True}}})
    elif method=='textDocument/didOpen':
        source_uri=msg['params']['textDocument']['uri']
        send({'jsonrpc':'2.0','method':'textDocument/publishDiagnostics','params':{'uri':source_uri,'version':1,'diagnostics':[{'severity':1,'message':'synthetic fixture error','range':{'start':{'line':4,'character':8},'end':{'line':4,'character':25}}}]}})
    elif method=='textDocument/didChange':
        send({'jsonrpc':'2.0','method':'textDocument/publishDiagnostics','params':{'uri':source_uri,'version':2,'diagnostics':[]}})
    elif method in ('textDocument/definition','textDocument/references'):
        send({'jsonrpc':'2.0','id':msg['id'],'result':[]})
    elif method=='shutdown':
        send({'jsonrpc':'2.0','id':msg['id'],'result':None})
    elif method=='exit':
        sys.stdout.buffer.write(b'x'*1048576);sys.stdout.buffer.flush();break
'''


def main():
    script=Path(__file__).with_name('stop0-probe.py').resolve()
    with tempfile.TemporaryDirectory(prefix='jh-stop0-client-') as temp:
        root=Path(temp).resolve()  # macOS /var 与 /private/var 同一目录，输入统一为真实路径。
        for name in ['project/src/main/java/probe','index','home','tmp','server/plugins','jdk/bin','config_mac_arm']:
            (root/name).mkdir(parents=True,exist_ok=True)
        app=root/'project/src/main/java/probe/App.java'
        original='package probe;\nimport module java.base;\npublic class App {\n public int run() {\n  String label = "ok";\n  return Calc.twice(label.length());\n }\n}\n'
        app.write_text(original)
        (root/'config_mac_arm/config.ini').write_text('synthetic config only\n')
        for name in ['maven-user-settings.xml','maven-global-settings.xml']:
            (root/'home'/name).write_text('<settings/>\n')
        for jar in ['org.eclipse.equinox.launcher_1.8.0.v20260804-1928.jar','org.eclipse.jdt.ls.core_1.63.0.202610191322.jar']:
            (root/'server/plugins'/jar).write_bytes(b'fixture only')
        java=root/'jdk/bin/java';java.write_text('#!'+sys.executable+'\n'+FAKE_SERVER);java.chmod(0o700)
        args=[sys.executable,'-B',str(script),'--scratch',str(root),'--server',str(root/'server'),
              '--java-home',str(root/'jdk'),'--configuration',str(root/'config_mac_arm'),'--evidence',str(root/'run')]
        result=subprocess.run(args,capture_output=True,text=True,timeout=30)
        assert result.returncode==0,(result.stdout,result.stderr)
        evidence=json.loads((root/'run/result.txt').read_text())
        assert evidence['stdout_eof'] and evidence['process_group_gone']
        assert evidence['exit_code']==0 and 'forced_cleanup' not in evidence
        assert len(json.loads((root/'run/stdout-after-shutdown.txt').read_text())['raw_hex'])==2097152
        assert app.read_text()==original, 'buffer experiment must not mutate disk'
        assert evidence['source_v1']['sha256']!=evidence['disk_source']['sha256']
        frames=[json.loads(line)['message'] for line in (root/'run/protocol.txt').read_text().splitlines()]
        init=next(m['params'] for m in frames if m.get('method')=='initialize')
        assert init['initializationOptions']['workspaceFolders']==[(root/'project').as_uri()]
        assert init['capabilities']['workspace']['applyEdit'] is False
        assert init['capabilities']['textDocument']['definition']['dynamicRegistration'] is False
        java_settings=init['initializationOptions']['settings']['java']
        assert java_settings['configuration']['maven']['userSettings']==str(root/'home/maven-user-settings.xml')
        assert java_settings['configuration']['maven']['globalSettings']==str(root/'home/maven-global-settings.xml')
        assert '-Dlog.protocol=true' not in evidence['command']
        assert '-Djdk.xml.maxGeneralEntitySizeLimit=0' in evidence['command']
        assert '-Djdk.xml.totalEntitySizeLimit=0' in evidence['command']
        print('CONTRACT-AND-DISK-BUFFER=PASS')
        print('EXIT-DRAIN=PASS (1 MiB tail, normal exit, EOF, group gone)')
        # 启动前缺 launcher 也必须留下失败结果；不启动第二个进程。
        (root/'server/plugins/org.eclipse.equinox.launcher_1.8.0.v20260804-1928.jar').unlink()
        args[-1]=str(root/'setup-failure')
        result=subprocess.run(args,capture_output=True,text=True,timeout=10)
        assert result.returncode==2
        failure=json.loads((root/'setup-failure/result.txt').read_text())
        assert failure['core_gate'] is False and failure['probe_exit']==2
        print('SETUP-FAILURE-EVIDENCE=PASS')
    print('EXIT=0; synthetic client checks only, not LS evidence')


if __name__=='__main__':
    main()
