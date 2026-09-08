# Upstream inbox — external changes not yet distilled

`tools/refs/whatsnew.py` output, triaged. Newest first. When a line is fully
folded into `reference/kb/`, move it to the "Distilled" section with the target
file noted. This file is the hand-off point for the (optional) weekly scheduled
agent that fetches the refs and appends new commits here.

## Format

```
- YYYY-MM-DD  <repo>@<short-hash>  <one-line summary>   [ TODO | kb/<file> ]
```

## Pending

- 2026-09-06  octamax@7d9debc  OCTAMAX 2.x — dual-256 static-pool reclaim (DDR
              relocation), OCTAMAX_2 combined release. Techniques noted in
              kb/techniques.md; not adopted.                          [ noted, not adopted ]
- 2026-09-08  octabam@04b8512  the DSP-effect-addition work (one-aux bus, reverb/delay
              engines, xbus per-core rotation) — out of scope per COVERAGE.md.  [ out of scope ]
- 2026-09-08  octabam@04b8512  RTOS 10.17–10.18 — recorder-seam module, Bryan's click
              (recorder length/loop) — recorder-specific, not our threads.   [ not ours ]

## Distilled

- 2026-09-08  octamax@7d9debc  DESIGN_SLICEVIEW.md — SLICE PLAYHEAD RE (octamax's own
              feature, not ported): voice-struct field map @0x800049d8 (+8/+23/+32/+36/
              +48/+52/+68), FUN_40007960 position engine, slice table SETTINGS+312+n*12
              / count +1092, screen primitives + surface 0x400bf10a, the 0x40056c92
              periodic-repaint hole (event 78 → 0x40062d04)
              [ kb/memory-map.md "Voices" + "Screen drawing primitives" ]
- 2026-09-08  octabam@04b8512  midi_re_cc.md §7 (HW, 12 flashes) — page-2 param → engine
              publish path: P2EDIT 0x4003a474, store DB+part*6322+0x8ef5a+track*30+page*6+slot2
              (page=0 for FX2), bookkeeping flags mandatory, live lane 0x80000830+track*72+slot2,
              NO DSP post (rides copier 0x4000cae8 only); page-1 posts kind-0x0f to 0x460d17ee.
              Generic writer FUN_40054cd8. Supersedes NOTES S17 "verify".
              [ kb/memory-map.md "Parameter value → the engine" ]
- 2026-09-08  octabam@04b8512  COLDFIRE_PORT.md O9d — per-voice DSP record 0x80000110/0x310
              (core1) / 0x210/0x410 (core0), 32 halfwords/track (+0..5 AMP, +6..11 FX1 pg1,
              +12..17 FX2 pg1, page-2 in low byte, +27/+28 ids); copier 0x4000cae8; FILTER
              coeff X:0x2c0 (FX2) / X:0x3a0 (FX1)
              [ kb/memory-map.md, kb/dsp56300.md ]
- 2026-09-08  octabam@04b8512  COLDFIRE_PORT.md O1–O12 — tools/ot_emu, headless C++ ColdFire
              V4e + both DSP cores + ESAI audio + CF load; O11 r7-×3-per-track; "load part ≠
              play part"; "dsp_host pokes r6 → a slot can publish nothing"
              [ kb/techniques.md "the ColdFire PORT", kb/dsp56300.md ]
- 2026-09-08  efw-tool@a5bce9a  ELEK version field = fixed 10 B @ 0x08, right-justified
              (was 0x0D); container_header_end(); aPLib offset-bias underflow is deliberate;
              --emit-container; section 2 DSP→bootstrap
              [ kb/container-format.md ]

- 2026-09-07  octabam@47f6cc5  RTOS 10.16 — stock Unicorn halves the ColdFire fractional
              EMAC; unicorn_emac_fractional.patch + build_unicorn.sh; MAC/MSAC ext-bit-8
              [ kb/techniques.md ; patched Unicorn built into refs/octabam/.venv/ ]
- 2026-09-07  octabam@47f6cc5  RTOS 10.13 — machine types 0=STATIC/1=FLEX/4=PICKUP;
              5-byte per-track slot record (+0x2d3+5*track+type); 0x80004f1c recorder
              state record (16x84B, double-buffered); block-table reciprocals 0x80003c20
              [ kb/memory-map.md, kb/file-format.md ]
- 2026-09-02  OctaLib@6e2438e  bank/pattern/part file offsets            [ kb/file-format.md ]
- 2026-09-02  (our RE, DEMO bank01.work)  full TRAC layout + p-lock array (64x32B @+0x62)
              + PART FX-id bytes; tools/inspect_bank.py                   [ kb/file-format.md ]
- 2026-09-02  octabam@e1dcfa9  SETTINGS-tree menu cluster (FUN_40064908/64c18/5578c,
              24B row struct) + FUN_4006d57c signature; 27-fn name diff   [ kb/memory-map.md, kb/techniques.md ]
- 2026-09-02  octabam@e1dcfa9  DSP56721 chip model + boot/upload map     [ kb/dsp56300.md ]
- 2026-09-02  octa-bt-pt@e970dd0  FX/machine descriptor table (0x400d2fe4-0x400d5e04),
              effect id codes, DSP module-map parser, AMF mpysu->mpyuu, ELUP cipher
              [ kb/memory-map.md, kb/dsp56300.md, kb/container-format.md ]
- 2026-09-02  ems-octakit@1817ffb  closed-source, nothing to import; behavioural note only
              [ kb/file-format.md ]
- 2026-09-06  ems-octakit@ca3b527  OPEN-SOURCED — runtime/abi.inc (~500 named stock
              addrs) + firmware.json (598 guarded patch sites + 411 relocate ops) +
              62 .S + Rust patcher; no LICENSE
              [ kb/octakit-abi.md (new), kb/file-format.md, kb/memory-map.md,
                kb/container-format.md, kb/techniques.md ]
- 2026-09-06  octamax@c78ff70  'ANDY' battery-SRAM persistence — 0x800000xx is volatile,
              real store 0x100fff00 (checksum FUN_4001f23c, restore memcpy 0x64 @ 3 sites)
              [ kb/memory-map.md, kb/techniques.md; ported in Session 19 ]
- 2026-09-06  octamax@ec510e1  emu_check.py Unicorn pre-flash gate (diff patched vs stock)
              [ kb/techniques.md ]
- 2026-09-06  octabam@2f241e1  RTOS fork: emu_rtos.py full-firmware emulator + kernel decode
              (scheduler 0x40000550, 11 tasks, TCB layout, INTC0/1)
              [ kb/memory-map.md "Kernel / RTOS", kb/techniques.md ]
- 2026-09-06  octabam@2f241e1  TRAC step-mask map — masks 0x00..0x38 → offsets +0x09..+0x41,
              recorder trigs REC1/2/3 = masks 0x20/0x28/0x30 (HW-confirmed via pattern-diff),
              RAM strides 0x8ed8 / 0x91a, step handler 0x4009d1e8
              [ kb/file-format.md, kb/memory-map.md ]
- 2026-09-06  octabam@2f241e1  PARAM_PAGES.md — full descriptor-table decode: bounds
              0x400d2e52..0x400d5f00, entry layout, MULTIBCOMP id 0x19 @ 0x400d5bdc,
              recorder-page 3-tier storage (Bryan T)
              [ kb/memory-map.md ]
- 2026-09-06  octabam@2f241e1  FAILURE_MODES.md — cave ceiling 0x400d8000 (OS .bss tail
              is not free), power-cycle-after-upgrade warm-up tag
              [ kb/techniques.md, kb/dsp56300.md, FLASHING.md ]
- 2026-09-06  octabam@2f241e1  MAINMENU.md §9a — menu-state table 0x400cbdac (16 entries,
              stride 0x14), relocate-and-repoint to add a whole screen
              [ kb/techniques.md ]
