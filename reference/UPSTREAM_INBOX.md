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

_(none — initial sync 2026-09-02, MANIFEST.lock is the baseline)_

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
