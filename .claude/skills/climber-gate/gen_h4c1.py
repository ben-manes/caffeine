#!/usr/bin/env python3
"""Generate phase-declared, token-multiset-preserving audit-crash trains."""
import argparse
import random
from array import array

N = 4_000_000
MAXIMUM = 8192
SAMPLE = 4 * MAXIMUM
HOT_BASE, HOT_N = 1_000_000, 3000
BAND_BASE, BAND_D = 5_000_000, 3000
WHISP_BASE, WHISP_D = 3_000_000, 100
NOISE_BASE = 9_000_000
W_HOT, W_BAND, W_WHISP = 0.631, 0.1434, 0.0038

HOT, NOISE, BAND_FIRST, BAND_RETURN, WHISP_FIRST, WHISP_RETURN = range(1, 7)


def main():
  parser = argparse.ArgumentParser()
  parser.add_argument("--phase", type=int, required=True)
  parser.add_argument("--order", choices=["high-low", "low-high"], required=True)
  parser.add_argument("--gap", type=int, default=1)
  parser.add_argument("--samples", type=int)
  parser.add_argument("--seed", type=int, default=20260726)
  parser.add_argument("--swaps", type=int, default=1900)
  parser.add_argument("--out", required=True)
  args = parser.parse_args()

  random_source = random.Random(args.seed)
  requests = N if args.samples is None else (args.samples * SAMPLE)
  pending = {}
  tokens = array("Q")
  kinds = bytearray()
  band_id = whisp_id = noise_id = 0

  for position in range(requests):
    pending_token = pending.pop(position, None)
    if pending_token is not None:
      token, kind = pending_token
      tokens.append(token)
      kinds.append(kind)
      continue
    draw = random_source.random()
    if draw < W_HOT:
      tokens.append(HOT_BASE + random_source.randrange(HOT_N))
      kinds.append(HOT)
    elif draw < (W_HOT + W_BAND):
      token = BAND_BASE + band_id
      band_id += 1
      tokens.append(token)
      kinds.append(BAND_FIRST)
      pending[position + BAND_D] = (token, BAND_RETURN)
    elif draw < (W_HOT + W_BAND + W_WHISP):
      token = WHISP_BASE + whisp_id
      whisp_id += 1
      tokens.append(token)
      kinds.append(WHISP_FIRST)
      pending[position + WHISP_D] = (token, WHISP_RETURN)
    else:
      tokens.append(NOISE_BASE + noise_id)
      noise_id += 1
      kinds.append(NOISE)

  starts = [args.phase, args.phase + 20, args.phase + 55]
  for start in starts:
    first = range(start * SAMPLE, (start + 1) * SAMPLE)
    second = range((start + args.gap) * SAMPLE, (start + args.gap + 1) * SAMPLE)
    if args.order == "high-low":
      high, low = first, second
    else:
      low, high = first, second
    high_noise = [i for i in high if kinds[i] == NOISE]
    low_hot = [i for i in low if kinds[i] == HOT]
    if min(len(high_noise), len(low_hot)) < args.swaps:
      raise ValueError(f"insufficient eligible tokens at samples {start}/{start + 1}")
    for high_index, low_index in zip(high_noise[:args.swaps], low_hot[:args.swaps]):
      tokens[high_index], tokens[low_index] = tokens[low_index], tokens[high_index]

  # the swap pass needs random access, so the array stays; only the write streams. Joining the
  # whole array first materializes every record as a string, ~570MB at four million requests.
  with open(args.out, "w") as output:
    for start in range(0, len(tokens), 1 << 16):
      block = tokens[start:start + (1 << 16)]
      output.write("\n".join(map(str, block)) + "\n")
  print(f"{args.out}: {requests} requests, phase={args.phase}, order={args.order}, "
        f"gap={args.gap}, seed={args.seed}, swaps={args.swaps}, pairs={starts}, band={band_id}, "
        f"whisper={whisp_id}")


if __name__ == "__main__":
  main()
