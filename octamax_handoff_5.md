# Octatrack MIDI Manual-Trig Bug — Handoff 5 (session 4 → Claude Code)

## What this is

Reverse-engineering the Elektron Octatrack firmware to find the root cause of a real,
hardware-reproducible bug: manually retriggering an already-playing MIDI track goes silent
instead of restarting, under a specific combination of settings. This project only uses
real hardware-exported Octatrack project files as test data — never fabricated/hand-edited
ones. Everything confirmed so far has been either (a) diffed between two real exports that
differ in exactly one setting, (b) cross-checked by running the actual firmware
deserializer function in an emulator to get ground-truth RAM offsets, or (c) read directly
off raw disassembly (not just decompiled C, which has been caught misrendering a branch
condition once already — see "Lessons" below).

**Why this handoff exists**: this investigation has been running inside Cowork, whose
`device_bash` shell is an ephemeral Linux ARM64 sandbox VM (not the user's real Mac) with
no state persistence and a ~45-second-per-call cap with no backgrounding. That forced a
from-source Ghidra decompiler build every session and fragmented, single-shot analysis
passes. Moving to Claude Code running directly on the user's own Mac (Apple Silicon,
macOS) removes both problems — see "Environment / toolchain" below, which is *simpler*
than what the old sessions had to do, not the same recipe transplanted.

## Repo layout

Working directory: `~/Documents/octamax/` (a real folder on the user's Mac; if opening
via Claude Code locally this is just its normal path, no device-bridge needed).

- `NOTES.md` — the primary knowledge base for this specific bug investigation. Read this
  first, specifically the "Session 4" sections (parts 1–7) for everything below in full
  detail with decompiled code, disassembly, and reasoning. This handoff is a distilled
  summary; NOTES.md is the source of truth.
- `HANDOFF.md` — a **different** running doc, about shipped firmware patches (LED behavior,
  encoder fixes, etc.) — NOT related to this MIDI-trig bug investigation. Don't confuse
  the two.
- `tools/GhidraResolve26.java` … `GhidraResolve35.java` — one-off Ghidra headless analysis
  scripts, each with a comment header explaining what it was for. They're cheap to re-run
  (`GhidraResolve35` finished in a few seconds) and useful as templates for the next ones
  (`GhidraResolve36+`).
- `tools/emu_bankdeserialize.py` — runs the *real* firmware bank-file deserializer
  (`FUN_4008ded0`) against real `bankNN.work` files inside a Unicorn CPU emulator, to get
  precise blob-relative (RAM) offsets from file-relative byte diffs. This is how every
  offset below was actually confirmed, not guessed.
- `ghidra_project/octamax.gpr` / `.rep/` — an existing, already-imported-and-analyzed
  Ghidra project over `out/raw/section_3_MAIN_OS.bin` (the firmware image). Don't
  re-import; just open it.
- `out/ghidra/GhidraResolveNN_session4.txt` — persisted raw output logs from each script
  run this session, for reference without re-running.
- Test project folders (siblings of `octamax/`, each a real hardware export):
  `test1_PF_`, `test1_PFD`, `test1_PFD_scale`, `test1nil_scale`. `test1_PFD_scale` is
  confirmed by the user as a genuine, complete repro of the actual bug (all preconditions
  met). See "Confirmed bug preconditions" below for what differs between these.

## Environment / toolchain (DO THIS DIFFERENTLY THAN THE OLD SESSIONS DID)

The old Cowork sessions had to build Ghidra's native decompiler from source because their
sandbox is `linux_arm_64` and the official Ghidra release only ships prebuilt decompile
binaries for `linux_x86_64` / `mac_x86_64` / `mac_arm_64` / `win_x86_64` — **not**
`linux_arm_64`. On the user's real Mac (confirmed Apple Silicon / `darwin arm64` via device
info), the official Ghidra release **already includes a working native `decompile` binary
for `mac_arm_64`** — none of that from-source build workaround should be necessary. Before
doing anything else, verify this assumption:

1. Check for existing tools: `which java`, `brew list | grep -i ghidra`, or check if Ghidra
   is already installed anywhere (`mdfind -name Ghidra` or similar). Homebrew is present on
   this machine (`.homebrew` shows up in the user's home directory).
2. If not installed: `brew install --cask ghidra` (or download the official release zip
   directly from `github.com/NationalSecurityAgency/ghidra/releases` — the project's Ghidra
   version used so far is `12.1.2_PUBLIC`) plus a JDK 21 (`brew install openjdk@21` or
   Temurin).
3. Confirm the native decompiler works before assuming anything: run one of the existing
   `tools/GhidraResolveNN.java` scripts headless against the existing project (command
   pattern is in every script's header comment) and check for a clean decompile, not a
   "Decompile failed" message.
4. Only fall back to building `ghidra_opt` from source (recipe fully documented in
   NOTES.md's early "Session 4" section, under the Ghidra-headless-setup writeup) if step 3
   genuinely fails on real hardware — it shouldn't.
5. Ghidra project ownership: the existing project was created under OS user `kyoti_m4`.
   If Claude Code runs as a different OS user, opening it may throw `NotOwnerException` —
   fix is `-Duser.name=kyoti_m4` as a JVM option (via `GHIDRA_JAVA_OPTIONS` env var,
   forwarded by `analyzeHeadless`'s launch script). Shouldn't be needed if running as the
   user's own normal account.
6. A recurring **benign** error on every headless run after the first:
   `ERROR Unexpected Exception creating Pre-save file...DuplicateFileException`. Ignore it
   — script execution proceeds normally afterward every time this has been seen. Hasn't
   been root-caused or cleaned up; flag if it ever actually blocks a run.

## Confirmed bug preconditions (user-verified on real hardware, all 4 required)

1. **MIDI track** (not audio).
2. Track set to **"Plays Free"**.
3. Track's trigger quantization set to **"Direct"**.
4. The **pattern's** scale is set to **"Per Track"** — this is a **pattern-level** setting,
   not project-level and not simply per-track: pattern A1 in Bank A can be "Per Track"
   (shows the bug) while pattern A2 in the same bank is "Normal" (bug doesn't show), even
   with everything else identical.

Symptom: manually retriggering (pressing the track's trig key for) an already-playing
Plays-Free MIDI track goes silent instead of restarting. Also important: a Plays-Free MIDI
track is supposed to start via manual trig even when the OT's overall sequencer transport
is stopped — this is correct/intended behavior, not itself a bug, and matters for
interpreting the code (see `_DAT_800065b8` below).

## Confirmed field offsets (blob-relative = RAM-resident, relative to
`bank_blob_base = 0x400e21e0 + bank_index*0x9b340`; pattern blocks are `0x8ed8` bytes each
within a bank blob; per-track MIDI stride within a pattern is `0x8b0` bytes, audio-track
stride is `0x91a` bytes)

- `+0x48fc` — **PLAYS_FREE**, per-track (MIDI track sub-struct), boolean.
- `+0x48fe` — **DIRECT**, per-track (MIDI track sub-struct). `0xff` (-1) = Direct trig
  quantization selected; other values = a quantized length.
- `+0x48fd` — **TRIG_MODE**, per-track (MIDI track sub-struct). This is the manual-trig-key
  **response mode**, Elektron's own three UI options: confirmed `1 = ONE` (hardware
  ground-truth: every test file so far uses this). Value `2` is confirmed **HOLD** via raw
  disassembly (press unconditionally starts, release stops — clean, unambiguous). Value `0`
  is presumed **ONE2** by elimination but **does not match ONE2's toggle semantics in the
  disassembly** — see "Open item 1" below, this is the most concrete unresolved thread.
- `+0x48f8` — per-track fallback quantize length (read by the sequencer step engine,
  `FUN_400a1eea`, and by many other functions across the firmware — this address alone is
  not diagnostic of anything MIDI-trig-specific, it's a generic per-track field).
- `+0x8e53` — pattern-level fallback quantize length (used instead of `+0x48f8` when scale
  mode is NOT per-track).
- `+0x8e55` — **SCALE_MODE**, pattern-level (near the end of the `0x8ed8`-byte pattern
  block, i.e. genuinely pattern-scoped, matching precondition #4's pattern-level nature).
  Confirmed via the emulator-deserializer diff to flip `0x00→0x01` in exactly the same
  real-export diff as `+0x48fd`. **Important, currently unresolved**: this byte is read
  *only* by the bank (de)serializer and by `FUN_400a1eea` (the per-step sequencer engine)
  and by one not-yet-decompiled function, `FUN_4009a670` — it is **not** read anywhere in
  the manual-trig-key dispatch chain (`FUN_40044584`, `FUN_4009b5c8`, `FUN_4009f3a4`). See
  "Open item 2" — this is the most important open thread.
- `_DAT_800065b8` — NOT a per-track field, a single RAM flag. All 3 writes to it are 32-bit
  and all happen inside `FUN_400a1eea` (the sequencer step engine) — current working theory
  is it reflects live "sequencer actively stepping" state, not a static "pattern loaded"
  flag (revised from an earlier, weaker theory in session 3). Not fully confirmed — would
  need to observe it change across an actual transport stop/start, not yet done.
- `_DAT_80000012` ("internally read as `MIDI_MODE`" per its only write site) — **do not**
  treat this as bug precondition #4. It's a single project-wide boolean, loaded once from a
  text-based project-state parser (`FUN_400866c4`, keyed on a literal `MIDI_MODE=` config
  line), gating whether MIDI tracks use the per-track TRIG_MODE dispatch at all vs. an
  entirely different direct-MIDI-scheduling codepath. It was proposed as precondition #4 in
  the previous round of this session and then **retracted** once the user clarified
  precondition #4 is pattern-level, which this flag structurally cannot be (it's loaded
  once from project state, not per-pattern). Worth keeping in mind as *some* kind of
  prerequisite for MIDI tracks generally, just not the pattern-scale one.

## Key functions (all addresses in `section_3_MAIN_OS.bin`'s address space)

- `FUN_40044584(track, action)` — **the manual-trig-key dispatcher**. `track` 0-15
  (8-15 = MIDI), `action` 1 = press / 0 = release. Ground-truthed against raw disassembly
  this session (not just decompiled C). For MIDI tracks, gated by `_DAT_80000012`
  ("MIDI_MODE") being nonzero; reads TRIG_MODE at `+0x48fd` and dispatches:
  - `TRIG_MODE==0`, press → unconditionally call `FUN_4009b5c8` (no active-state check).
    Release → no-op.
  - `TRIG_MODE==1` (**ONE**, confirmed), press → call `FUN_4009b290(track)` to check if
    already active; if active → call `FUN_4009f3a4` (the buggy clear-only path); if not
    active → call `FUN_4009b5c8` (start). Release → no-op.
  - `TRIG_MODE==2` (**HOLD**, confirmed), press → unconditionally call `FUN_4009b5c8`.
    Release → call `FUN_4009f3a4`.
- `FUN_4009b5c8(track)` — the real "start a track" function. Fully decompiled. For MIDI
  tracks: if `PLAYS_FREE==0`, defers to `FUN_4009b95a()` (a confirmed **empty stub**, does
  nothing) and returns. Otherwise reads `DIRECT` (`+0x48fe`); if DIRECT is *not* selected
  AND `_DAT_800065b8==1` (sequencer stepping), takes the "soft" path: flips bits in
  `_DAT_80006680`/`_DAT_80006682` and defers to `FUN_4009b95a()` (stub) — presumably lets
  the step engine handle the actual transition at the next quantize boundary. **Otherwise**
  (DIRECT selected, or sequencer not stepping): does a full, unconditional re-init of the
  track's playback/timing state and sets `DAT_80006500[track] = 1` (**activates**). No
  active-state check anywhere in this function.
- `FUN_4009f3a4(track)` — the "retrigger / stop" function, structurally parallel to
  `FUN_4009b5c8`. Same DIRECT/stepping gating logic. In the "DIRECT selected or not
  stepping" branch — **this is the confirmed bug mechanism** — it clears the track's active
  state (`DAT_80006500[track] = 0`, plus other per-track arrays) and calls the same
  `FUN_400a539c(track)` + `FUN_40000c3c(0x460d17ae,&DAT_400abac8)` pair that
  `FUN_4009b5c8` calls in its equivalent branch — but **never re-runs the reactivation /
  re-init sequence**, i.e. it only does the "stop" half of what should be a stop-then-
  restart. That's the whole bug: a MIDI track manually retriggered while already active,
  with DIRECT selected (or the sequencer not stepping), goes silent and stays silent.
- `FUN_4009b290(track)` — trivial active-state getter. Confirmed, fully decompiled:
  `if (track<0) return _DAT_800065b8; return DAT_80006500[track & 0xf];`
- `FUN_400a539c(track)` — small per-track state-reset helper called by both start/stop
  paths. Decompiled, touches RAM around `0x4610791x`. Not investigated in depth — probably
  not where the bug lives, since both the correct and buggy paths call it identically.
- `FUN_400a1eea` — the sequencer's per-step engine. Confirmed (session 4 part 3, via raw
  disassembly, correcting an earlier decompiler misrender) to read the TRIGQUANT/quantize
  length fields and choose between per-track (`+0x48f8`) and pattern-level (`+0x8e53`)
  fallback values, gated by `+0x8e55` (SCALE_MODE). This is where SCALE_MODE is actually
  consumed — but this is the *automatic step* path, not the *manual key press* path, which
  is the open puzzle (see below).
- `FUN_4009b95a` — confirmed empty stub (`{ return; }`), 10 bytes. Ruled out as a hiding
  place for any toggle logic.
- `FUN_4009a670` — **not yet decompiled**. Found this session as the one other function
  (besides the deserializer and `FUN_400a1eea`) that touches all three of SCALE_MODE
  (`+0x8e55`), the pattern-level quantlen fallback (`+0x8e53`), and the per-track quantlen
  (`+0x48f8`) together. Sits in the same `0x4009xxxx` neighborhood as
  `FUN_4009b290`/`FUN_4009b5c8`/`FUN_4009f3a4`. **This is the strongest concrete lead for
  Open Item 2 below and should be the first thing decompiled in the next session.**
- `FUN_4008a6fc` — also touches all three of the above; likely a sibling
  serializer/deserializer or pattern-copy function (parallel to the known deserializer
  `FUN_4008cebc`). Lower priority than `FUN_4009a670` but worth a quick look if the latter
  doesn't pan out.
- `FUN_400866c4` — the text-based project-state parser that sets `_DAT_80000012`
  ("MIDI_MODE"). Huge function (~7100 bytes), only the relevant `MIDI_MODE=` key-parsing
  branch has been examined. Not expected to be relevant to the actual bug mechanism, just
  documented for completeness since it was chased down this session.

## Open items — what to tackle next, in priority order

1. **Decompile `FUN_4009a670`.** This is the single most promising unexplored function:
   it's the only non-deserializer, non-step-engine function that touches SCALE_MODE, and
   it sits right next to the manual-trig-key handler functions. The goal is to answer Open
   Item 2 (below) — find the actual mechanism by which pattern-level SCALE_MODE gates the
   bug, since it's confirmed absent from the direct `FUN_40044584`→`FUN_4009b5c8`/
   `FUN_4009f3a4` call chain.

2. **Reconcile why SCALE_MODE matters at all, given it's not read in the manual-trig-key
   dispatch chain.** Current puzzle: DIRECT alone (`cVar1 == -1`) should already force the
   buggy branch in `FUN_4009f3a4` regardless of stepping state or scale mode — so on paper,
   the bug "should" reproduce whenever DIRECT+PLAYS_FREE+ONE are set, with or without
   per-track scale. But the user has confirmed on real hardware that scale=per-track is
   necessary. Possible explanations to test, roughly in order of plausibility, none
   confirmed: (a) `FUN_4009a670` is on some input/key-handling path that gates whether a
   manual trig press even reaches `FUN_40044584` at all when scale mode is "Normal" (b)
   scale mode affects how `_DAT_800065b8` gets computed/updated, indirectly changing which
   branch gets taken (c) something in the per-track vs. pattern-level quantlen selection
   changes what "Direct" (`0xff`) even means at the byte level for a given track when scale
   mode differs (d) the mapping/investigation has an error somewhere not yet found. Don't
   assume any of these — decompile and check.

3. **Resolve `TRIG_MODE` value `0`.** Doesn't behave like a toggle (ONE2) in the
   disassembly — it unconditionally calls the start function on every press with no
   active-state check, and `FUN_4009b5c8` itself has no active-check either, so repeated
   presses would just keep re-initializing/restarting, not toggle off. This might mean
   value 0 isn't ONE2 at all, or that ONE2's toggle-off logic lives upstream of
   `FUN_40044584` (not yet checked), or something else. Static analysis alone hasn't
   resolved this after a real attempt — **the concrete next step is a 3rd real hardware
   test export with TRIG_MODE explicitly set to ONE2**, read via the same
   `emu_bankdeserialize.py` methodology used for every other field, rather than guessing
   further from code.

4. **Not yet attempted**: proposing an actual firmware patch. The shape of a fix is fairly
   clear (make `FUN_4009f3a4`'s DIRECT-or-not-stepping branch perform the same reactivation
   `FUN_4009b5c8` does, instead of only clearing state) but this needs full register/stack
   context verification before treating it as safe, and should probably wait until items 1
   and 2 above are resolved, in case they change the picture (e.g. if it turns out
   `FUN_4009f3a4` behaves differently than currently understood when scale mode is
   involved).

## Lessons learned this project (worth keeping in mind)

- **Decompiled C can misrender register-reuse as a false condition.** Caught one real case
  where decompiled output showed `if (uVar20 * 0x8b0 == 0)` (looked like a track-index
  check) but raw disassembly showed the actual branch tested a completely different,
  just-overwritten register value. Whenever a decompiled branch condition looks surprising
  or doesn't match the story, check raw disassembly before trusting it. This is why this
  session re-verified `FUN_40044584` against raw disasm rather than relying on the earlier
  decompile alone.
- **Never fabricate or hand-edit test data.** Every confirmed field in this project came
  from a real hardware export, diffed against another real export differing in exactly one
  setting, cross-checked via the emulator-based deserializer for true RAM offsets. If a new
  question needs new test data (like TRIG_MODE=ONE2 above), the answer is to ask the user
  to export it from real hardware, not to synthesize it.
- **A whole-image operand scan beats trusting one function's obvious callers.** Several key
  findings this session (`FUN_4009f3a4` reading PLAYS_FREE/DIRECT directly,
  `_DAT_80000012`'s single write site, SCALE_MODE's actual reader set) only turned up by
  scanning every instruction in the entire binary for a target literal/offset value,
  because register-relative addressing doesn't show up as a searchable byte pattern the way
  absolute addressing does.
