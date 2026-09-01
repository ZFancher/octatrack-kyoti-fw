# Credits & lineage

OT Kyoti FW is a **fork of [`mxldyn/octamax`](https://github.com/mxldyn/octamax)**
by Maxolydian, and stands on a wider body of Octatrack reverse-engineering work.
Nothing here would exist without the projects below.

## Direct lineage

- **[octamax](https://github.com/mxldyn/octamax)** — Maxolydian.
  The workspace this fork is built on: the container/update-chain analysis, the
  reproducible patch/build pipeline (`sysex/apply_patch.py`, `tools/build.py`),
  the code-cave detour method, the PERSONALIZE-menu mapping, and the first round
  of behaviour mods (lazy Part transitions, no BANK/PTN countdown, arp key
  scales, boot branding, LED dirty indicators). GitHub shows the fork link.
  octamax ships no `LICENSE` file; this fork exists under GitHub's Terms of
  Service and keeps octamax's stance — educational use only, no binaries
  redistributed.

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

## Community reverse-engineering & documentation

<!-- TODO(zfancher): confirm the exact URLs for these before publishing -->

- **Bryan_T** — long-running Octatrack deep-dive documentation on Elektronauts
  (sequencer/scene/parameter behaviour). <!-- link: Elektronauts profile / guide thread -->
- **Em / "Octakit"** — Octatrack tooling. <!-- link: repo URL -->
- **"Octabam"** — <!-- link + one-line description; confirm the exact name -->
- **Elektronauts threads** that seeded specific findings here:
  - Octatrack CPU chip model — https://www.elektronauts.com/t/octatrack-cpu-chip-model/93304
  - Modifying Elektron firmware — https://www.elektronauts.com/t/modifying-elektron-firmware/36228
  - Plays-Free MIDI manual-trig stall (Bug 1), thread 87588 — reported by the
    author in 2019 on MKI OS 1.30B.

## Legal reference

- EFF Coders' Rights — Reverse Engineering FAQ:
  https://www.eff.org/issues/coders/reverse-engineering-faq
- EU Directive 2009/24/EC Art. 5–6 (study/observe/test; decompilation for
  interoperability).

---

*"Elektron" and "Octatrack" are trademarks of Elektron Music Machines MAV AB,
used here only to identify the hardware under study. This project is
independent, unofficial, and not affiliated with or endorsed by Elektron.*
