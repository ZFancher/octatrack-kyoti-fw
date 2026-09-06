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

- 2026-09-06  octamax@7d9debc  OCTAMAX 2.x — slice-playhead view, dual-256 static-pool
              reclaim (DDR relocation), OCTAMAX_2 combined release. Techniques noted in
              kb/techniques.md; not adopted.                          [ noted, not adopted ]
- 2026-09-06  octabam@2f241e1  the DSP-effect-addition work (bus screen, reverb/delay
              engines, xbus accumulator fixes) — out of scope per COVERAGE.md.  [ out of scope ]

## Distilled

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
