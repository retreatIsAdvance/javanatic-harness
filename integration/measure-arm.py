#!/usr/bin/env python3
"""双臂收益验收度量提取器（it31 停 3，四确认裁定③三量纲）。

从会话 jsonl 账本机读三量纲 + 委派树聚合，供单 Agent 臂 vs 委派臂对比判读：
  total-tokens    = Σ(input+output+reasoning)——委派臂自动含子会话（树总账）
  wall-clock-ms   = 末事件 time − 首事件 time（只报告不判赢，裁定③精化①）
  final-context   = 父会话末条 assistant usage.input（完成时主上下文占用，代理指标）
用法：
  python3 integration/measure-arm.py <sessions-root> <parent-session-id> [<parent-id> ...]
输出每臂一行制表符分隔 + 委派臂的子会话明细行（stderr）。
"""
import json
import sys
from pathlib import Path


def read_events(sessions_root: Path, session_id: str):
    log = sessions_root / session_id / "log.jsonl"
    if not log.is_file():
        raise SystemExit(f"session log not found: {log}")
    events = []
    with log.open(encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if line:
                events.append(json.loads(line))
    return events


def session_metrics(events):
    total = 0
    final_input = 0
    for event in events:
        data = event.get("data", {})
        if event.get("type") != "assistant/message":
            continue
        usage = data.get("usage") or {}
        total += sum(int(usage.get(key, 0)) for key in ("input", "output", "reasoning"))
        if "input" in usage:
            final_input = int(usage["input"])
    times = [event["data"].get("time", 0) for event in events if event.get("data")]
    wall = (max(times) - min(times)) if times else 0
    return total, wall, final_input


def child_ids(events):
    ids = set()
    for event in events:
        if event.get("type") == "delegation/snapshot":
            child = event.get("data", {}).get("snapshot", {}).get("childSessionId")
            if child:
                ids.add(child)
    return sorted(ids)


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    root = Path(sys.argv[1])
    for parent_id in sys.argv[2:]:
        parent_events = read_events(root, parent_id)
        p_total, p_wall, p_final = session_metrics(parent_events)
        children = child_ids(parent_events)
        tree_total = p_total
        for child in children:
            c_total, _, _ = session_metrics(read_events(root, child))
            tree_total += c_total
            print(f"child\t{child}\ttokens={c_total}", file=sys.stderr)
        print(f"{parent_id}\ttree-tokens={tree_total}\tparent-tokens={p_total}"
              f"\twall-ms={p_wall}\tfinal-context={p_final}\tchildren={len(children)}")


if __name__ == "__main__":
    main()
