# Octatrack OS 1.40C — address map (merged)

The single cross-referenced picture: our findings (`NOTES.md`) + anything imported
from the external repos. **Seeded 2026-09-02 from `NOTES.md` Sessions 1–15;** extend
it as you go (see `reference/kb/README.md` for the entry rules).

Namespace: `0x40xxxxxx` = MAIN OS code (load base `0x40000400`, file
`out/raw/section_3_MAIN_OS.bin`) · `0x800xxxxx` = work RAM · `0x46cxxxxx` /
`0x20xxxxxx` = MMIO & driver structs · `FUN_`/`DAT_`/`_DAT_` = Ghidra auto-names.

Confidence: **C**onfirmed (HW or real decompile) · **L**ikely (emu/inference) ·
**?** open question.

---

## Boot / platform

| Addr | Conf | What | Source |
|---|---|---|---|
| `0x40000400` | C | MAIN OS load base | START_HERE |
| `0x46c8d18c` | C | MKI/MKII probe. `tstl 0x46c8d18c ; sne ; …` → `moveq #15` becomes 15 (MKI) or 16 (MKII) PERSONALIZE items. Stock 1.40C is one image for both. | NOTES §"MKI only" |
| `FUN_40001d4c` | C | DSP **P-memory** loader — 24-bit word stream, starts `0x20000000 = 0x81`. Uploads the DSP program at startup. | NOTES L269 |
| `0x80000037` | C | SOLO-mode flag (byte). `FUN_40004db8` branches on `tst.b 0x80000037`. | NOTES Session 11 |

## Sequencer clock / tick

| Addr | Conf | What | Source |
|---|---|---|---|
| `0x4000aad0` | C | Frame ISR. Fires on reading the frame index at `0x2000001c`; accumulates a `2³¹/tempo` phase accumulator; wakes the seq task via a kernel queue. Sample-accurate. | NOTES L261, COVERAGE |
| `0x2000001c` | C | MMIO frame-index register (read triggers the ISR path) | NOTES L261 |
| `FUN_4009c550` | L | Sets tempo period from pattern data (`_DAT_46c82456 + pat*0x8ed8`) | NOTES L260 |
| `FUN_400977cc` | L | trig → voice command mapping (seq task side) | COVERAGE, NOTES L263 |
| `FUN_40005030` | L | trig apply path (paired with `FUN_400977cc`) | NOTES L298 |

## Pattern change / cue / Parts  (Session 15 — DIRECT JUMP)

| Addr | Conf | What | Source |
|---|---|---|---|
| `FUN_400a0570(bank,pat,loopStart,loopEnd,p5)` | C | **The cue-pattern primitive** — single choke point for every pattern change (manual trig, arranger, chain). If seq running (`_DAT_800065b8==1`) it stashes the pending pattern into `_DAT_800065bf/c0`. | NOTES Session 15 |
| `FUN_400a1eea` | C | Per-step pattern switch / pattern reload. 3 reload blocks, each zeroes `_DAT_800065b4`. CHAIN-AFTER gate inside. | NOTES Session 15 |
| `0x400a44d0` | C | **THE COMMIT**: `move.b D1,(0x800065be)` then `0x400a44dc: move.b (800065bf),(800065bd)` — pending pat/bank → active. `800065c1/c2` first hold the *outgoing* pat/bank. | NOTES L3866 |
| `FUN_4009e884` | C | Sends MIDI Program Change for a pattern switch — fired 2 steps early (`if DAT_800065b6 == 2`). | NOTES Session 15 |
| `_DAT_800065b4` | C | Master step position — reset to 0 in every pattern-reload block (this is the lever DIRECT JUMP save/restores with modulo). | NOTES Session 15 |
| `DAT_800065b6` | ? | Step counter — NOTES uses it as both "sub-step counter reset every full step" (L1332) and "master step" (L3728, `==2` PC preload). **Reconcile before relying on it.** | NOTES L1332 vs L3728 |
| `_DAT_800065b8` | L | Per-pattern "sequencer actually stepping" state (not merely "playing") | NOTES L1638 |
| `0x800065bd–c2` | C | pending/active/outgoing bank+pattern bytes (see COMMIT above) | NOTES L3866 |
| `_DAT_800065bf/c0` | C | pending pattern stash written by `FUN_400a0570` | NOTES Session 15 |
| `0x800000a8` | C | free scratch word — DIRECT JUMP menu state (`OFF/ON`) | NOTES Session 15 |

## Parts / Bank apply

| Addr | Conf | What | Source |
|---|---|---|---|
| `FUN_40009094` | C | Applies a Part **by event** — per-track apply loop. The only path that actually swaps Part params (Scenario B). Hook point for lazy Part transitions. | NOTES L295–298 |

## Track start / PLAYS FREE

| Addr | Conf | What | Source |
|---|---|---|---|
| `FUN_4009f3a4` | C | Reads `PLAYS_FREE` / `SCALE_MODE` / `DIRECT` per-track flags at track-struct `+0x48fc` / `+0x48fd` / `+0x48fe`. | NOTES L1506 |
| `FUN_4009b5c8(track)` | L | Normal (non-PLAYS-FREE) track start | NOTES L1540 |
| `FUN_40044584(track, pressOrRelease)` | C | **Manual-trig key handler** — the Bug 1 (Plays-Free MIDI manual-trig stall) site. Fix lives in `tools/patch_trigscale.s`. | NOTES L1515, Bug 1 |

## Voices / audio data model

| Addr | Conf | What | Source |
|---|---|---|---|
| `0x800049d8` | C | Per-track voice state. Stride `0xA8`, 8 tracks. `byte[0]` = active. | NOTES L192 |
| `FUN_40005178` | C | Queues per-track voice commands into mailboxes `0x46c7e9fa` / `0x800018be` / `0x800018de`, indexed `[t*4]`. | NOTES L196 |
| `FUN_40097168` | L | Machine-type dispatch → 0–4 = FLEX / STATIC / THRU / NEIGHBOR / PICKUP | COVERAGE |
| `FUN_40008f84(track)` | C | Start a graceful voice release — sets `DAT_8000184a \|= 1<<t` (release *state*). | NOTES L2703 |
| `FUN_40008fe4(track)` | C | Wraps `FUN_40008f84`; also sets `DAT_8000184c = 0xff`. `FUN_40008fe4(0xffffffff)` = all. | NOTES L2709 |
| `DAT_8000184a` | C | voice-release state bitfield (`1<<t`) | NOTES Session 9 |
| `DAT_8000184c` | C | voice-release **ramp trigger** bitfield (`1<<t`) — the bit that actually starts the fade. | NOTES L2765 |
| `FUN_400836d8` | C | voice release phase handler; `phase==1` branch. No-op for FLEX/STATIC. | NOTES L2683, L2760 |
| `0x46c7ff42` | L | Per-voice **pre-FX amp** array. Stride 4, 8 voices. Filled every frame by `FUN_4000d16c` at `0x4000d36e`. Sits right below `_DAT_46c7ff64`. Candidate lever for the 4th mute mode. | NOTES Session 14 |
| `FUN_4000d16c` | C | Per-frame voice-parameter fill (writes `0x46c7ff42[t]`) | NOTES Session 14 |

## Mute / solo / cue

| Addr | Conf | What | Source |
|---|---|---|---|
| `_DAT_80000008` | C | Mute/solo/cue bitfield. **bits 0–7 = solo, 8–15 = mute, 16–23 = cue** (per project memory; NOTES L3053 says bit 8+t muted / bit 16+t cued — consistent). | NOTES Session 9/11 |
| `FUN_40004dbc` (entry `0x40004db8`) | C | Per-frame **mute gate**. `D5 = _DAT_80000008`; per-track. Hook site `0x40004dc6`. Its only mute lever = zero the post-FX MAIN word. Branches on `tst.b 0x80000037` (solo). SOFT MUTE hooks here + `FUN_40005178`. | NOTES Session 9/11/14 |
| `_DAT_46c7ff64` | C | "silenced in MAIN out" mask (`<<8` per-track layout) — the **post-FX MAIN mute**. Read by the frame builder. | NOTES Session 9/14 |
| `FUN_40083eb0` | C | Track-LED painter. Loops 8 tracks over an id table at `0x400a9670`; reads the same `_DAT_80000008` mute/cue bits. | NOTES L704, L3053 |

## Storage / file data model

| Addr | Conf | What | Source |
|---|---|---|---|
| `_DAT_46c82456` | C | **Per-track pattern data base.** Trigs/params at `_DAT_46c82456 + pat*0x18b2 + trk*0xc` (+`0x8f385`). Tempo/pattern-settings stride `pat*0x8ed8`. This is the anchor for the unmapped p-lock model. | NOTES L197, L260 |
| `_DAT_46c82xxx` | ? | FAT-layer vtable region (storage driver) | COVERAGE |

## UI / menu

| Addr | Conf | What | Source |
|---|---|---|---|
| `FUN_4006d57c` | C | Shared **dialog constructor** — used to add PERSONALIZE / menu entries (MUTE MODE, DIRECT JUMP). | NOTES L90 |
| `0x400a9670` | C | 8-entry track-id table used by the LED painter | NOTES L704 |

## Effect & machine parameter-descriptor table (`0x400d2fe4`–`0x400d5e04`)

> source: `refs/octa-bt-pt/patch_tool/addresses.json` + `tools/generate_streamlit_patch.py` @ `e970dd0` · fetched 2026-09-02 · confidence: **C** for FILTER/DELAY/NONE (disassembly), **L** for the other 12 (structural analogy)

Each descriptor: base `E`, then a `0x96`-byte default-parameter block at `E_0x96`
(`E_0x96 = E + 0x96`). The effect **id byte** sits at `E + 0x3b`. Stock FX1 = FILTER,
FX2 = DELAY; FX-default assignment via `lea` operands `0x40005680` (FX1) /
`0x4000568a` (FX2); NONE descriptor `E = 0x400d45e0`.

| Effect | id | `E` | Effect | id | `E` |
|---|---|---|---|---|---|
| FILTER | `0x04` | `0x400d4772` | PLATE REV | `0x14` | `0x400d5594` |
| SPATIALIZER | `0x05` | `0x400d4904` | SPRING REV | `0x15` | `0x400d5726` |
| DELAY | `0x08` | `0x400d4a96` | DARK REV | `0x16` | `0x400d58b8` |
| EQUALIZER | `0x0c` | `0x400d4c28` | COMPRESSOR | `0x18` | `0x400d5a4a` |
| DJ EQ | `0x0d` | `0x400d4dba` | LOFI | `0x1c` | `0x400d5d6e` |
| PHASER | `0x10` | `0x400d4f4c` | COMB FILTER | `0x13` | `0x400d5402` |
| FLANGER | `0x11` | `0x400d50de` | CHORUS | `0x12` | `0x400d5270` |

FX1-disallowed (hardware menu restriction, not addressing): DELAY + the 3 reverbs
(FX2-exclusive; cf. `dsp56300.md` FX1 3072 words / FX2 16384).

**Machine** descriptors, same 12-byte-slot + `0x96` block shape:
`FLEX = 0x400d2fe4`, `STATIC = 0x400d3176` (TSTR = slot byte index 10; `1`=AUTO
stock, `0`=OFF). PICKUP's TSTR has min 1 (0 stalls the sequencer — OOB table idx).

## Free scratch words (for new menu/feature state)

| Addr | Status | Used by |
|---|---|---|
| `0x800000a8` | **taken** | DIRECT JUMP menu state (Session 15) |
| `0x800000d4` | free | — |
| `0x800000d8` | free | — |
| `0x800000dc` | free | — |

_(NOTES L811: "free words inside the block". Verify a candidate is untouched before use.)_

---

## To import next (from `refs/`)

- **OctaLib** → resolve the `_DAT_46c82456 + pat*0x18b2 + trk*0xc` trig/p-lock
  record layout against the on-CF struct definitions → `file-format.md`.
- **octabam** `docs/` — its named ColdFire functions (sequencer / parts / voicing)
  are directly comparable to ours; sweep for `FUN_4000xxxx` names we haven't got.

_Done: octa-bt-pt (descriptor table above), octabam + octa-bt-pt DSP boot map
(`dsp56300.md`). ems-octakit is closed-source — nothing to import (see EXTERNAL_RESEARCH.md)._
