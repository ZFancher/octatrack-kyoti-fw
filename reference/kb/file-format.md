# On-CF file data model — Set / Project / Bank / Part / Pattern

What the Octatrack writes to the CF card, and how it maps to the in-RAM structures
in `memory-map.md`. Primary external source: **OctaLib** (snugsound). Our anchor
into the same data from the firmware side is `_DAT_46c82456` (see `memory-map.md`).

---

## Project layout on disk

> source: `refs/OctaLib/Research.md` @ `6e2438e` · fetched 2026-09-02 · confidence: **L** (author calls it "untested but likely")

A Project = **52 files**, each in two versions (`.work` = working memory,
`.strd` = stored/saved; load copies `.strd`→`.work`, save the inverse):

| Files | Names |
|---|---|
| Project (1) | `project` — **plain text**, sample-slot definitions + metadata |
| Arranger (8) | `arr01`..`arr08` |
| Bank (16) | `bank01`..`bank16` — fixed-length binary |
| Markers (1) | `markers` |

### `project` sample definitions (plain text)

```
[SAMPLE]
TYPE=              FLEX | STATIC
SLOT=              001-128  (+129-136 = FLEX recording buffers)
PATH=              ../path/to/file
TRIM_BARSx100=     length in bars ×100  (400 = 4 bars)
TSMODE=            timestretch mode
LOOPMODE=          loop mode
GAIN=              default 48
TRIGQUANTIZATION=  default -1
[/SAMPLE]
```

---

## Bank file — binary layout

> source: `refs/OctaLib/OctaLibCore/Constants.cs` + `BankUtils.cs` @ `6e2438e` · fetched 2026-09-02 · confidence: **C** for the offsets OctaLib actually reads, **L** for the structural sketch

Header (16 B): `46 4F 52 4D 00 00 00 00 44 50 53 31 42 41 4E 4B` = `FORM....DPS1BANK`

Structure: file header → **16 PTRN blocks** (each = 8 TRAC + 8 MTRA) → PART header
→ part names as plain text at end of file. Repeating `AA AA AA AA AA AA AA AA 00 00
00 00 00 00 00 00 10 02` marker between sections. Unset values padded `FF`.

### Key offsets (byte addresses within the bank file)

| Const | Value | Meaning |
|---|---|---|
| `ADDR_PAT01` | `0x00000016` | start of pattern-1 header |
| pattern-block stride | `0x8EEC` (36588) | `LENGTH_PATTERN_LENGTH` — PTRN *n* = `0x16 + 0x8EEC*n` |
| pattern header len | `8` | |
| `LENGTH_TRAC` | `0x922` (2338) | one audio-track block; 8 back-to-back after the header |
| `LENGTH_MTRA` | `0x8B9` (2233) | one MIDI-track block; 8 after the 8 TRAC blocks |
| `OFFSET_TRACK_NUM` | `+8` from TRAC/MTRA | track index (always matches position) |
| `OFFSET_TRACK_TRIGS` | `+9` from TRAC/MTRA | regular trigs, **reverse binary** bitfield (OctaLib reads 8 bytes) |
| `OFFSET_TRACK_REC_TRIGS` | `+41` (`0x29`) from TRAC | recording trigs |
| `OFFSET_PATTERN_PART_NUM` | `+0x8EE7` from PTRN | which Part (0-3) this pattern uses |
| `ADDR_PART_NAME[0..3]` | `0x9B4B3, 0x9B4BA, 0x9B4C1, 0x9B4C8` | 6-char part names (stride 7), NUL-terminated |

PTRN header addresses (pattern 1..16): `0x16, 0x8F02, 0x11DEE, 0x1ACDA, 0x23BC6,
0x2CAB2, 0x3599E, 0x3E88A, 0x47776, 0x50662, 0x5954E, 0x6243A, 0x6B326, 0x74212,
0x7D0FE, 0x85FEA`.

MTRA (pattern 1) addresses: `0x492E, 0x51E7, 0x5AA0, 0x6359, 0x6C12, 0x74CB, 0x7D84, 0x863D` (stride `0x8B9`).

PART block addresses (1..8 — **OctaLib notes "two sets of parts, why?"**, likely
`.work` vs saved copy): `0x8EED6, 0x90791, 0x9204C, 0x93907, 0x951C2, 0x96A7D, 0x98338, 0x99BF3` (stride `0x18BB`).

### Machine types

Stored as consecutive bytes with the part definition. `00` = STATIC (default);
FLEX has its own code. Machine-type→code table still open at the byte level, but
firmware `FUN_40097168` dispatches `0-4 = FLEX/STATIC/THRU/NEIGHBOR/PICKUP`, and
the FLEX/STATIC parameter descriptors are located: `0x400d2fe4` / `0x400d3176`
(see `memory-map.md` "Effect & machine descriptor table").

### Effect types → id  (from octa-bt-pt)

`FILTER 0x04 · SPATIALIZER 0x05 · DELAY 0x08 · EQ 0x0c · DJ EQ 0x0d · PHASER 0x10
· FLANGER 0x11 · CHORUS 0x12 · COMB 0x13 · PLATE REV 0x14 · SPRING REV 0x15 ·
DARK REV 0x16 · COMPRESSOR 0x18 · LOFI 0x1c` — full descriptor addresses in
`memory-map.md`. Stock FX1=FILTER, FX2=DELAY.

**Confirmed in the PART block** (DEMO `bank01.work`): each `PART` tag (`0x8EED6` +
`n*0x18BB`) is followed by `+8 = part index (0-3)`, then **8 bytes FX1 id / track**
then **8 bytes FX2 id / track**. e.g. PART slot 8: FX1 `04 18 0c 12 10 04 0c 1c`,
FX2 `08 08 14 08 08 12 12 08` — every FX2 is DELAY/PLATE/CHORUS, consistent with
the FX1-disallowed rule (`memory-map.md`). 8 PART slots = 4 live + 4 saved
(`.work` vs `.strd` copies inside the one file); only 4 name entries exist
(`ADDR_PART_NAME`), DEMO parts are `ONE`/`TWO`/`THREE`/`FOUR`.

### Full TRAC block layout — p-lock region mapped

> source: our RE against a real hardware export — Elektron factory **OT DEMO**
> `bank01.work` (`~/Desktop/OT Backup/KYOTI/OT DEMO/`, exported 2026-01, 636 113 B,
> reproducible: it's the factory demo). Method: `tools/inspect_bank.py`
> (Session 16). confidence: **L** — structure is consistent across all 16
> patterns / 8 tracks and shows sensible per-step ramps, but not yet cross-checked
> against the firmware deserializer or a controlled before/after export.

The audio-track block (`LENGTH_TRAC = 0x922`, from the `TRAC` tag) is **fixed
size** and lays out as:

| Offset | Size | Field |
|---|---|---|
| `+0x00` | 8 | `"TRAC\0\0\0\0"` tag |
| `+0x08` | 1 | track number (0–7) |
| `+0x09` | 8 | **regular trig** bitmap — 64 steps, 1 bit/step, reverse bit order |
| `+0x11` … `+0x48` | ~56 | further per-step bitmaps: trigless / one-shot / swing / slide / **rec-trig @+0x29** (OctaLib), all 8 B each; empty in the DEMO |
| `+0x49` | 16 | delimiter `AA×8 00×8` |
| `+0x59` | 9 | **param header**: `[LEN] 02 00 FF 00 00 00 00 00` — `LEN` ∈ `{0x10,0x20,0x40}` = this **track's** step count **16 / 32 / 64** (varies per track *within* a pattern → it's the per-track length / "TRACK" scale mode, not the pattern master length) |
| `+0x62` | `0x800` | **p-lock array — 64 steps × 32 bytes.** `0xFF` = that parameter not locked on that step. `record[step][p]` = locked value of p-lockable parameter `p` |
| `+0x862` | `0xC0` | per-step aux array — 64 × 3 B (trig conditions / micro-timing / retrig?); `0x00` = default; empty in the DEMO |

`0x62 + 0x800 + 0xC0 = 0x922` exactly.

**p-lock array evidence** (P11 t2, a filter-sweep pattern lock): step records at
exactly 32-byte spacing, byte `0x12` ramping `40 → 29 → 14 → 05 → 00` across
steps 0,2,4,6,8 and byte `0x00` climbing `4F → 5E → 68` on steps 10,12,14.
Locked-param byte offsets seen so far: `0x00, 0x09, 0x12–0x14, 0x1F` — sparse,
~32 slots ≈ one byte per p-lockable track parameter (SRC / pitch-start-len-rate /
AMP / FILTER / FX1 / FX2 / LFO). Exact offset→param map is future work.

**For the NOTES Session 13 backlog** (auto-remove an emptied trigless lock): a
"trigless lock" = a step with entries in the `+0x62` array but its bit clear in
the `+0x09` trig bitmap (and in the trigless-trig bitmap around `+0x11`). Erasing
its last lock = every byte of `record[step]` back to `0xFF`; then if that step is
also not in any trig bitmap, clear its trigless-trig bit too. Confirm the exact
trigless-trig bitmap offset (one of `+0x11..+0x28`) before building.

---

## Firmware ↔ disk cross-reference

| Concept | Disk (OctaLib) | RAM (our RE) |
|---|---|---|
| pattern block stride | `0x8EEC` (bank file) | `pat*0x8ed8` for tempo/settings (`FUN_4009c550`); `pat*0x18b2` for trig/param (`_DAT_46c82456`) |
| per-track block | `LENGTH_TRAC 0x922` | `trk*0xc` within the `_DAT_46c82456` trig region (**mismatch — resolve**) |
| pattern → Part | `PTRN +0x8EE7`, 1 byte | `FUN_40009094` applies Part by event |
| regular trigs | `TRAC +9`, reverse-binary | `FUN_400977cc` consumes trig → voice cmd |

⚠️ The disk per-track stride (`0x922`) and the RAM `trk*0xc` are different views —
`0xc` is almost certainly just a per-track *header/pointer* array, not the trig
payload. The disk block's own p-lock array is `64 × 32 B` (above); the RAM
`pat*0x18b2` stride ÷ 8 tracks ≈ `0x375`/track, so the deserialiser clearly
repacks — don't assume disk offsets survive into RAM. Verify with `insp_banks.py`
(runs the real `FUN_4008ded0`) before hooking anything that reads locks in RAM.

---

## To import next

- **ems-octakit** — ⚠️ **closed-source**. The repo is only a README + issue
  templates; the patcher runs in-browser and isn't published. Value is limited to
  the README's behavioural description (Parts→256 Kits/Project, migration to first
  64 Kit slots, date-based version string e.g. `26512`, MKI keys FUNC+MIDI /
  FUNC+BANK). No code or offsets to import. If the Part block needs a second
  source, ask on their GitHub Discussions or diff a before/after image.
- OctaLib credits **WiliWoW** (Elektronauts) for format help — worth a thread search.
- Best remaining lever for the p-lock model: build a tiny reader against a real
  exported `bank01.work` (ask user to export) using OctaLib's offsets, then walk
  outward from `TRAC+9` / `TRAC+41` to find the p-lock region.
