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

> sources: our RE against a real hardware export — Elektron factory **OT DEMO**
> `bank01.work` (`~/Desktop/OT Backup/KYOTI/OT DEMO/`, exported 2026-01, 636 113 B,
> reproducible: it's the factory demo; `tools/inspect_bank.py`, Session 16) **+
> `refs/octabam/docs/RTOS_FORK.md` §10.6 @ `2f241e1`** (2026-09-06) which measured
> the same block against the firmware and via hardware `pattern-diff`.
> confidence: **C** for the step-mask offsets + the recorder masks, **L** for the
> p-lock array's internal parameter map (still needs the `pattern-diff` pass).

The audio-track block (`LENGTH_TRAC = 0x922`, from the `TRAC` tag) is **fixed
size**. octabam's framing: the `TRAC` chunk header is **9 bytes** (tag + length +
1 pad); track data starts at `+9`. Our offsets below are from the `TRAC` tag, so
they already include that (`+0x08` = the pad byte, which holds the track number;
`+0x09` = first data byte = OctaLib's `OFFSET_TRACK_TRIGS`).

| Offset | Size | Field |
|---|---|---|
| `+0x00` | 8 | `"TRAC\0\0\0\0"` tag |
| `+0x08` | 1 | track number (0–7) (= header pad byte) |
| `+0x09` | 8 | **mask 0x00** — regular note/sample trig; 64 steps, bit `step-1`, byte 7 bit 0 = step 1 |
| `+0x11` | 8 | **mask 0x08** — trig-type layer (trigless-trig / one-shot) — one of these three carries the "trigless lock" bit |
| `+0x19` | 8 | **mask 0x10** — trig-type layer |
| `+0x21` | 8 | **mask 0x18** — trig-type layer |
| `+0x29` | 8 | **mask 0x20** — recorder trig **REC1** (HW-confirmed; = OctaLib `OFFSET_TRACK_REC_TRIGS`) |
| `+0x31` | 8 | **mask 0x28** — recorder trig **REC2** (HW-confirmed) |
| `+0x39` | 8 | **mask 0x30** — recorder trig **REC3** |
| `+0x41` | 8 | **mask 0x38** — swing / slide (sets flag-word bits 5+8) |
| `+0x49` | 8 | per-step byte array (not a mask), default `0xAA` — micro-timing gate (`0x4009d3d6`) |
| `+0x51` | 8 | per-step byte array (not a mask), `0x00` in the DEMO |
| `+0x59` | 9 | **param header**: `[LEN] 02 00 FF 00 00 00 00 00` — `LEN` ∈ `{0x10,0x20,0x40}` = this **track's** step count 16/32/64 (per-track, → the "TRACK" scale mode, not the pattern master length) |
| `+0x62` | `0x800` | **p-lock array — 64 steps × 32 bytes.** `0xFF` = that parameter not locked on that step. `record[step][p]` = locked value of p-lockable parameter `p` |
| `+0x862` | `0xC0` | per-step aux array — 64 × 3 B (trig conditions / micro-timing / retrig?); `0x00` = default; empty in the DEMO |

`0x62 + 0x800 + 0xC0 = 0x922` exactly. Firmware consumers of the masks:
`0x4009d1e8` (step handler), `0x4009d382..0x4009da12`, per-track flag word
`0x46c7a6c0` — see [`memory-map.md`](memory-map.md) "Per-step sequencer data".

### p-lock array — byte → parameter map (hypothesis, confidence **L**)

**Evidence** (DEMO P11 t2, `tools/inspect_bank.py -p 11 -t 2`; 16-step track, 8
regular trigs, no trigless/rec trigs): records at exactly 32-byte spacing.
Byte `0x12` ramps `40 → 29 → 14 → 05 → 00` across steps 0,2,4,6,8 (one automated
parameter); byte `0x00` climbs `4F → 5E → 68` on steps 10,12,14 with `0x13`
appearing on step 14. Non-`0xFF` offsets across the pattern: `0x00, 0x12, 0x13`.

**Working model:** 32 bytes = **five 6-parameter page-1 groups + a 2-byte tail**,
in the OT's parameter-page order:

| record offset | page-1 group (6 params × 1 byte) |
|---|---|
| `0x00–0x05` | PLAYBACK (`PTCH STRT LEN RATE …`) |
| `0x06–0x0b` | AMP (`ATK HOLD REL VOL BAL XVOL`) |
| `0x0c–0x11` | LFO (audio) |
| `0x12–0x17` | FX1 |
| `0x18–0x1d` | FX2 |
| `0x1e–0x1f` | tail — sample-slot lock / validity? |

`0x12` = FX1 param 0 and `0x00` = PLAYBACK param 0 (PITCH) are the offsets seen
automated here — *consistent* with the model but **not confirmed** (P11 t2's part
was not resolved; most DEMO parts give track 2 an EQ, not a FILTER, on FX1, so
the ramp at `0x12` is probably an EQ band, not a cutoff). Payload cross-ref:
[`octakit-abi.md`](octakit-abi.md) FX1 `0x2fe` / FX2 `0x304` are 6 bytes apart
⇒ 6 params per FX page, which is where the "6-byte group" comes from.

Open: page-2 params (SLIC/LOOP/TSTR, AMP SYNC, FX SETUP), LFO-designer locks, the
sample-slot lock, trig-condition / micro-timing (likely the `+0x862` aux array).
The whole map is **untested** — the `pattern-diff` plan below pins it in one pass.

### NOTES Session 13 backlog — auto-remove an emptied trigless lock

A **trigless lock** = a step with a non-`0xFF` `record[step]` in `+0x62` but its
bit **clear** in mask 0x00 (`+0x09`) *and* in whichever of masks 0x08/0x10/0x18
is the "trigless trig" (retrig) layer. On the last-lock erase, `record[step]`
goes all-`0xFF`; the feature then also clears the step's bit in the mask that
lights the dim-red LED. **Which mask that is is the one open question** — it is
one of `+0x11/+0x19/+0x21`, and a pure p-lock may set *none* of them (the LED
predicate could be "row has a `+0x62` entry"). This is what the `pattern-diff`
pass settles.

#### Phase 0 test-pattern plan (uses octabam `ot_project.py pattern-diff`)

octabam's `tools/ot_project.py pattern-diff <projA> <projB> <bank>` diffs every
step-mask between two saved projects and prints the mask offset + steps that
changed — turning "which bit" into a 30-second hardware job. Also useful:
`pattern-trig` writes a trig on disk, `emu_rtos.py` loads a project through the
real firmware and runs the sequencer (see `techniques.md`). Bring both into
`tools/` (or `refs/octabam/tools/`) for the session.

On the MKI, from one cleared baseline project, save a copy after **each** of:

1. one **pure trigless lock** (2 p-locks: e.g. FILTER cutoff + AMP VOL) on step 4
2. erase **one** of those p-locks live (`[NO]`+knob, LIVE REC) — lock still lit
3. erase the **second** — lock should vanish (this is the target behaviour to observe today: it *doesn't*)
4. a **trigless trig** with LFO retrig + 1 p-lock on step 8
5. a **manually placed empty** trigless lock on step 12
6. a normal **sample trig** with 2 p-locks on step 16

`pattern-diff baseline↔1` pins the trigless-lock mask bit + the `record[step]`
offsets for cutoff & VOL (confirms the byte→param map). `1↔2↔3` shows the
multi-pass erase semantics and whether the mask bit clears on 1→0. `baseline↔4`
separates the retrig-trig mask from the pure-lock mask. `4` vs `5` vs `1`
separates "has retrig" from "bare lock" — the predicate must keep 4, delete 3,
keep 5.

#### Phase 1 — the LIVE-REC `[NO]`+knob erase handler (not yet located)

Static RE never pinned the knob→param *writer* (NOTES L410: "scattered through
UI"). Two ways in: (a) `emu_rtos` — drive a `[NO]`+knob event and watch what
touches the `+0x62`-equivalent RAM region (`[0x46c82456] + pat*0x18b2`, near
`+0x8f385`); (b) trace back from the mask consumers `0x4009d382..0x4009da12`.
The detour goes *after* the clear: if `record[step]` is all-`0xFF` and the step
is a bare trigless lock, clear its mask bit. Conservative — keep on any doubt.

---

## Firmware ↔ disk cross-reference

| Concept | Disk (OctaLib) | RAM (our RE) |
|---|---|---|
| pattern block stride | `0x8EEC` (bank file) | `pat*0x8ed8` for tempo/settings (`FUN_4009c550`); `pat*0x18b2` for trig/param (`_DAT_46c82456`) |
| per-track block | `LENGTH_TRAC 0x922` | `trk*0xc` within the `_DAT_46c82456` trig region (**mismatch — resolve**) |
| pattern → Part | `PTRN +0x8EE7`, 1 byte | `FUN_40009094` applies Part by event |
| regular trigs | `TRAC +9` (mask 0x00), bit `step-1` | `FUN_400977cc` consumes trig → voice cmd |
| per-track seq data | `LENGTH_TRAC 0x922` (disk, 9-B header) | RAM stride **`0x91a`** (`mulsl #0x91a,%d7` @ `0x4009d376`) — 9 less: chunk header stripped on load |
| pattern seq data | `0x8EEC` (disk) | RAM `0x8ed8` — 8 less (`PTRN` header 8 B stripped) |
| playing bank/pattern | — | `0x800065bd` / `0x800065be` (sequencer's own; step handler indexes `bank*0x9b340 + 0x400e21e0`, `pat*0x8ed8`) |

octabam (RTOS §10.6) measured the RAM strides directly: **pattern `0x8ed8`, track
`0x91a`** — exactly `disk − header`. So a `TRAC`'s data *does* survive into RAM at
the same relative offsets (mask 0x00 at RAM `+0`, etc.); the older "RAM `trk*0xc`"
note was a different (header/pointer) view.

### RAM p-lock array — **CONFIRMED** (Session 24, `tools/emu_plock.py --confirm`)

**`[0x46c82456] blob + pattern*0x8ed8 + track*0x91a + 0x59`, 64 steps × 32 bytes** —
byte-for-byte identical to the disk `TRAC+0x62` array (the `+0x59` = disk `+0x62`
minus the 9-byte chunk header). Verified by loading the factory OT DEMO through the
real firmware in `emu_rtos` and diffing the RAM against `bank01.work` (P11 t2:
param header `10 02 00 ff …` and every locked step/offset/value match exactly). So
the on-disk map above **is** the RAM map — no repack. The param header
(`[LEN] 02 00 FF …`) is at blob `+0x50`.

⚠️ Separate structure: `FUN_40033e3c` / `FUN_400409f4` manage a triplet
`0x46c7bf2c` (values, `param*128 + step`) / `0x46c7d7d8` (lock bitmap, `param*4`
longs, bit `step%32`) / `0x46c7e0de` (per-param "any lock" flag), gated by
`0x8000004a` bit 1. `FUN_400409f4` flushes it as **MIDI CC** via `FUN_40010bc8` and
clears the bitmap — this is the **MIDI-track CC-lock** send layer, *not* the audio
per-step store. The audio-p-lock writer/eraser (Session 13's target) writes the
blob array above — `emu_plock.py --watch` + a GRID-REC hold-trig + knob-turn gesture
will name it.

---

## To import next

- **ems-octakit** — **open-sourced 2026-09** (`ca3b527`). Distilled into
  [`octakit-abi.md`](octakit-abi.md): its `runtime/abi.inc` confirms
  `FUN_4008ded0` = bank deserialiser, `_DAT_46c82456` = bank pointer,
  `GK_STOCK_BANK_SIZE 0x9b4d1` = the DEMO `bank01.work` size, `GK_PART_PAYLOAD_SIZE
  0x18b2`, and adds the `.work`↔`.strd` store/restore choke points
  (`0x4008eda4` / `0x4008f0b0` / `0x4008ee74` / `0x4008f180`), the per-parameter-page
  payload offsets, and `GK_STOCK_SEQUENCER_PART_{STEP,CONDITION}_OFFSET`
  (`0x1832` / `0x1822`). Watch: `GK_STOCK_PATTERN_PART_OFFSET 0x8e57` vs OctaLib
  `+0x8EE7` — different framing, reconcile before a write.
- OctaLib credits **WiliWoW** (Elektronauts) for format help — worth a thread search.
- **octabam `ot_project.py`** (`@ 2f241e1`) — `pattern-trig` / `pattern-diff` /
  `set_track_slot` / `set_machine_type` / `part-name`: an on-disk bank/project
  editor + differ. `pattern-diff` is the tool for the p-lock Phase-0 pass above.
  `emu_rtos.py` loads a project through the real firmware (see `techniques.md`).
  Both worth vendoring into `tools/` for the next hardware session (their licence
  posture = octamax's: facts + small excerpts, not bulk source).
- Best remaining lever for the p-lock model: the Phase-0 `pattern-diff` pass
  (needs the MKI) + locating the LIVE-REC erase handler (needs `emu_rtos` or
  Ghidra).
