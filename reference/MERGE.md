# MERGE.md — combining every final-scoped mod into one firmware

**Status: no-flash merge prep (2026-09-09, `wip/mute-mode`).** `tools/build_merged.py`
composes all five and `tools/emu_merged.py` verifies the one integration point. The
combined image is **not hardware-tested**; the per-feature HW passes in `FLASHING.md`
come first, in order, then this.

This doc is the authoritative allocation map. Update it whenever a cave address, a
detour site, or a shared global changes.

---

## The five final-scoped mods

Only the *scoped* build of each — not the intermediates (`RELOAD2` not `RELOAD`,
`SIDECHAIN3` not `SIDECHAIN`/`2`, DIRECT JUMP **v1**).

| Mod | Standalone build | Sources |
|---|---|---|
| Bug-1 MIDI manual-trig fix | `build_trigscale_only.py` | `patch_trigscale.s` |
| MUTE MODE — `OT` / `OT+FX` / `DT` | `build_mutemode_dt.py` | `patch_softmute.s` + `patch_mutemode.s` (`DT_MODE=1`) |
| DIRECT JUMP — `[PTN]`+`[YES]` | `build_directjump.py` (**v1**) | `patch_directjump.s` |
| SIDE-CHAIN compressor | `build_sidechain3.py` | `patch_sidechain.s` + `patch_sc_dsp3.asm` + `sc_tables.py` |
| RELOAD FROM PROJECT — hold `[PTN]` | `build_reload2.py` | `patch_reload2.s` |

Combined build: **`build_merged.py`** → `out/OCTATRACK_OS1.40C_KYOTI_ALL.{syx,bin}`.

---

## Verdict

**One real code collision, one mechanical cave clash, everything else composes.**

1. **`[YES]` handler @ `0x4005e4c8`** — DIRECT JUMP *and* RELOAD2 both detour the same
   8 bytes. Resolved by a trampoline (below). *Needs integration + retest.*
2. **Cave base `0x400d7400`** — MUTE MODE, DIRECT JUMP, RELOAD2 each link there.
   Resolved by auto-packing (below). *Mechanical.*
3. Space: everything fits `0x400d7000–0x400d7c3c` with **~526 B** headroom.
4. SIDE-CHAIN is nearly orthogonal — DSP address space + a descriptor region nothing
   else touches.
5. `patch_trigscale` is byte-identical in every build; it is the shared base.

---

## ColdFire cave allocation (as `build_merged.py` packs it)

Free zone `0x400d7000 … 0x400d7c3c` (3132 B). Packed from the bottom;
`patch_trigscale` pinned at `0x400d7b00` so its bytes match `build_trigscale_only.py`.

| Cave | Addr | Size | Notes |
|---|---|---|---|
| `patch_sidechain` (CF: `key_fmt` / `kfilt_fmt`) | `0x400d7000` | 140 B | + descriptor & chooser edits outside the zone |
| `patch_softmute` (`DT_MODE=1`) | `0x400d708c` | 368 B | |
| `patch_mutemode` (`DT_MODE=1`) | `0x400d71fc` | 136 B | |
| `patch_directjump` (v1) | `0x400d7284` | 498 B | `dj_toggle` reached via chain, not a detour |
| `patch_reload2` (`MERGE=1`) | `0x400d7478` | ~1194 B | ~4 B smaller than standalone (chained `rly_stock`) |
| PERSONALIZE menu arrays ×3 (17 entries) | `0x400d7924` | 204 B | relocated from `0x400b2a34/74/c0` |
| — free — | `0x400d79f0` | 272 B | |
| `patch_trigscale` | `0x400d7b00` | 62 B | **pinned** |
| — free — | `0x400d7b3e` | 254 B | |

Addresses shift if any cave's size changes — `build_merged.py` re-packs and re-asserts
every run (disjoint, inside the zone, every displaced-byte guard). Do not hand-copy
these into another tool; read them from a build run.

### Outside the free zone (SIDE-CHAIN only, no other mod touches these)

| Region | What |
|---|---|
| `0x400d5ac8–0x400d5ba3` | COMPRESSOR descriptor slots 8–11 (`KEY` / `KFLT` / `KGAIN` / `MON`) |
| `0x400d607e–0x400d61c3` | FX1/FX2 chooser lists + `ID2POS` (SPATIALIZER pulled) |
| DSP payload A `0x400e2324+`, B `0x400f59ef+` | SPATIALIZER donor cave + `sctap`/`scdet`/`sctail` hooks + `X:0x215[5]` null-stub. **Separate address space — zero ColdFire interaction.** |

---

## Detour-site inventory

| Site | Mod | Sym | Kind | Displaced (stock) |
|---|---|---|---|---|
| `0x4009b6f2` | Bug-1 | `cave` | jmp+6nop (18) | `move.l #0x91a,d0` … |
| `0x40004dc6` | MUTE MODE | `pre` | jmp (6) | `move.l 0x80000008,d5` |
| `0x40005178` | MUTE MODE | `pre_v` | jmp (8) | `lea -0xc(sp),sp` … |
| `0x400a4006` | DIRECT JUMP | `dj_a` | jsr (6) | `tst.b (0x8000667e).l` |
| `0x400a42fa` | DIRECT JUMP | `dj_b` | jsr (6) | `move.l #0x8e56,d0` |
| `0x400a4840` | DIRECT JUMP | `dj_c` | jsr (8) | `clr.b d0 ; move.b d0,(0x800065b6).l` |
| `0x4005a044` | RELOAD2 | `rl_ptn` | jmp (6) | `move.l 8(sp),d0 ; moveq #1,d1` |
| `0x4005e25c` | RELOAD2 | `rl_no` | jmp (6) | `move.l 8(sp),d0 ; beq.s …` |
| **`0x4005e4c8`** | **RELOAD2** | **`rl_yes`** | **jmp (8)** | `move.l 4(sp),d1 ; move.l 8(sp),d0` |
| `0x4004b970` | RELOAD2 | `rl_arr_a` | jmp (8) | `lea -12(sp),sp ; movem.l d2-d3/a2,(sp)` |
| `0x400491a0` | RELOAD2 | `rl_arr_b` | jmp (6) | `move.l d2,-(sp) ; movea.l 8(sp),a0` |
| `0x40085864` | RELOAD2 | `rl_job` | jmp (8) | `move.l a2,-650(fp) ; move.l 4(a2),-(sp)` |

Non-detour edits: menu ref repoints `0x40068efe/f0a`, `0x40069022/3e/56`; count
`0x40068fb2` (`moveq #15`→`#16`); restore-length `pea 0x64`→`0x70` at `0x4001f322`,
`0x4001f3be`, `0x4001fb24` (MUTE MODE **and** DIRECT JUMP — identical, idempotent).

**Standalone, DIRECT JUMP also detours `0x4005e4c8` (`dj_toggle`).** In the merge it
does **not** — RELOAD2 owns that site and chains.

---

## The `[YES]` trampoline (`0x4005e4c8`)

DIRECT JUMP wants it for the `[PTN]`+`[YES]` toggle; RELOAD2 wants it to answer its
picker. One `jmp` fits. Both source files already anticipate the merge.

**Resolution — RELOAD2 is the outer hook, DIRECT JUMP is chained:**

```
0x4005e4c8  jmp rl_yes
                │
  rl_yes: ──────┤  event==press && G_MENU==1 && !POPUP ?
                │        yes → close picker, run the highlighted item, swallow
                │        no  → jmp dj_toggle            ◄── MERGE=1 only
                │
  dj_toggle: ───┤  event==press && [PTN] held && !arranger && !POPUP ?
                │        yes → toggle DIRECT JUMP, toast, swallow
                │        no  → replay `move.l 4(sp),d1 ; move.l 8(sp),d0`
                │              jmp 0x4005e4d0  (stock YES handler)
```

Implementation: `patch_reload2.s` gained an `.ifdef MERGE` block. Standalone,
`rly_stock` replays the prologue and `jmp`s `YES_RESUME`; under
`--defsym MERGE=1 --defsym MERGE_DJ_TOGGLE=<addr>` it is a single `jmp DJ_TOGGLE`.
`build_merged.py` assembles `patch_directjump` first, reads `dj_toggle`, feeds it in.
**`patch_directjump.s` is unchanged** — `dj_toggle` behaves identically whether
entered from a detour or from `rl_yes`, because the stack is untouched on that path
(`0(sp)`=ret, `4`=keycode, `8`=event).

`emu_merged.py` drives all six cases on the real merged bytes:

| picker | `[PTN]` | key | → owner |
|---|---|---|---|
| open | — | press | RELOAD2 |
| open | held | press | RELOAD2 (picker wins) |
| closed | held | press | DIRECT JUMP |
| closed | none | press | stock |
| closed | held | release | stock |
| closed | held + popup | press | stock (DJ guard) |

---

## Shared / adjacent state — all compatible

| Concern | MUTE MODE | DIRECT JUMP | RELOAD2 | Verdict |
|---|---|---|---|---|
| PERSONALIZE word | `0x800000dc` | `0x800000d8` | — | distinct |
| 'ANDY' SRAM shadow | `0x100fff6c` | `0x100fff68` | — | distinct, both in the extended `0x70` window |
| `pea 0x64→0x70` ×3 | yes | yes | no | identical write, idempotent |
| scratch RAM globals | `0x80006c66` | `0x80006a40–44` | `0x80006a50–53` | disjoint |
| `[PTN]` flags | — | reads `0x460d1742` | detours PTN handler, replays prologue on non-hold | stock still sets `0x460d1742`; both set `PTN_USED 0x460d173e` |
| PERSONALIZE menu | owns the surgery | no menu entry | no menu entry | only MUTE MODE |

**Gesture split:** quick `[PTN]`+`[YES]` = DIRECT JUMP toggle; `[PTN]` **hold** =
RELOAD picker. Confirm the hold feel on hardware (HW unknown, `FLASHING.md` §4.7).

---

## DIRECT JUMP: use v1, not v2, in the merge

v2 adds two couplings into MUTE MODE / RELOAD territory:

- **`0x400522ca` hook (`dj_tick2`)** — the per-frame routine that services soft-mute's
  release watchdog `0x46c7dfba`. v2 replays the `lea`, so it is functionally safe, but
  it is an extra splice next to soft-mute. RELOAD2 deliberately dropped its own hook
  here in Session 44.
- **`FUN_4005a0e0` popup + handle `0x460d1e64`** — shared with RELOAD2's picker
  (`rl_draw`). One window object; a DJ toast and the picker could stomp each other.

v1 uses the SELECT-BANK/PTN window (`0x40059f8c` / handle `0x460d1e5c`) and hooks
nothing in the frame handler. If v2's box-free toast is wanted later, give it a
private countdown that is not `0x400522ca` and a window that is not `0x460d1e64`.

---

## Build & verify

```
python3 tools/build_merged.py           # -> out/OCTATRACK_OS1.40C_KYOTI_ALL.{syx,bin}
python3 tools/emu_merged.py              # STATIC + DYNAMIC, expect "ALL GOOD"
```

`build_merged.py` asserts: cave layout disjoint + inside the zone; every displaced-byte
guard; no two detours at one site; Bug-1 bytes identical to `build_trigscale_only.py`;
every change is one a standalone feature also makes (bar relocated caves / detours);
round-trip + checksum through Elektron's tool. The SIDE-CHAIN DSP + descriptor bytes
are byte-identical to `build_sidechain3.py`.

The per-feature `emu_*.py` still run against their **standalone** images (they assert
`0x400d7400`-era cave addresses); `emu_merged.py` covers the merge-specific risk only.

---

## Order of operations (when the MKI is back)

1. Flash & sign off each feature standalone, in the `FLASHING.md` / `START_HERE.md`
   order: **DT → SIDECHAIN2 → SIDECHAIN3 → DIRECTJUMP** (+ Bug-2 confirm, p-lock
   Phase-0).
2. Only then flash `OCTATRACK_KYOTI_ALL.bin` and re-run each feature's HW checklist on
   the combined image, plus: the `[PTN]` gesture split (tap vs hold), MUTE MODE while
   the RELOAD picker is open, a DIRECT JUMP toggle immediately after a RELOAD.

## Open decisions

- **RELOAD `reload` vs `reload2`** (the Session 43/44 open item) — `build_merged.py`
  takes `reload2` (2-item). Swap to `patch_reload.s` if the 3-item picker wins; the
  `MERGE` block must be ported to `patch_reload.s` too (same `rly_stock` edit).
- **Whether the merge ships at all** vs staying a per-feature menu of builds — the
  combined image is the harder thing to support (one HW regression sinks all five).
