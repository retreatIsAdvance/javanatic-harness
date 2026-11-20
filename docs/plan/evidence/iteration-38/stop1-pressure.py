"""并发用例五轮 CPU 竞争复跑；只回收本脚本创建的压力进程。"""
from pathlib import Path
import datetime
import json
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[4]
EVIDENCE = Path(__file__).resolve().parent
REPORT = EVIDENCE / 'stop1-revision-pressure.txt'
BURNER = '''import json, os, signal, sys, time
started = time.monotonic()
def finish(signum, frame):
    print(json.dumps({'pid': os.getpid(), 'cpu_seconds': time.process_time(), 'wall_seconds': time.monotonic() - started}), flush=True)
    sys.exit(0)
signal.signal(signal.SIGTERM, finish)
print('READY', flush=True)
x = 1
while True:
    x = (x * 1664525 + 1013904223) % 4294967296
'''
if REPORT.exists():
    raise SystemExit('refuse overwrite ' + str(REPORT))
lines = ['COMMAND=python3 docs/plan/evidence/iteration-38/stop1-pressure.py', f'CWD={ROOT}', 'START=' + datetime.datetime.now().isoformat(), f'CPU_COUNT={os.cpu_count()}']
burners = []
exit_code = 1
try:
    for _ in range(os.cpu_count() or 1):
        worker = subprocess.Popen([sys.executable, '-u', '-c', BURNER], cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        burners.append(worker)
        if worker.stdout.readline().strip() != 'READY':
            raise RuntimeError('burner startup failed')
    lines.append('OWNED_PIDS=' + repr([worker.pid for worker in burners]))
    for i in range(1, 6):
        command = [sys.executable, str(EVIDENCE / 'stop1-check.py'), f'revision-pressure-{i}', 'examples/headless', 'ApprovalRelayTest,RelayApprovalPromptTest,SseConnectionTest,SseBufferTest']
        result = subprocess.run(command, cwd=ROOT)
        lines.append(f'ROUND={i} EXIT={result.returncode} RESULT=stop1-revision-pressure-{i}-result.txt')
        if result.returncode:
            raise RuntimeError(f'round {i} failed')
    exit_code = 0
finally:
    for worker in burners:
        if worker.poll() is None:
            worker.terminate()
    for worker in burners:
        try:
            output, _ = worker.communicate(timeout=10)
        except subprocess.TimeoutExpired:
            worker.kill()
            output, _ = worker.communicate(timeout=10)
            exit_code = 1
        lines.append(f'BURNER_PID={worker.pid} EXIT={worker.returncode} DATA={output.strip()}')
    lines += [f'ALL_OWNED_REAPED={all(worker.poll() is not None for worker in burners)}', 'END=' + datetime.datetime.now().isoformat(), f'EXIT={exit_code}']
    REPORT.write_text('\n'.join(lines) + '\n')
    print('\n'.join(lines), flush=True)
sys.exit(exit_code)
