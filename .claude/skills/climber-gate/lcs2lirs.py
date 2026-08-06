#!/usr/bin/env python3
"""Project a libCacheSim oracleGeneral trace to unit-weight LIRS epochs.

The tier boundary is 4096 in the maximum's NATIVE units, so on a byte-weighted trace it means 4096
BYTES — every realistic weighted cache is far above it and the boundary is unreachable. To ask the
reachability question of these workloads at all, the weight has to be stripped, which the simulator
deliberately offers only as an explicit trace-side projection (`.claude/rules/simulator.md`: there
is no "treat weighted as unit" mode, use `simulator:rewrite`). This does the same projection out of
band so it does not contend with a running sweep, and slices the result into disjoint epochs — each
epoch is a separate workload cell, which is how a 14-trace pool yields enough cells for a corpus
scan at one fixed size.

Record layout, from `OracleGeneralTraceReader`: little-endian
    uint32 real_time | uint64 obj_id | uint32 obj_size | int64 next_access_vtime   (24 bytes)

The id is unpacked SIGNED to match the reader, which does `Long.reverseBytes(readLong())` and so
yields a negative long for any id with the high bit set. Unpacking it unsigned emits values above
`Long.MAX_VALUE` that `LirsTraceReader` then rejects — every metaStorage epoch died that way, and
loudly, which is the good failure mode. Key identity is unaffected either way; only the spelling is.

    lcs2lirs.py <trace.zst> <outdir> <stem> [--epoch 2000000] [--max-epochs 6]
"""
import argparse, os, struct, subprocess, sys

REC = struct.Struct("<IqIq")
assert REC.size == 24


def decompress(path):
    """Returns the byte stream and the decompressor to reap, or no process for a plain file."""
    if path.endswith(".zst"):
        proc = subprocess.Popen(["zstd", "-dcq", path], stdout=subprocess.PIPE)
    elif path.endswith(".xz"):
        proc = subprocess.Popen(["xz", "-dcq", path], stdout=subprocess.PIPE)
    else:
        return open(path, "rb"), None
    return proc.stdout, proc


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("trace")
    ap.add_argument("outdir")
    ap.add_argument("stem")
    ap.add_argument("--epoch", type=int, default=2_000_000)
    ap.add_argument("--max-epochs", type=int, default=6)
    args = ap.parse_args()
    os.makedirs(args.outdir, exist_ok=True)

    src, proc = decompress(args.trace)
    chunk = REC.size * 8192
    epoch = 0
    written = 0
    eof = False
    out = None
    keys = set()
    tail = b""
    try:
        while epoch < args.max_epochs:
            buf = src.read(chunk)
            if not buf:
                eof = True
                break
            buf = tail + buf
            n = len(buf) // REC.size
            tail = buf[n * REC.size:]
            if out is None:
                out = open(os.path.join(args.outdir, f"{args.stem}_e{epoch}.lirs"), "w")
            lines = []
            for i in range(n):
                _, obj, _, _ = REC.unpack_from(buf, i * REC.size)
                lines.append(obj)
                keys.add(obj)
                written += 1
                if written % args.epoch == 0:
                    out.write("\n".join(str(k) for k in lines) + "\n")
                    lines = []
                    out.close()
                    print(f"{args.stem}_e{epoch}: {args.epoch} requests, "
                          f"{len(keys)} distinct", flush=True)
                    keys = set()
                    epoch += 1
                    out = (open(os.path.join(args.outdir, f"{args.stem}_e{epoch}.lirs"), "w")
                           if epoch < args.max_epochs else None)
                    if out is None:
                        break
            if lines and out is not None:
                out.write("\n".join(str(k) for k in lines) + "\n")
    finally:
        if out is not None:
            out.close()
            # a short final epoch is not comparable to the others; drop it
            p = os.path.join(args.outdir, f"{args.stem}_e{epoch}.lirs")
            if os.path.exists(p) and (written % args.epoch) != 0:
                os.remove(p)
                print(f"{args.stem}_e{epoch}: dropped ({written % args.epoch} requests, short)")
        src.close()
        if proc is not None:
            # an epoch cap leaves the decompressor mid-stream, but a completed read must exit
            # cleanly or a corrupt archive silently truncated the epochs
            if eof:
                if proc.wait() != 0:
                    sys.exit(f"{args.trace}: decompressor exited {proc.returncode}")
            else:
                proc.terminate()
                proc.wait()
    print(f"{args.stem}: {written} requests read, {epoch} full epochs", file=sys.stderr)


if __name__ == "__main__":
    main()
