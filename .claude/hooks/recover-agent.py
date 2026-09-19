#!/usr/bin/env python3
"""Recover the work of a subagent that died mid-turn.

A subagent killed by a quota error, a timeout or a crash leaves its full JSONL transcript behind.
The transcript holds every file it read, every command it ran, every file it wrote and its own
prose, so the expensive part of a dead run is recoverable even though the agent is not resumable.
Reading the transcript directly overflows an orchestrator's context (they run to megabytes), so
this prints only the slice asked for.

    recover-agent.py <transcript> writes   what it had already persisted, in order
    recover-agent.py <transcript> files    distinct files read and commands run, for a relaunch
    recover-agent.py <transcript> text     its own prose, newest last (conclusions not yet written)
    recover-agent.py <transcript> stats    record counts and where it stopped

Transcripts live in the session's task directory as `<agentId>.output`; the id is in the task
notification for the failed agent. Feed `writes` and `files` into the relaunch prompt so the new
agent starts from what the dead one had, rather than paying for it twice.
"""
import json
import sys

LIMIT = 1400


def load(path):
    out = []
    with open(path, errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if line:
                try:
                    out.append(json.loads(line))
                except ValueError:
                    pass
    return out


def blocks(record):
    content = (record.get("message") or {}).get("content")
    return content if isinstance(content, list) else []


def tool_uses(records):
    for record in records:
        for block in blocks(record):
            if block.get("type") == "tool_use":
                yield block.get("name", "?"), (block.get("input") or {})


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    records = load(sys.argv[1])
    mode = sys.argv[2] if len(sys.argv) > 2 else "stats"

    if mode == "writes":
        for name, args in tool_uses(records):
            if name in ("Write", "Edit", "NotebookEdit"):
                size = len(json.dumps(args))
                print(f"  {name:5} {size:>8}b  {args.get('file_path', '?')}")

    elif mode == "files":
        seen = []
        for _, args in tool_uses(records):
            value = args.get("file_path") or args.get("pattern") or args.get("command", "")[:90]
            if value and value not in seen:
                seen.append(value)
        print("\n".join("  " + str(v) for v in seen))

    elif mode == "text":
        said = [
            block["text"].strip()
            for record in records
            if (record.get("message") or {}).get("role") == "assistant"
            for block in blocks(record)
            if block.get("type") == "text" and block.get("text", "").strip()
        ]
        print(f"[{len(said)} assistant messages]\n")
        for message in said[-6:]:
            print("-" * 70)
            print(message[:LIMIT])

    elif mode == "stats":
        calls = list(tool_uses(records))
        wrote = {a.get("file_path") for n, a in calls if n in ("Write", "Edit")}
        print(f"  records    : {len(records)}")
        print(f"  tool calls : {len(calls)}")
        print(f"  files written: {len(wrote - {None})}")
        for path in sorted(p for p in wrote if p):
            print(f"    {path}")

    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
