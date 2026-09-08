# Credits & lineage

OT Kyoti FW is a **fork of [`mxldyn/octamax`](https://github.com/mxldyn/octamax)**
by Maxolydian, and stands on a wider body of Octatrack reverse-engineering work.
Nothing here would exist without the projects below.

## Direct lineage

- **[octamax](https://github.com/mxldyn/octamax)** — Maxolydian.
  The workspace this fork is built on: the container/update-chain analysis, the
  reproducible guarded-patch/build pipeline, the code-cave detour method, the
  PERSONALIZE-menu mapping, and the first round of behaviour mods (lazy Part
  transitions, no BANK/PTN countdown, arp key scales, boot branding, LED dirty
  indicators). Those mod patch sources and the bundle builder (`build.py`) are
  kept in [`tools/attic/`](tools/attic/) for reverse-engineering
  cross-reference — they are not part of any OT Kyoti FW build — and the design
  notes are in [`reference/upstream-notes.md`](reference/upstream-notes.md).
  GitHub shows the fork link. octamax ships no `LICENSE` file; this fork exists
  under GitHub's Terms of Service and keeps octamax's stance — educational use
  only, no binaries redistributed.

## Tools this project builds on

- **[mischa85/elektron-firmware-tool](https://github.com/mischa85/elektron-firmware-tool)**
  — packs/unpacks the ELEK container and the `.bin`/`.syx` transports. Fetched
  into `vendor/` by `setup.sh` and patched locally
  (`tools/elektron-firmware-tool.patch`, two small changes documented in
  `sysex/README.md`). Keeps its own license.
- **[snugsound/OctaLib](https://github.com/snugsound/OctaLib)** — project/bank
  file-format reference used when cross-checking the on-CF data model.
- **aPLib** — the OS payload compression; implemented in
  `elektron-firmware-tool` from the public format description.

## Other Octatrack firmware-patching projects

The same "bring your own official OS, patch it, roll your own image, redistribute
no binary" approach — worth reading alongside this repo:

- **[sambanks/octabam](https://github.com/sambanks/octabam)** (Octabam) — adds
  original DSP audio effects to the Octatrack MKII by patching new **DSP56300
  assembly** into the stock OS, with a Python build system that compiles curated
  effect "remixes" into flashable images. This is the **DSP side** that
  [`COVERAGE.md`](COVERAGE.md) flags as out of scope here — the natural companion
  to the ColdFire-side work in this repo.
- **[emuyia/ems-octakit](https://github.com/emuyia/ems-octakit)** (Octakit, by
  emuyia / junes) — a patcher for OS 1.40C that replaces the 4 Parts per Bank with
  **256 Kits per Project**. Open-sourced in 2026-09: its `runtime/abi.inc`
  (~500 named stock-firmware addresses for the Part / Kit / Bank / scene /
  LFO-designer / sequencer subsystems) and `runtime/firmware.json` (598
  SHA-guarded patch sites) are distilled into
  [`reference/kb/octakit-abi.md`](reference/kb/octakit-abi.md). Ships no `LICENSE`
  and no official code/assets — same "bring your own OS" stance as this repo.
- **[bryantysinger/octa-bt-pt](https://github.com/bryantysinger/octa-bt-pt)**
  (Bryan_T) — a parameter-default patch tool for OS 1.40C (Python / Streamlit):
  customise the firmware's default values and generate a flashable image from
  your own copy of the official OS.

## Community reverse-engineering & documentation

- **emuyia / junes** — the Octakit (`ems-octakit`) source release attributes a
  large slice of the OS 1.40C Part/Kit/Bank/scene/sequencer address map, folded
  into `reference/kb/octakit-abi.md`.
- **Bryan_T** (`octa-bt-pt`), **sambanks** (`octabam`), **snugsound** (`OctaLib`),
  **mischa85** (`elektron-firmware-tool`) — the prior-art repos whose findings are
  distilled, with per-fact attribution, into `reference/kb/*.md` (see
  `reference/EXTERNAL_RESEARCH.md`).
- **Elektronauts threads** that seeded specific findings here:
  - Octatrack CPU chip model — https://www.elektronauts.com/t/octatrack-cpu-chip-model/93304
  - Modifying Elektron firmware — https://www.elektronauts.com/t/modifying-elektron-firmware/36228
  - Plays-Free MIDI manual-trig stall (Bug 1) — documented on Elektronauts
    (thread 87588), 2019, MKI OS 1.30B.

## Legal reference

- EFF Coders' Rights — Reverse Engineering FAQ:
  https://www.eff.org/issues/coders/reverse-engineering-faq
- EU Directive 2009/24/EC Art. 5–6 (study/observe/test; decompilation for
  interoperability).

---

*"Elektron" and "Octatrack" are trademarks of Elektron Music Machines MAV AB,
used here only to identify the hardware under study. This project is
independent, unofficial, and not affiliated with or endorsed by Elektron.*
