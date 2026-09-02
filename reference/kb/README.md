# reference/kb/ — the distilled knowledge base

First-class project data, same status as `NOTES.md` / `COVERAGE.md`. Read the
relevant file here **before** starting a new patch — it's where prior art from
the external repos (`reference/EXTERNAL_RESEARCH.md`) and our own cross-session
findings are merged into one address-keyed picture.

## Files

| File | Scope |
|---|---|
| [`memory-map.md`](memory-map.md) | THE merge point — every function / RAM word / MMIO reg by address, ours + theirs |
| [`container-format.md`](container-format.md) | ELUP/ELEK container, aPLib, checksum, `.bin` vs `.syx` transport, the update chain |
| [`file-format.md`](file-format.md) | on-CF Set/Project/Bank/Part/Pattern/Arrangement layout; the per-step trig / p-lock model |
| [`dsp56300.md`](dsp56300.md) | the DSP program: location, upload path, and octabam's findings. Out of scope to *patch* here |
| [`techniques.md`](techniques.md) | code-cave/detour patterns, PERSONALIZE-menu recipe, build-pipeline ideas worth stealing |

## Rules for entries

- **Attribute everything imported.** `source: <repo>/<path> @ <commit>` (commit from
  `refs/MANIFEST.lock`) `· fetched <date>`. For our own findings: `source: NOTES Session N`.
- **Normalise on the address namespace already in `NOTES.md`:**
  `0x40xxxxxx` = MAIN OS code (base `0x40000400`), `0x800xxxxx` = work RAM,
  `0x46cxxxxx` = MMIO / driver structs, `FUN_`/`DAT_`/`_DAT_` = Ghidra auto-names.
- **Flag confidence.** `confirmed` (HW or decompiled), `likely` (emu / inference),
  `claim` (someone else's, unverified here).
- **Contradictions stay visible.** If an external repo disagrees with our finding,
  record both and mark which we trust and why — don't silently overwrite.
- Keep excerpts factual and small. No wholesale source copies (licence posture).
