#!/usr/bin/env python3
"""A streaming writer for generated traces.

A generator that collects its whole trace in a list before writing pays for it twice: about
36 bytes per request for the list slot and the int object it points at, and again inside
`"\\n".join(map(str, out))`, which materializes every record as a string before concatenating.
At 4M requests that is roughly a gigabyte for one generator and at 12M (`moat_h7800_long`)
about three, so regenerating the battery's ninety traces can take the machine down even though
the traces themselves are tens of megabytes.

Buffer and flush instead. The bytes written are identical to the join form for any non-empty
trace, which `gen.py`, `gen_straddle.py` and `workload.py` already relied on; this is the same
idiom factored out so a new generator inherits it.
"""


class TraceWriter:
    """Collects trace records and flushes them to disk in fixed-size blocks."""

    def __init__(self, path, block=1 << 16, mark=None):
        """Streams to `path`. Records at or above `mark` are counted, since a generator that
        reports a share of its own output cannot re-read a trace it did not keep."""
        self.file = open(path, "w")
        self.block = block
        self.mark = float("inf") if (mark is None) else mark
        self.buf = []
        self.count = 0
        self.marked = 0

    def append(self, key):
        """Adds one record, flushing the block when it is full."""
        self.buf.append(key)
        self.count += 1
        if key >= self.mark:
            self.marked += 1
        if len(self.buf) >= self.block:
            self.flush()

    def flush(self):
        """Writes the pending block, if any."""
        if self.buf:
            self.file.write("\n".join(map(str, self.buf)) + "\n")
            self.buf.clear()

    def close(self):
        self.flush()
        self.file.close()

    def __len__(self):
        return self.count

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
