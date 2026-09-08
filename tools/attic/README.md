# tools/attic/ — inherited octamax mod sources

Inherited from [`mxldyn/octamax`](https://github.com/mxldyn/octamax) by
**Maxolydian**. These are the octamax behaviour-mod patch sources and their
bundle builder:

- `patch.s`, `patch_enc.s`, `patch_led.s`, `patch_scene.s`, `patch_scene2.s` —
  lazy Part transitions + the "dirty track" LED/encoder indicators + sticky A/B
  scene pointers
- `patch_notimer.s` — no BANK/PTN selection countdown, plus its two PERSONALIZE
  entries
- `patch_arp.s` — extra arpeggiator F-knob key scales (Greek modes, blues, …)
- `patch_gui.s` — the superseded GUI-in-transition editor hook
- `build.py` — assembles all of the above (plus the Kyoti Bug-1 fix
  `tools/patch_trigscale.s`) into one `MAXOLYDIAN`-branded image
- `build_arp.py` — a standalone stock + arp-scales image

**None of this is part of any OT Kyoti FW build.** It is kept here purely as
reverse-engineering cross-reference — several of the hook sites and RAM addresses
it uses sit right next to the regions the Kyoti patches touch (the PERSONALIZE
menu, the encoder editors, the LED painter, the scene/crossfader path, the
pattern-change engine). The consolidated, address-keyed map is
[`../../reference/kb/memory-map.md`](../../reference/kb/memory-map.md); the design
narratives are in
[`../../reference/upstream-notes.md`](../../reference/upstream-notes.md).

`build.py` / `build_arp.py` still run from the repo root
(`python3 tools/attic/build.py`) and resolve their `.s` sources from this
directory. Full credit and lineage: [`../../CREDITS.md`](../../CREDITS.md).
