#!/usr/bin/env python3
"""Normalize Slang -g SPIR-V for comparisons of the shipped pipelines.

Strip core debug instructions before invoking SPIRV-Tools: the Windows 2026.2
optimizer faults while stripping embedded Slang source in a large raygen.
The optimizer still removes dead constants and compacts IDs, and spirv-val
checks the final module. Requires Vulkan SDK tools on PATH.
"""

import argparse
from pathlib import Path
import struct
import subprocess
import tempfile


CORE_DEBUG_OPS = {3, 4, 5, 6, 7, 8, 317, 330}


def normalize(source: Path, destination: Path) -> None:
    data = source.read_bytes()
    if len(data) < 20 or len(data) % 4:
        raise ValueError(f"Invalid SPIR-V size: {source}")
    words = struct.unpack(f"<{len(data) // 4}I", data)
    if words[0] != 0x07230203:
        raise ValueError(f"Invalid SPIR-V magic: {source}")
    instructions = []
    offset = 5
    while offset < len(words):
        count, opcode = words[offset] >> 16, words[offset] & 0xFFFF
        if count == 0 or offset + count > len(words):
            raise ValueError(f"Invalid instruction at word {offset}: {source}")
        instructions.append((opcode, words[offset:offset + count]))
        offset += count
    debug_sets = set()
    for opcode, instruction in instructions:
        if opcode == 11:  # OpExtInstImport
            name = struct.pack(f"<{len(instruction) - 2}I", *instruction[2:]).split(b"\0", 1)[0]
            if name.startswith(b"NonSemantic.Shader.DebugInfo."):
                debug_sets.add(instruction[1])
    stripped = list(words[:5])
    for opcode, instruction in instructions:
        if opcode in CORE_DEBUG_OPS:
            continue
        if opcode == 11 and instruction[1] in debug_sets:
            continue
        # Both ExtInst forms put the imported instruction set after type and result ID.
        if opcode in (12, 4433) and instruction[3] in debug_sets:
            continue
        stripped.extend(instruction)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="fluorite-spirv-") as scratch:
        intermediate = Path(scratch) / source.name
        intermediate.write_bytes(struct.pack(f"<{len(stripped)}I", *stripped))
        subprocess.run(["spirv-opt", "--eliminate-dead-const", "--compact-ids",
                        str(intermediate), "-o", str(destination)], check=True)
    subprocess.run(["spirv-val", "--target-env", "vulkan1.2", str(destination)], check=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out-dir", required=True, type=Path)
    parser.add_argument("sources", nargs="+", type=Path)
    args = parser.parse_args()
    for source in args.sources:
        normalize(source, args.out_dir / source.name)
        print(source.name)


if __name__ == "__main__":
    main()
