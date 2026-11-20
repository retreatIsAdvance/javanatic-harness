"""定向行为突变：串行 Maven，逐字节还原并复绿；不运行300秒慢测。"""
from pathlib import Path
import ast
import hashlib
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[4]
EVIDENCE = Path(__file__).resolve().parent
RPC = 'workbench/rpc/src/main/java/io/javanatic/harness/workbench/rpc/'
# name, file, old, new, module, class, optional method selector
CASES = [
    ('deadline-boundary', 'ApprovalRelay.java', 'clock.getAsLong() - entry.deadline >= 0', 'clock.getAsLong() - entry.deadline > 0', 'workbench/rpc', 'ApprovalRelayTest', 'registeredDeadlineRejectsAtBoundaryWithoutAnyWaiter'),
    ('deadline-early', 'ApprovalRelay.java', 'clock.getAsLong() - entry.deadline >= 0', 'clock.getAsLong() - entry.deadline >= -1', 'workbench/rpc', 'ApprovalRelayTest', 'responseBeforeDeadlineIsKeptAfterDeadline'),
    ('deadline-settle', 'ApprovalRelay.java', '            expireIfDue(entry);\n            error = terminalError(entry.done);', '            error = terminalError(entry.done);', 'workbench/rpc', 'ApprovalRelayTest', 'registeredDeadlineRejectsAtBoundaryWithoutAnyWaiter'),
    ('retention-capacity', 'ApprovalRelay.java', 'MAX_RETIRED = 4096', 'MAX_RETIRED = 1', 'workbench/rpc', 'ApprovalRelayTest', 'terminalRetentionIsBoundedAndOldHandleStillObservesItsOutcome'),
    ('retention-ttl', 'ApprovalRelay.java', 'Duration.ofMinutes(10).toNanos()', 'Duration.ofMinutes(11).toNanos()', 'workbench/rpc', 'ApprovalRelayTest', 'tombstoneExpiresAfterTenMinutesWithoutChangingOldDecision'),
    ('buffer-wakeup', 'SseBuffer.java', '        bytes = 0;\n        notifyAll();', '        bytes = 0;', 'workbench/rpc', 'SseBufferTest', 'idleDiffersFromEofAndAbortWakesWaiter'),
    ('buffer-frames', 'SseBuffer.java', 'MAX_FRAMES = 64', 'MAX_FRAMES = 65', 'workbench/rpc', 'SseBufferTest', 'sixtyFifthQueuedFrameAbortsInsteadOfDroppingAndContinuing'),
    ('buffer-bytes', 'SseBuffer.java', 'MAX_BYTES = 8 * 1024 * 1024', 'MAX_BYTES = 9 * 1024 * 1024', 'workbench/rpc', 'SseBufferTest', 'aggregateEncodedByteBudgetIsEightMiB'),
    ('codec-payload', 'SseEncoder.java', 'envelope.set("data", json(write(codec, event)));', 'envelope.set("data", MAPPER.createObjectNode());', 'examples/headless', 'WebSseCodecContractTest', ''),
    ('cursor-default', 'SseRequest.java', 'long after = -1;', 'long after = 0;', 'workbench/rpc', 'SseRequestTest', ''),
    ('cursor-header', 'SseRequest.java', 'String cursor = lastEventIds.isEmpty() ? values.get("lastSeq") : lastEventIds.getFirst();', 'String cursor = values.get("lastSeq");', 'workbench/rpc', 'SseRequestTest', ''),
    ('interrupt-preserve', 'SseConnection.java', 'activeWriter == Thread.currentThread() && !activeWriter.isInterrupted()', 'activeWriter == Thread.currentThread()', 'workbench/rpc', 'SseConnectionTest', 'closePreservesAnAlreadyInterruptedWriter'),
    ('b36-events', 'RpcHttpServer.java', 'if (rejection.isPresent()) {', 'if (rejection.isPresent() && !"/events".equals(exchange.getRequestURI().getPath())) {', 'workbench/rpc', 'RpcHttpServerSseTest', ''),
    ('b37-writes', 'RpcHttpServer.java', 'if (!RequestFence.jsonContentType(exchange.getRequestHeaders().getFirst("Content-Type"))) {', 'if (RequestFence.jsonContentType(exchange.getRequestHeaders().getFirst("Content-Type"))) {', 'workbench/rpc', 'RpcHttpServerTest', ''),
]


def run(name, state, module, test, method):
    phase = f'mut-{name}-{state}'
    command = [sys.executable, str(EVIDENCE / 'stop1-check.py'), phase, module, test]
    if method:
        command += [f'-Dtest={test}#{method}', 'test']
    result = subprocess.run(command, cwd=ROOT)
    report = (EVIDENCE / f'stop1-{phase}-result.txt').read_text()
    total = ast.literal_eval(next(line.split('=', 1)[1] for line in report.splitlines() if line.startswith('FRESH_TOTAL=')))
    fresh = 'MISSING=[]' in report
    return result.returncode, total, fresh


def main():
    chosen = set(sys.argv[1:])
    for name, file, old, new, module, test, method in CASES:
        if chosen and name not in chosen:
            continue
        audit = EVIDENCE / f'stop1-mut-{name}-audit.txt'
        if audit.exists():
            raise SystemExit(f'refuse overwrite {audit}')
        path = ROOT / RPC / file
        original = path.read_bytes()
        before = hashlib.sha256(original).hexdigest()
        if original.count(old.encode()) != 1:
            raise SystemExit(f'not unique: {name}')
        mutated = original.replace(old.encode(), new.encode())
        try:
            path.write_bytes(mutated)
            red, red_total, red_fresh = run(name, 'red', module, test, method)
        finally:
            # 不用 git restore/reset，精确还原本轮原字节，不覆盖并行作者的变动。
            if path.read_bytes() != mutated:
                raise RuntimeError(f'concurrent modification: {path}; restore requires review')
            path.write_bytes(original)
        restored = path.read_bytes() == original
        green, green_total, green_fresh = run(name, 'green', module, test, method)
        # 新鲜测试 XML 的 failure/error 只证明测试阶段红；具体原因仍须人工核读。
        behavior_red = red != 0 and red_fresh and red_total[1] + red_total[2] > 0
        success = behavior_red and green == 0 and green_fresh and restored
        audit.write_text(f'NAME={name}\nFILE={RPC}{file}\nOLD={old!r}\nNEW={new!r}\nBEFORE_SHA256={before}\nAFTER_SHA256={hashlib.sha256(path.read_bytes()).hexdigest()}\nBYTE_RESTORED={restored}\nRED_EXIT={red}\nRED_TOTAL={red_total}\nBEHAVIOR_RED={behavior_red}\nGREEN_EXIT={green}\nGREEN_TOTAL={green_total}\nPASS={success}\n')
        print(f'MUTATION {name}: PASS={success}', flush=True)
        if not success:
            raise SystemExit(1)


if __name__ == '__main__':
    main()
