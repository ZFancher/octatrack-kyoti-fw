# tools/ghidra/

Helper scripts for the Ghidra headless analysis of the decompressed MAIN OS
(`out/raw/section_3_MAIN_OS.bin`, load base `0x40000400`, ColdFire / m68k
big-endian).

## Reusable

| script | run | purpose |
|---|---|---|
| `ghidra_import.py` | inside Ghidra (Script Manager) or via `analyzeHeadless` | verify/annotate the load base, then define strings + pointers from `pointers_to_strings.csv` so cross-references show in the disassembly |
| `ghidra_decompile.py` | inside Ghidra | batch-decompile a named function list to text |

## `attic/`

`attic/` holds ~240 one-shot `Ghidra*.java` probe scripts — each one was written
to answer a single question during a single session (find a function, dump a
table, resolve a branch target, test a hypothesis). They are kept for
**provenance**: they show how each finding was reached and let a result be
reproduced. They are *not* a maintained toolkit and most will only make sense
next to the `NOTES.md` session that spawned them.

The findings themselves — the durable knowledge — live in `NOTES.md` (the
chronological log), `ARCHITECTURE.md`, and `COVERAGE.md`.

Naming: the prefix groups a line of inquiry (`GhidraArp*`, `GhidraLfo*`,
`GhidraScene*`, `GhidraBank*`, `GhidraResolve*`, `GhidraMute*` …); the trailing
number is just the order they were written.
