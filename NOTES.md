# RE Log — Octatrack

Record of findings. Each run of `analyze.sh` leaves evidence in `out/`.

> **New session? Read `START_HERE.md` first.** This log is chronological and starts at
> 2026-07 recon — do NOT read top-to-bottom. Jump to the newest `## Session N` section and
> its `STATE OF PLAY` / `NEXT` blocks. Section index: `grep -nE '^## ' NOTES.md`.

## Phase 0 — Static recon of the public OS  [COMPLETED ✓ 2026-07-26]

Goal: decide whether the payload is **compressed** (feasible) or **strongly encrypted** (blocking),
and identify where the real ColdFire code lives. **Result: compressed. Firmware obtained.**

Checklist:
- [x] Download official OS 1.40C + record sha256
- [x] Entropy of `.bin` and `.syx`
- [x] elektron-firmware-tool on `.syx` → extracts the raw section, checksums OK
- [x] Confirm container: Octatrack uses **ELEK** (within the supported family)
- [x] Save the decompressed raw as a candidate for disassembly
- [ ] binwalk on the `ELUP` `.bin` (optional; the `.syx` already yielded the raw)

### Results

- **Artifacts** (`OCTATRACK_OS1.40C_dist.zip`, sha256 `370c55a3…73ff0`):
  - `.bin` (459 KB): magic `ELUP`, entropy **uniform ~8.0** (compressed end to end).
  - `.syx` (641 KB): `F0 00 20 3C` (SysEx + Elektron ID) wrapping the **`ELEK`** container.
- **elektron-firmware-tool `-i`**: `device: Octatrack (0x05)`, `version 1.40C`,
  container `ELEK` 469834 B, **section id 3 "MAIN OS" → 1,112,560 B decompressed**, checksums OK.
- **Decompressed raw** `out/raw/section_3_MAIN_OS.bin` (1,112,560 B):
  - Mean entropy **5.5** (real code+data, NOT encrypted); only 3.8% of windows are high.
  - **2908 readable strings**: UI menus, error codes, `/octatrack_factory_os.bin`.
  - m68k big-endian disassembly OK: prologue `lea -0x1c(a7),a7` + `movem.l d2-d7/a2,(a7)`.

### Memory-map clues (from absolute refs in the code)

- **Data/BSS in SDRAM base `0x40000000`** (refs to `0x400b9650`, `0x400b9654`, `0x400dea48`).
- Initial stack loaded from ~`0x48000000`.
- **Code load** base: still to be determined (the raw starts in code, not in a vector table).
  Strategy: locate where the code references its own strings to fix the base address.

## Phase 1 — Static disassembly  [IN PROGRESS]

- Target: **ColdFire / m68k, big-endian**.
  - radare2: `./disasm.sh` (already configured with base and arch).
  - Ghidra: processor `68000`, big-endian, base `0x40000400`; then run
    `tools/ghidra_import.py` to define strings/pointers and populate xrefs.

### BASE ADDRESS DETERMINED ✓  =  `0x40000400`

Empirical method (`tools/find_base.py`, not an assumption): correlate the offsets of
the 2607 strings in the file against the absolute 32-bit pointers (high byte 0x40).
The sweep of candidate bases gave an unambiguous peak:

| candidate base | strings with a direct pointer |
|---|---|
| **0x40000400** | **1441** |
| 0x400003dc | 291 |
| 0x40000424 | 290 |

- Peak 5× over the second → solid base. Image in SDRAM `0x40000000` + `0x400` of
  header/vectors; the decompressed MAIN OS section maps at `0x40000400`.
- Data/BSS at `~0x400bxxxx` (consistent: `0x400b9650 - 0x40000400 = 0xb9250`, inside the image).
- Verified in r2: at `0x40000400` the prologue `lea -0x1c(a7),a7` + `movem.l` appears,
  and there is code `move.l #0x400b3349,d0` loading pointers to strings as immediates.
- Artifacts: `out/base.txt`, `out/pointers_to_strings.csv` (**1993 sites** ptr→string).

### Function → UI-strings map ✓  (`tools/string_func_map.py` → `out/string_function_map.txt`)

Detects string-pointer loads in the code (`pea`/`lea`/`move.l #imm`) and walks back
to the function prologue (`LINK`/`lea -n(a7),a7`). **619 functions** anchored to strings,
1060 refs in code. Key functions already identified by name:

| Function | What it is (by its strings) |
|---|---|
| `0x40086d7a` | **Serializes project settings** — 58 keys (`MIDI_CLOCK_SEND`, `MASTER_TRACK`, `MAIN_LEVEL`…) |
| `0x4001fc1e` | **Error handler** — 49 strings (`WAV/AIFF PARSE ERROR`, `SAMPLE NOT UNLOADED`…) |
| `0x400867e0` | Writes the `[SAMPLE]` section of the project file (OctaLib format) |
| `0x40069424` | `FORMAT CARD` handler |
| `0x400645ce` | `SAVE PROJECT` handler |
| `0x40022fdc` | `COLLECT SAMPLES` handler |

This **connects the firmware to the already-documented file format**: the settings
function emits exactly the project-file keys in plain text.

### Decompilation in Ghidra ✓  (12.1.2, language `68000:BE:32:Coldfire`, base 0x40000400)

Project in `out/ghidra_proj`. Scripts: `tools/GhidraDecompile*.java` (Ghidra 12 doesn't ship
Jython → use Java). Logs: `out/ghidra_decompile*.log`.

**Finding: shared dialog constructor `FUN_4006d57c`**
Reconstructed signature: `(title, num_options, char **options, default, confirm_callback)`.
Creates a popup (measures text with the font at `DAT_400ba876`, window via `FUN_4005829c`) and
stores the confirmation callback. Every action menu calls it with its label.

**OS UPGRADE flow traced end to end** (`FUN_400636bc`):
```c
if (FUN_400448dc() == 0)   FUN_40063660(0);          // no playback -> direct upgrade
else {                                                // with playback:
    opts = {"PLAYBACK WILL BE", "STOPPED. CONTINUE?"};
    FUN_4006d57c("OS UPGRADE", 2, opts, 3, FUN_40063660);  // dialog; on confirm -> FUN_40063660
}
```
→ **`FUN_40063660` = the actual OS update routine** (next target to decompile).

**ColdFire hardware registers identified** (in the init/main function `~0x4001fc1e`):
- `0x20000008` — status register polled at boot (`while ((*0x20000008 & 6)==0)`).
- `0xfc04xxxx` — on-chip peripheral space (MBAR) of the ColdFire (init writes).
- App data/BSS at `0x460exxxx` and `0x46c8xxxx` (in addition to the `0x400bxxxx` of the image).

**Known limitation**: the ColdFire decompiler fails ("Cannot properly adjust input
varnodes") on large functions with complex frames (e.g. `project_settings_serialize`
5388 B, `project_sample_section` 1434 B). The disassembly does work; analyze them at the ASM level.

### Complete OS UPGRADE chain (decompiled) ✓  — logs `out/ghidra_{osupgrade,flashwriter,apply,program}.log`

```
OS UPGRADE menu
  └─ FUN_400636bc   confirms "PLAYBACK WILL BE / STOPPED. CONTINUE?" (via FUN_4006d57c)
      └─ FUN_40063660 (os_upgrade)  stops audio, "WORKING PLEASE WAIT", QUEUES a deferred task:
          └─ FUN_4006370c  scans the CF, picks the OS file, validates existence
              └─ FUN_40080434 (os_apply_flash)  critical section + calls the loader and maps errors
                  └─ FUN_4007f748 (os_file_program)  << THE CORE: parses/deobfuscates/verifies >>
```

**`FUN_4007f748(path, mode)` — OS file format (`.bin`, magic ELUP):**
- `fopen`; if it fails → `-1 IO_ERROR`. Reads size; `payload = filesize - 0xC` (12 B header).
- `payload > 0x100000` (1 MiB) → `-3 LENGTH_ERROR`.
- Header (words): `[0]`=magic (`== DAT_400a966c`), `[1]`=feedback seed,
  `[3]`=flags (bit `0x800000` selects variant) + checksum, `[4]`=version material. Payload from `[2]`.
- **Obfuscation = XOR cipher with feedback** (not compression, not real crypto):
  each word: `p ^= 0x9E3B16A2` (or `0x764E28CA` if flag) → rotate/byteswap → `k ^= prev_cipher ^ x`.
  Constants **fixed and embedded** → the `.bin` is fully decodable offline. (This explains the
  uniform ~8.0 entropy of Phase 0: it's this XOR stream, not compression.)
- **Integrity = additive checksum** (sum of deobfuscated words) compared with the stored value;
  mismatch → `-4 CHECKSUM_ERROR`. **There is NO cryptographic signature** (confirms Phase 0: modifiable firmware).
- **Model/version gating**: `< "0156"` (0x30313536) → `-5 MK1_OS_NOT_ALLOWED`;
  `< "0178"` (0x30313738) → `-6 CAN_NOT_DOWNGRADE`. Version = packed ASCII.
- `mode=1` → only returns the version (for the pre-scan). `mode=0` → deobfuscate+verify (commit).
- On successful completion: `FUN_4007fe80(0xffffffff,...)` finalizes/reboots into the new OS.

### Offline `.bin` decoder ✓✓✓  (`tools/bin_decode.py` + `tools/decode_elek.c`)

Reimplementation of `FUN_4007f748`. Constants extracted from the OS image:
`magic=0x454C5550 ("ELUP")`, `C3=0x360FA955`, `C7=0xEF4A9AB6`, XOR `0x9E3B16A2`/`0x764E28CA`.

**Double validation:**
1. `bin_decode.py` deobfuscates the `.bin` and **the additive checksum matches** (`0xa85be7ef`) — the
   SAME check the firmware performs → deobfuscation demonstrably correct.
2. The deobfuscated payload is an **`ELEK0178`** container (identical to the one in the `.syx`). `decode_elek.c`
   (reuses the tool's `ap_depack`/aPLib) decompresses it → **1,112,560 B, SHA256 `164f3122…`**,
   **byte-identical to the MAIN OS extracted from the `.syx`**.

**Full chain reconstructed and verified end to end:**
```
.bin  = [ELUP hdr][seed] + XOR-feedback( [len] + ELEK(aPLib(MAIN_OS)) ) + checksum
.syx  = SysEx 7-bit( ELEK(aPLib(MAIN_OS)) )
                                    → both → the SAME MAIN OS (164f3122…)
```
No cryptographic signature at any layer: reversible XOR obfuscation + additive checksum + aPLib.

### Full storage stack (decompiled) ✓ — logs `out/ghidra_{commit,hw,drv,fac,prim,txn,disp,ata}.log`

Traced from the UI down to the ATA registers. **The OS "flash" = writing to the CompactFlash (ATA)**;
the driver is the ColdFire ATA block stack, NOT an internal NOR flash.

```
os_apply_flash (FUN_40080434)
  └─ FUN_400323a0   dispatches via driver vtable: (*(obj+0x10))(0)   [obj=_DAT_460d16cc]
      └─ FUN_400158cc  = method +0x10 → sends ATA command 0xE0 (STANDBY IMMEDIATE: flush/park before reboot)
          └─ FUN_4001568c  QUEUES the command (async ring, 0x16 B entry) + reserves event (_DAT_460babfc) + waits
              └─ FUN_40015098  DISPATCHER: drains the queue and dispatches by ATA opcode:
                    0x20 READ SECT · 0x30 WRITE SECT · 0x87 · 0xC0 · 0xC8 READ DMA · 0xCA WRITE DMA · 0xE0 STANDBY
                    └─ FUN_40014c48 (0x30 WRITE SECTORS)  ← ATA task-file registers @ 0x90000000 (FlexBus)
```

- **Factory driver** `FUN_40015e28`: builds the vtable at `&DAT_46c85c76` (methods 0x400157xx/0x400158xx)
  and **detects the hardware variant** by reading an IDENTIFY-type descriptor (offsets 0x62/0x6a/0x146/0x7e/0xb0).
- **Confirmed hardware map**:
  - ATA host registers (control/config) in ColdFire MBAR: `0xFC04_51xx`, status `0xFC0A_4039`.
  - ATA task-file (data/LBA/cmd/status) in FlexBus window `0x9000_00xx`
    (data=0xa0, seccount=0xa8, lba=0xac/b0/b4, dev=0xb8, cmd=0xbc, status=0xd8).
  - **The MCF5445x has an on-chip ATA controller → corroborates the ColdFire CPU from Phase 0.**
- **RTOS confirmed (not bare-metal)**: async I/O via command queue with event-based completion
  (bit pool at `_DAT_460babfc`), a worker that drains the queue. There are tasks and synchronization.

## Musical layer — audio/sequencer engine (decompiled) — logs `out/ghidra_{play,trk,voice,voice2}.log`

Engine data map (all in the RAM window `0x80000000` = hot state):

| Structure | Address / layout | What it is |
|---|---|---|
| Per-track voice state | base `0x800049d8`, stride `0xA8`, 8 tracks | live audio voice; byte[0] = active |
| "Active voice" query | `FUN_40000ee0(t)` reads `0x800049d8[t*0xA8]` | 0=inactive, 1/2 per `0x8000184a` |
| "Something sounding" query | `FUN_400448dc` walks the 8 tracks | used by OS-upgrade to block |
| MIDI tracks state | `0x80006500[t]`, global `0x800065b8` | MIDI mute/active flags |
| Voice command mailboxes | `0x46c7e9fa`/`0x800018be`/`0x800018de` `[t*4]` | `FUN_40005178` queues per-track commands |
| Per-track pattern data | `_DAT_46c82456 + pat*0x18b2 + trk*0xc` (+0x8f385) | sequenced data (trigs/params) |
| Globals | current track `0x100b14cc`, current pattern `0x80000003` | live selection |

**Confirmed architecture — same pattern as storage**: `FUN_40005178` (command voice)
writes mailboxes in RAM, consumed asynchronously by an ISR/feeder that feeds the DSP56xxx.
Hardware (DSP) behind an async boundary, just as ATA was behind the command queue.
→ reinforces the RTOS model: producer (sequencer) / consumer (audio ISR) decoupled by RAM.

## RTOS identified: Elektron's PROPRIETARY microkernel ✓ — log `out/ghidra_kern.log`

Not MQX/ThreadX/VxWorks/Nucleus. Negative evidence: **zero** copyright/version/API strings
of a commercial RTOS. Only banner: `ElektronOctatrack DPS-1 0002 / FROM WWW.ELEKTRON.SE`
(DPS-1 = internal Elektron platform, shared across their ColdFire machines).

Positive evidence — preemptive priority-based microkernel, decompiled primitives:
- **`FUN_40000818` (wait_event)**: if `*ev < 1`, links the current TCB into a wait list, state
  `TCB[0x13]=0` (blocked), and executes **`TRAP #0`** = context switch via m68k software trap.
- **`FUN_40000c3c` (post/queue)**: writes ring buffer; if a task is waiting, marks it `TCB[0x13]=1`
  (ready), inserts it into the **priority ready queue** (doubly-linked circular lists), and forces
  reschedule with `0xFC04_C010 |= 0x800` (ColdFire interrupt controller).
- **TCB**: state@0x13, priority@2, list pointers@0/1. Current task = `_DAT_800068fc`.
  Top-priority pointer = `_DAT_800068d8`.

**Unifies everything above**: the ATA stack's "async queues" and the audio engine's "voice mailboxes"
ARE this kernel's message/event queues. A single microkernel, used throughout the firmware.

## DSP interface and audio pipeline ✓ — logs `out/ghidra_{dsp,frame}.log`, r2 disasm

**Physical DSP interface: MMIO at `0x20000000`** (revealed by r2; Ghidra's ColdFire module
fails on this hot-path code). Handshake registers:
- `0x2000_0004` command/status (writes `0x8C`, polls busy bit7 until ack)
- `0x2000_0008` status ("DSP ready": boot polled `while ((*0x20000008 & 6)==0)`)
- `0x2000_001c` frame index reported by the HW → selector of the double buffer `0x800000e0`

**Full pipeline (control path):**
```
sequencer trig
 → FUN_40005178 writes voice mailbox (0x46c7e9fa / 0x800018be) in RAM
   → FUN_4000c8a4 (frame builder, control-rate) consumes mailboxes, updates 8 voices,
      assembles a parameter FRAME in a double buffer in shared RAM 0x80000000 (ping-pong 0x800000e0)
     → handshake via 0x20000000 (reads frame idx 0x1c, writes cmd 0x8C to 0x04, polls busy)
       → DSP56xxx reads the frame from 0x80000000 and synthesizes (samples, time-stretch, filters, FX)
```

**ColdFire↔DSP split**: ColdFire = control (RTOS, sequencer, assembles voice parameters).
DSP56xxx = signal (real-time audio). Synchronized by **double buffer + register handshake**.

### Consolidated memory map
| Window | Use |
|---|---|
| `0x40000000` / `0x46000000` | SDRAM: code (img @0x40000400) + app data/BSS |
| `0x20000000` | **Audio DSP coprocessor** (cmd 0x04, status 0x08, frame idx 0x1c) |
| `0x80000000` | Fast/shared RAM: voice state, mailboxes, **double-buffered DSP frames** |
| `0x90000000` | ATA task-file (CompactFlash) via FlexBus |
| `0x100b0000` | Small globals (current track/pattern) |
| `0xFC000000` | ColdFire on-chip peripherals (MBAR: ATA host, interrupt ctrl 0xFC04C010, etc.) |

## Sequencer clock ✓ — logs `out/ghidra_clock.log`, r2 disasm

**The sequencer has NO timer of its own: it is clocked by the DSP's audio FRAME interrupt**
(sample-accurate sequencing). Phase accumulator:
- Tempo → `_DAT_80001814`; per-frame increment `_DAT_80001820 = 2³¹ / tempo`
  (seen in `FUN_4000c8a4`: `_DAT_80001820 = -0x80000000 / _DAT_80001814`).
- `FUN_4009c550` sets the tempo period from the pattern data (`_DAT_46c82456 + pat*0x8ed8`).
- The **frame ISR** (`0x4000aad0`, fires on reading the frame index at `0x2000001c`) accumulates the phase;
  on overflow it advances the step and **posts to a kernel queue** (`FUN_40000c3c`) to wake the
  sequencer task → trigs → `FUN_400977cc` → voice command. Refs to tempo/phase at `0x4000axxx`.

## DSP program: located and extracted ✓ — `out/dsp_region.bin`

**DSP56300 (24-bit), at the TAIL of the MAIN OS image** (`~0x400e2000 .. 0x4010fdf0`, ~188 KB
= ~62,600 24-bit words). Confirmed: `f803 00bb …` = DSP56k opcodes, loaded 3 bytes at a time.
- Loaders: `FUN_40001d4c` → **P** memory (24-bit stream, starts with `0x20000000=0x81`);
  `FUN_40001b18` → **X/Y** data. Uploads in sections to DSP `0x31000`, `0x32000`, …
- Blobs from init: `0x400e21e0`(len 0x96→0x31000), `0x400e2276`(0xae→0x32000), `0x400e2324`, `0x400f59ef`.
- **For the FX/timestretch**: disassemble with target **DSP56300** (Ghidra/r2 don't ship it; a56/dsp56k
  or community SLEIGH modules). Separate project.

## Go/No-Go — patch "preserve volume when switching Part" → FEASIBLE (corrected)

- The behavior lives in the **audio hot path** (`FUN_4000c8a4` + core `0x40009xxx`/`0x4000cxxx`):
  the frame builder reads the **active Part** (`0x80000002`) and **active pattern** (`0x80000003`) every frame
  and from there takes the LEVEL → that's why it jumps when switching Part (continuous read, not "apply on change").
- **CORRECTION of a previous erroneous verdict**: it is NOT blocked by tooling. Ghidra's ColdFire SLEIGH
  **already decodes** this code (defines MAC/MSAC/MACSR/ACC0-3; `71f9`/`73c3` = `mvz.w`/`mvs.w`).
  Verified: the frame builder disassembles **121 clean instr., 0 gaps** in Ghidra's listing.
  The previous error was using **radare2** (blind to ColdFire extensions) for the check and confusing the
  **decompiler** failure (no C) with a **disassembler** failure (which does work).
- **Rule**: for the audio core → use **Ghidra's listing** (not radare2, not the decompiler).
- **EMAC sub-project: UNNECESSARY** (Ghidra already covers the ISA). Off the roadmap.
- Remaining work for wish 1 (hard but defined, NOT blocked): read the frame builder's ASM
  to find the exact LEVEL read, map free RAM in the voice struct (`0x800049d8`/0xA8),
  design the per-voice latch, patch at the byte level, test on hardware.

### A vs B confirmed → **B**, and intermediate "lazy apply on first trig" design
- `FUN_40005030` (trig helper) reads **pattern** data (`_DAT_46c82456 + pattern*0x18b2`, which sample)
  and writes sample-slot + voice command + DSP double-buffer, but **does NOT refresh the Part params**
  (doesn't touch `0x80000a50` nor the Part data `*0x9b340`). → The trig fires the sample but does NOT re-apply
  the Part. Only `FUN_40009094` (by event) does. **Scenario B confirmed.**
- Intermediate design (apply destination Part on the first trig per track): **moderate**. Components:
  "pending" flag per track (or better: **Part-applied per track**, 8 bytes), skip reload of sounding
  tracks in `FUN_40009094`, per-track apply, hook in the trig (`FUN_40005030`/`FUN_400977cc`).
- **Layered params model**: base Part → (FUN_40009094 on change) base buffer → per-frame computation
  (LFO/scene/p-lock, in `FUN_4000c11a` 5066 B, does NOT decompile) → `0x80000a50` → DSP. The voice-in-transition
  uses the buffer, which derives from the ORIGIN Part if we skip the reload → no jump. ✓

### AUDIO PATCH SPEC (intermediate design) — per-track infrastructure ALREADY EXISTS ✓
- **`per_track_part[8]` @ `0x8000182a`, `per_track_pattern[8]` @ `0x80001832`** already exist. Both
  param appliers (frame builder + `FUN_40002df4`) are gated by `per_track == active`.
- The jump originates in the frame builder: when `GLOBAL_applied (0x80001828) == active`, at `0x4000ca32`
  it does `per_track_part[track]=active` (`move.b D1,(A5)`) + `0x4000ca34` pattern + applies params.
- **Patch**: at `0x4000ca30` (update+apply block), gate: if `voice_active[track] && !trig_pending`
  → jump to `0x4000ca76` (keeps old per_track → no jump). Otherwise → apply + clear pending.
  Both appliers respect it for free (they key on per_track). Trig hook (`FUN_40005030`):
  `trig_pending[track]=1`. Active voice = `0x800049d8 + track*0xA8` byte0.
- **Resources**: code cave `0x400d64da` (5986 B of 0x00); free RAM `0x80006a00` (340 slots) for
  `trig_pending`. Minimal new state (the Part array already exists). Difficulty: medium, bounded.
- Improvement over the previous idea of patching `0x4000c8c6`: reuses existing infra, 1 gate + 1 hook.
- Knob→Part editor: cornered to the cluster `0x40041–0x40043` (param pages), writes the Part data
  without calling `FUN_40009094`; still need to pin the exact function (1 pass). Enables the GUI-in-transition
  (route editing through `per_track_part[track]`, which already exists).

### DYNAMIC ANALYSIS OPERATIONAL — ColdFire emulator (Unicorn) ✓✓
- `tools/emu_validate.py`: validates that **Unicorn (m68k) runs the firmware's ColdFire** —
  `FUN_40000e50(5)` → `0x80004d20` exact. Viable approach.
- `tools/emu_trace.py`: memory-write tracer. Ran `FUN_40009094(0,0)` and **dynamically confirmed**
  that it writes `0x80001828/29` (global), `0x8000182a/32` (per-track), `0x80000a50` (voice).
- `tools/emu_find_editor.py`: sets up globals (`_DAT_46c82456`=project, track/pattern/mode) and traces
  candidates watching for writes to the Part data. **Found `FUN_4005a918`**: writes 6 params/page
  to the Part data (`0x8edaa`…) + dirty flag (`0x9b332=1`); operates on `DAT_100b14cc/cf/46c7d8d8`.
  (Probably page recall/scene/commit, not the single-encoder editor — but the dynamic
  methodology works to pin any function.)
- **Proven method**: set globals + trace + watch writes to the `_DAT_46c82456`-region = hunt editors.
  The single-encoder editor is a few more traces away. CPU exception sometimes (~0x40000c42, unsupported
  instr) but the useful trace happens before it.

### "LAZY PART" BEHAVIOR PATCH — IMPLEMENTED AND VALIDATED ✓✓✓ → `out/OCTATRACK_OS1.40C_LAZYPART.syx`
- **Behavior**: on pattern change, SOUNDING tracks keep their params (no volume
  jump); on their first trig they apply the new Part (via the frame builder's D6 gate).
- **Implementation** (save/restore, `tools/patch.s`, assembled with `m68k-elf-as -mcpu=5407`):
  - Cave at `0x400d64e0`: `save_stub` (0x400d64e0) + `restore_stub` (0x400d6538), 184 B.
  - ENTRY detour `0x40009094` → `save_stub`: bulk-saves voice buffers (`0x80000a50`, 0x200 B) +
    per_track (`0x8000182a`, 16 B) + "sounding" flags (`0x80006c20`); runs the displaced lea+movem;
    `jmp 0x4000909c`.
  - EXIT detour (tail-call) `0x40009664` → `restore_stub`: restores voice buffer + per_track of the
    tracks that were sounding; `jmp 0x40000c3c` (original tail-call to post_work). RAM save: `0x80006a00`.
  - Key: `FUN_40009094` does NOT end in rts but in tail-call `jmp 0x40000c3c` @0x40009664 (the rts
    @0x40009844 belongs to ANOTHER function — bug fixed).
- **Validated in the Unicorn emulator** (`tools/emu_clean.py`): sounding track preserved (0xEE intact),
  silent tracks updated. Pre-stub of post_work + 1 EMAC func (instructions Unicorn doesn't
  support; on real HW they run normally).
- **Repackaging**: `-c 3` → `.syx` with `checksums: ok`. Flashable. Final test = flash over MIDI
  (recoverable).

### GUI-IN-TRANSITION PATCH — IMPLEMENTED AND VALIDATED ✓✓✓ → `out/OCTATRACK_OS1.40C_LAZYPART_GUI.syx`
- **What it does**: turning a knob while a track is in transition (per_track_part≠active) edits the ORIGIN
  Part and live-updates (sculpts the sound-in-transition in real time). Combines with the audio.
- **Editor** = `FUN_40052e98(enc,delta)`. Addressing (traced in the emulator): writes the param to
  `_DAT_46c82456 + DAT_100b14cf*0x18b2 + (iVar10*8+track)*0x20 + enc + 0x8f3e2`; live-update gated by
  `0x80000002==per_track_part[track] && 0x80000003==per_track_pattern[track]`. iVar10 = part of
  DAT_100b14cf (from `+0x8ed91`).
- **Discovery**: redirecting ONLY `DAT_100b14cf`→per_track_pattern makes it read/write the source
  (iVar10 still follows); + `0x80000002/03`=source passes the gate → live-update. (emu: TRANS without patch
  writes dest without sounding; with override writes source `0x90df4` + live `0x80000f94/95`.)
- **Implementation** (`tools/patch_gui.s` — SUPERSEDED by `patch_gui2.s`, it was not reentrant;
  see "Hardware crash and fix" below. m68k-elf-as+ld @0x400d6600): wrapper with **return-hook**.
  Entry `0x40052e98`→setup: if in transition, saves+sets globals to source, replaces the return on the stack
  with cleanup. cleanup restores globals + jmp to the real return (covers rts + the editor's tail-call). Save
  area `0x80006c30`, cave `0x400d6600` (no overlap with audio: cave `0x400d64e0`, RAM `0x80006a00`).
- **Validated**: transition→source+live; normal→intact; globals restored; robustness 4 tracks × 4
  encoders (incl. LEVEL=6) all ✓. Combined repackage (audio+GUI) checksums ok.
- **Caveat**: sets `0x80000002/03` temporarily (µs) for the gate; the frame builder skips sounding
  non-triggered tracks (D6 gate) → track in transition unaffected; µs risk window is negligible. Documented.

### FLASHABLE FIRMWARE CHAIN — PROVEN END-TO-END ✓✓✓
- POC patch: `COLLECT SAMPLES` → `COLLECT SAMPLEZ` in the raw MAIN OS.
- Repackaging: `elektron-firmware-tool -i orig.syx -c 3 mainos_patched.bin -o patched.syx`
  (recompresses aPLib + rebuilds ELEK + recomputes checksums).
- `-i patched.syx` → **checksums: ok**. The Octatrack would accept it.
- Byte-perfect round-trip: decompressed differs from the original by **1 byte** (the one we changed).
- → `out/OCTATRACK_OS1.40C_patched.syx` is valid, flashable modified firmware. **Full chain
  decode→patch→recompress→checksum→.syx demonstrated.**

### BEHAVIOR PATCH SITE — CONFIRMED in clean code ✓
- Frame builder D6 gate (`0x4000c9e2 beq 0x4000ca7c`): **the frame builder only applies params
  when there's a trig (D6≠0)**. → The volume jump comes from `FUN_40009094` (per-event applier).
- **Clean patch**: in `FUN_40009094`, skip the apply for SOUNDING tracks (`0x800049d8+track*0xA8` byte0).
  A sounding track keeps its params; on its first trig, the frame builder (D6≠0) applies the new Part.
  No `trig_pending`, no trig hook — the existing D6 gate is enough. Decompilable code.
- Pending implementation: nail the insertion point in the `FUN_40009094` loop (nested loops,
  SP-relative locals), assemble the cave (m68k-elf-as installed), detour, and **validate in the emulator**.

### ENCODER EDITOR PINNED (dynamic + static) ✓✓ — `FUN_40052e98`
- Hunted with `tools/emu_batch.py` (classified functions by number of params written → the 1-param ones).
- **`FUN_40052e98(param_1=encoder_idx 0-6, param_2=delta)`**: the encoder parameter editor.
  - `def = FUN_40031f28()` (param min/max). encoder 6 = LEVEL (special case 0/0x7f).
  - else: reads the current Part value (`…+0x8f3e2`), adds `param_2` (delta), clamps, writes back.
  - Writes the Part data indexed by **`DAT_100b14cf` (DISPLAYED pattern)** + dirty flag `0x9b332=1`.
  - **ALREADY integrates per-track**: only updates the live sound buffer if
    `DAT_80000003==per_track_pattern[track] && DAT_80000002==per_track_part[track]`.
    → If the track is in transition, it writes the Part but does NOT touch the sound — out of the box.
- **GUI-in-transition**: redirect the editor's write destination to `per_track_part[track]` during
  transition (same class of patch as the audio); the live-update check already uses per_track. Sizing closed.

### TWO "current" POINTERS — key to GUI-in-transition ✓
- **`DAT_80000003`/`0x80000002`** = **sounding** pattern/Part → used by the AUDIO engine.
- **`DAT_100b14cf`** = **displayed/edited** pattern → used by GUI and EDITOR (`FUN_40031da4` reads
  `_DAT_46c82456 + DAT_100b14cf*0x18b2 + …`). They are distinct: that's why after the change you see the destination
  but hear the origin.
- Editor/param definitions: `FUN_4004f5f8` (audio branch) → `FUN_40031ee0` → `FUN_40031da4`
  (descriptor selector by machine type; min@0x6a, max@0x9a). It's the definition machinery,
  not the value writer.
- **GUI-in-transition = redirect `DAT_100b14cf` (GUI pointer, SEPARATE from the audio one) toward the
  per-track origin Part during transition.** Same class as the audio patch, they don't interfere.
- Honest limit: the exact audio param value writer was NOT pinned by static analysis (scattered
  through UI). Efficient path to close it: **dynamic analysis** (ColdFire emulator, QEMU m68k style)
  observing what runs when a knob is turned. The emulator we have (`dsp56300`) is for the DSP, no use here.

### PATCH POINT (earlier, superseded) — the Part index is per-track in the hot path
- The knob→Part editor **writes the Part data** (persistent); does NOT call apply. The sound is
  updated because the per-frame compute **re-reads the active Part every frame, per track**.
- The callers of `FUN_40009094` are structural (paste part, assign part), NOT the knob editor.
  → The volume jump originates in the per-frame re-read, NOT in `FUN_40009094` (corrects the previous GO).
- **Surgical point**: `0x4000c8c6` `move.b (0x80000002).l,D0` (bytes `1039 80000002`), INSIDE the
  per-track loop (0x4000c8a2→0x4000ca90, counter D4). The value flows to `A2 → D1*0x9b340` (indexes the
  Part) at `0x4000ca44`. It's code that Ghidra reads cleanly.
- **Patch**: reserve `part_per_track[8]` in RAM; replace the 6 bytes with `jsr code_cave` (6 bytes);
  the cave does `D0=part_per_track[D4]; rts`. Maintain the array: pattern change → old Part for
  sounding tracks; first trig → active Part. Controls ALL params (not just level). **Surgical.**
- Caveat: verify whether there are OTHER re-reads of the active Part for audio (0x80000002 is read 1× in this
  function, but the function is large; confirm this loop is the main param path).

### GUI-in-transition (edit the origin Part's params while the track transitions) — FEASIBLE, the most complex
- The origin params ARE live in the engine (working buffer) during the transition.
- Elegant implementation: use **Part-applied per track** (the same as the intermediate design) also
  for the GUI → edit/display `applied_part[selected_track]` instead of the global active Part.
  This way audio and GUI share a mechanism.
- Extra cost over the intermediate design: route the **parameter editing path** and the **display** path
  through `applied_part[track]`. RE still needed: locate the knob→param editor (the write to the Part data
  is probably decodable code; the per-frame compute `FUN_4000c11a` is the one that doesn't decompile,
  but for editing you don't need to touch it). It's the most ambitious front of the ones discussed.

### Go/No-Go redone with Ghidra → **GO** (cleaner than expected)
- **`FUN_40009094` = "apply Part parameters to the voices"** (decompiles to clean C). Reads Part params
  (`part*0x9b340 + pattern*0x18b2 + track*0x1e + …`) and writes them into the voice working buffer
  `0x80000a50 + track*0x40` (16-bit per param) that the frame builder copies to the DSP double buffer.
- **It's BY EVENT, not per frame**: the frame builder compares active Part (`0x80000002`) vs. applied
  (`DAT_80001828/29`) and only triggers the reload on change. `FUN_40009094` has 9 callers, all
  event handlers (pattern/part change, load). → **That's where the volume jumps.**
- **Clean hook, NO new RAM**: the buffer `0x80000a50` already IS the "current level". To preserve the
  origin level it's enough to **NOT overwrite the LEVEL field for tracks that are sounding** inside
  `FUN_40009094`; the buffer keeps the old value (free latch). On the next trig, the normal path
  applies the new Part's level (desirable).
- "Sounding" = active voice flag in `0x800049d8 + track*0xA8` byte 0 (already read by `FUN_40000ee0`).
- Still needed for the concrete patch: (1) fix the exact offset of the LEVEL field within the params block,
  (2) insert the "skip if sounding" conditional (a code-cave possible for space), (3) test on hardware.
- This also covers the spirit of wish 2 (state preserved until manual re-trigger).

### DSP56300 toolchain ready ✓
- Disassembler built: `vendor/dsp56300` (Access Virus emulator) → `build/.../dsp56kDisassemble`.
  Usage: `dsp56kDisassemble -in blob.bin -pc <addr_hex>` (big-endian by default, 3 bytes/word).
- Validated: boot blobs disassembled (`out/dsp_disasm/mod_31000.asm`, `mod_32000.asm`) → code
  coherent with peripheral registers (`M_TCR0/1`, `M_DCR2`), parallel moves. They are init routines.
- Still needed: enumerate each module's load addresses (from the ColdFire call-sites) to disassemble
  the entirety of `out/dsp_region.bin` with the correct `-pc` per module → effects + timestretch.

### Coverage statistics (measured in Ghidra)
- ColdFire image: **2165 functions** total; **27 named/analyzed** by us (~1.2%).
  187,114 instructions, 593,744 B of code (of 1,112,560 B; the rest is data + DSP program).
- DSP program: ~62,600 24-bit words, 0 functions analyzed (toolchain now ready).

### Pending (Phase 1+)
- Disassemble `out/dsp_region.bin` by modules (with correct `-pc`) → effects + timestretch.
- Sequencer depth: p-locks, conditional locks, scenes/crossfader, LFO designer.
- MIDI subsystem (parser/sync) and UI/display framework.
- Remaining ATA handlers; large functions the ColdFire decompiler doesn't lift.
- Extract the vector table (0x400 preamble, NOT in this section) for the ISR map.

## Phase 2 — Hardware (only if needed)  [PENDING]

- UART on the PCB → boot logs (cheap, low-invasive).
- ColdFire uses **BDM** (Background Debug Mode), not ARM JTAG → live flash dump.
- Desolder flash + external programmer = last resort.

## Open questions

- Exact RAM and DAC/ADC codec (undocumented; require teardown/PCB photos).
- Do MKI (DPS-1) and MKII share the OS container format?
- Is there signature/encryption beyond checksums? (resolved by Phase 0)
- Is there any flash dump or community Ghidra project beyond OctaLib/ot-tools-io/elektron-firmware-tool?

## Sources

- Elektron support: https://www.elektron.se/support-downloads/octatrack-mkii
- elektron-firmware-tool: https://github.com/mischa85/elektron-firmware-tool
- OctaLib (Research.md): https://github.com/snugsound/OctaLib
- Elektronauts CPU thread: https://www.elektronauts.com/t/octatrack-cpu-chip-model/93304
- Modding firmware thread: https://www.elektronauts.com/t/modifying-elektron-firmware/36228
- EFF RE FAQ (legal): https://www.eff.org/issues/coders/reverse-engineering-faq

---

## Boot branding — change the displayed version (splash + SYSTEM STATUS)

**Goal**: the first screen on power-on shows `1.40C`; change it to custom text.

**Finding (RE)**: the displayed version is NOT in the MAIN OS code. It lives as text in the
**ELEK container header**, which ends up in flash starting at `0x4000` after flashing
(`FUN_4007fb84` = flash writer writes the container to flash 0x4000). Header layout:
- `0x00` `"ELEK"` (magic)
- `0x04` `"0178"` (internal version code; used by the downgrade check `< "0178"` — DO NOT touch)
- `0x08` DISPLAY version field: `"     1.40C\0"` (5 spaces + version, right-aligned,
  fixed width **10 chars**, offsets 0x08–0x11). The byte `0x12` = high byte of the aPLib section
  length (always `0x00` for an OS < 16 MB) → serves as the **NUL terminator** of the string.
- `0x12` onward: compressed aPLib section (offset **hardcoded** in the device's decompressor).

The MAIN OS reads the display via `FUN_40069848` (SYSTEM STATUS menu): `DAT_400a95c0` = first entry of
the **flash sector table** = `0x4000`; reads `0x4000 + 8 = 0x4008`, skips spaces, copies ≤10
chars. The **bootloader** (outside our container) draws the splash reading the same `0x4008` — that's
why the splash reflects the installed OS version. Editing `0x08–0x11` changes **both**.

**Hard limit**: the field is 10 chars (0x08–0x11). It can't be enlarged: the aPLib section starts
right after (0x12) at an offset the device's decompressor assumes fixed → moving it = brick. That's
why `"MAXOLYDIAN 1.40C"` (16) doesn't fit; the cap is 10 → `"MAXOLYDIAN"` was used.

**Implementation**: extended `set_version()` of `vendor/elektron-firmware-tool/main.c` so that in
ELEK the editable field starts at `0x08` (previously `ELEK_VERSION_OFF=0x0D`, only 5 chars) → now it writes
the full 10 chars of the display area. Build:
```
elektron-firmware-tool -i downloads/extracted/OCTATRACK_OS1.40C.syx \
    -c 3 out/mainos_combined.bin -V "MAXOLYDIAN" -o out/OCTATRACK_OS1.40C_LAZYPART_GUI_MAXO.syx
```
**Verified**: header `0x08–0x11` = `6d 61 78 6f 6c 79 64 69 61 6e` = "MAXOLYDIAN"; `0178` intact;
`0x12` = `00` (NUL); checksums ok; round-trip of the MAIN OS section byte-identical to `mainos_combined.bin`
(audio+GUI patches intact). 100% cosmetic change; the version code `0178` is not touched.

### Boot splash font = UPPERCASE only (verified on hardware)

When flashing with the field in lowercase (`maxolydian`) the boot splash showed **garbage glyphs**
below the Elektron logo (user's photo) — but the text WAS being drawn (confirms the bootloader
reads the version field from flash `0x4008` and paints it on the splash). The splash font (which lives in the
bootloader, outside our container) **has no glyphs for lowercase**: it's an embedded font of
~6 bits (range `0x20`–`0x5F`: space, symbols, digits `0-9`, `A-Z`), that's why `1.40C` (digits + `.` +
uppercase `C`) always looked fine. The lowercase bytes (`0x61`+) fall outside → garbage.

**Fix**: use UPPERCASE. Rebuild with `-V "MAXOLYDIAN"`. Verified header:
`0x08..0x11` = `4d 41 58 4f 4c 59 44 49 41 4e` = "MAXOLYDIAN"; `0178` intact; `0x12`=`00`; checksums ok;
round-trip MAIN OS byte-identical. **Rule for future splash brandings: only `A-Z 0-9 . space`
(and ASCII symbols 0x20-0x40), NO lowercase, max 10 chars.**

---

## SCENES subsystem (crossfader) — RE for "sticky scenes on Part change"

**User goal**: when switching to a pattern with a different Part, keep the currently selected
A/B scenes instead of loading those of the destination Part.

**Data structure (verified by decompilation):**
- Per-pattern scene selection: byte at `_DAT_46c82456 + pattern*0x18b2 + 0x8ed90` (scene A) and
  `+ 0x8ed91` (scene B). `_DAT_46c82456` = project database base; pattern stride = `0x18b2`.
- Each scene's data (p-locked params): `base + pattern*0x18b2 + scene*0x100 + 0x8f3e2`.
- RAM mirror of the selection: `0x100a4edf + pattern*0x18b2` (written by the writer alongside the project).
- Slot-in-edit flag (A vs B): `_DAT_460d169c` (1 = A).

**Key readers/writers:**
- `FUN_4003f1b4` = **crossfader/scene morph** (live, control-rate): uses the **ACTIVE** pattern
  `DAT_80000003` → reads the A/B selection (`0x8ed90/91`) → reads both scene blocks (`scene*0x100+0x8f3e2`)
  and interpolates them by the crossfader position. **This is where the scenes "change"**: on switching
  pattern, `DAT_80000003` changes and the new pattern's selection is read.
- `FUN_40052944` = scene B selection writer (manual assignment): writes `0x8ed91` in project +
  mirror `0x100a4edf` + display (`FUN_40033e3c(8,0x38,...)`). There's an analog for scene A (0x8ed90/0x37).
- Scene edit menu (COPY/PASTE/CLEAR/UNDO): `FUN_40062da0/e84/f60`, read the selection via `DAT_100b14cf`
  (DISPLAYED pattern) — GUI path.
- The **pattern-change commit** (write of `DAT_80000003`) lives in the giant event loop
  `FUN_40061a94` (store register-indirect, not a direct ref → hard to hook there).

**Part/pattern model**: the selection is stored per-pattern (0x18b2), but in practice reflects the
Part (activating a pattern loads its Part's data into the active block; patterns of the same Part
share the selection). That's why the "jump" is perceived when changing Part.

**Patch feasibility: YES.** Recommended approach (copy-on-change / "sticky"): on Part change,
copy the outgoing A/B selection to the incoming pattern's block (`0x8ed90/91` + mirror `0x100a4edf`),
so that the crossfader reads the previous scenes. Natural hook: the same Part-change path the
lazy-part patch already uses (`FUN_40009094`), carrying a shadow of the last applied pattern to know
origin→destination. **Tradeoff**: it modifies the destination pattern/Part's saved scene selection (working
copy); if the project is saved, it persists. Semantic decision pending with the user.

### Sticky scenes v1 — SUPERSEDED (`tools/patch_scene.s`, buggy; see "Sticky scenes v2" below)

`scene_stub` inserted in the detour chain of `FUN_40009094` (part-apply):
`0x40009094 -> scene_stub(0x400d6700) -> save_stub(0x400d64e0) -> ... -> jmp 0x4000909c`.
Copies the A/B selection of the outgoing pattern (carried in `LAST_PAT`@`0x80006c60`, with `INIT_FLAG`@
`0x80006c61`) to the incoming pattern's block (arg2), at `0x8ed90/91`, before the crossfader reads.
Details: base=`*(0x46c82456)`; `dst = base + arg2*0x18b2 + 0x8ed90/91`. Uses muls.l and movem in
ColdFire style (`lea -0x18,sp; movem.l ...,(sp)` — ColdFire does NOT support `movem -(An)`).
- Validated in emulator (`tools/emu_scene.py`): correct copy, respects init/same-pattern, no
  wild-writes, regs restored, SP balanced, chains to save_stub. The audio+GUI patches intact
  (scene_stub restores all state before the jmp; save_stub byte-unaltered).
- Diff vs mainos_combined: only 2 regions (detour `0x40009098` 2B + stub `0x400d6700` 134B).
- Final firmware: `out/OCTATRACK_OS1.40C_FULL_MAXO.syx` (audio+GUI+scene+MAXOLYDIAN), checksums ok,
  round-trip MAIN OS byte-identical to `out/mainos_scene.bin`.
- **Uncertainty (soft, no brick risk)**: efficacy depends on `FUN_40009094` running on the
  Part change (confirmed: the lazy-part patch that uses it ALREADY works on hw) and on the per-pattern
  selection not being reloaded after the copy (evidence: writer/readers are per-pattern → no reload).
  Worst case if the assumption fails: the scenes jump the same (harms nothing; the stub only writes 2 bytes
  of valid index to an in-range address).

### Correction: there is NO separate RAM mirror — logs `out/ghidra_mirror{,2}.log`, `out/ghidra_partapply.log`

An earlier note claimed the scene LEDs might read a mirror at `0x100a4ede/edf` that the patch
leaves stale. **That was wrong.** `0x100a4ede/edf` and `*(0x46c82456) + 0x8ed90/91` are the
**same two bytes**: the project working copy has a compile-time-constant base `0x1001614e`
(84 code sites use it directly), and part of the firmware reaches the same fields through the
pointer at `0x46c82456` instead. The offsets line up exactly:

| via pointer | absolute | field |
|---|---|---|
| `+0x8ed80` | `0x100a4ece` | (neighbouring field) |
| `+0x8ed88` | `0x100a4ed6` | (neighbouring field) |
| `+0x8ed90/91` | `0x100a4ede/edf` | **scene A / B selection** |
| `+0x8eda2` | `0x100a4ef0` | (neighbouring field) |

So writing "the mirror" would be a no-op — `scene_stub` already writes what every reader reads.
All four functions touching `0x100a4ede/edf` (`FUN_4000e79c`, `FUN_4004a100`, `FUN_40052944`,
`FUN_400a0734`) only **store** to it; none loads from it.

### Two-level scene storage (this is the real model)

- **Live working copy** — `0x1001614e + pattern*0x18b2 + 0x8ed90/91`. What the crossfader
  `FUN_4003f1b4` reads. **This is what `scene_stub` writes.**
- **Per-Part saved copy** — `0x40170f70 + part*0x9b340 + pattern*0x18b2` (+1 for B).
  Part stride `0x9b340`. `FUN_4004a100`/`FUN_400a0734` write both copies, but the live one only
  when the entry's Part/pattern are the active ones (`DAT_80000002`/`DAT_80000003`).

**`FUN_40009094` (our detour host) reads the per-Part copy, not the live one**
(`cVar2 = *(char *)(iVar13 + 0x40170f70)`, then indexes scene data by it). It does **not** write
`0x8ed90/91`, so it does not overwrite `scene_stub` — but anything it drives uses the destination
Part's selection regardless of the patch. Signature confirmed: `FUN_40009094(part, pattern)`,
matching the stub's `arg2 = pattern` read at `0x20(%sp)`.

**Open**: which copy the scene LEDs/display read is still unidentified. If they read the per-Part
copy, full stickiness needs writing `0x40170f70 + part*0x9b340 + pattern*0x18b2` too — more
invasive, since that block is what gets persisted on project save.

**Resolved** (see below): every consumer reads the live copy. Nothing extra is needed.

## Sticky scenes v2 — `tools/patch_scene2.s` (v1 was wrong)

**v1 was broken on hardware**: assigning a scene by hand after a transition got clobbered.

Root cause: v1 hooked `FUN_40009094` and treated every invocation as a pattern change,
copying the outgoing pattern's selection over the incoming one. That premise is false.
`FUN_40009094` is `apply_part(part, pattern)`, called from **10 sites**, and:

- 7 push the active pattern `(0x80000003)` as the argument;
- 3 push an **arbitrary pattern from a register** (`FUN_4002b470` D3, `FUN_4002b654` D7,
  `FUN_4004a8a4` D2), each preceded by `0x9b332 = 1` and `0x100f8598 = 1` — the
  "a parameter was edited, re-apply the Part" idiom;
- `FUN_40052944` (manual scene assign) sets those same two flags, so **assigning a scene
  reaches apply_part too**, and v1 fired as a side effect of the user's own action.

v2 ignores the arguments entirely and polls the real active pattern:

```
enforce():
    p = *(0x80000003)
    if !VALID:          STICKY = live[p]              ; first run
    elif p != LAST_P:   live[p] = STICKY ; HOLD = 4   ; pattern changed -> impose
    elif HOLD != 0:     live[p] = STICKY ; HOLD--     ; anti-loader window
    else:               STICKY = live[p]              ; user assigned -> adopt
    LAST_P = p ; VALID = 0xA5
```

A pattern change and a manual assignment are distinguishable because the index changes in
one and not the other. `HOLD` is a heuristic guarding against the Part loaders
(`FUN_4004a100`/`FUN_400a0734`) writing the destination's saved selection right after the
change; it is the one tunable parameter. RAM `0x80006c60`..`0x64`. `enforce()` is
idempotent and hangs off two hosts: `FUN_40009094` and `FUN_4003f1b4` (crossfader entry,
prologue `lea -0x3c(SP),SP ; movem.l d2-d7/a2-a6,(SP)` displaced into the stub).
Validated 9/9 in `tools/emu_scene2.py`. **Confirmed working on hardware.**

## Display of the scene selection — no GUI patch needed

All three consumers read the SAME live bytes, indexed by the ACTIVE pattern:

| consumer | what it drives |
|---|---|
| `FUN_4003f1b4` | the crossfader morph (audio) |
| `FUN_40061a94` @`0x40062c32` | publishes UI elements `0x37`/`0x38` via `FUN_40033e3c` |
| `FUN_4004d640` | the scene numbers on the LCD (`+1`, so 0-15 shows as 1-16) |

So correcting the data corrects audio and display together. `FUN_4004d5b8` (the scene trig
comparator) is the exception: it uses `DAT_100b14cf`, the DISPLAYED pattern.

`FUN_40002df4(part, pattern, scene, slot)` is NOT an LED setter — it stages 0x20 bytes of
scene parameter data per track from `0x401715c2 + part*0x9b340 + ...` into the live scene
buffer at `0x80000ed4 + track*0x40`, gated on the track's part/pattern matching the active
ones. **That gate is what makes a track in transition keep the source Part's scene params
— the real definition of "dirty".**

## LED subsystem — logs `out/ghidra_led{,map,drv,buf,enc}.log`

- State buffer `0x460ba98c`, **2 bits per LED** — `FUN_400132c4(id, state)` masks with
  `3 << (id & 7)`, and ids advance by 2. Bi-colour: one bit per die, so
  `00` off / `01` red / `10` green / `11` amber.
- Brightness is separate and 4-bit: `FUN_400135b0(id, level)`, one call per die.
- `FUN_400131a0(id)` / `FUN_400131c8(id)` set/clear a single bit (the widely-used pair).
- **Track LEDs**: `FUN_40083eb0` loops 8 tracks over an id table at `0x400a9670`, computes
  a colour state 0-3, and passes a **hardcoded `0xF` brightness** — so brightness is a free
  dimension there. Loop tail registers: `D5` = track index, `D2` = id, `D3` = id+1,
  `A3` = `FUN_400135b0`. It does NOT pop per call; it cleans `0x20` once at `0x40083fc6`,
  so a stub must push exactly the same bytes and clean nothing.
- **Trig LEDs**: `FUN_40034a44` loops 16 trigs building two local arrays, then emits them:
  colour at `SP+0xA0`, brightness at `SP+0x20`, 4 B per entry, 2 entries per trig
  (confirmed by the prologue `lea (-0x120,SP),SP ; lea (0xa0,SP),A6 ; lea (0x20,SP),A2`).
  Stock combinations: `(0,0)` empty, `(0,1)` has content, `(1,0)` selected scene.
  **`(1,1)` is never used** — that is the free slot the dirty indicator takes.

Dirty indicators: `tools/patch_led.s` (track LED dimmed to `0x5`, detour `0x40083fb4`) and
`tools/patch_trig.s` (selected scene trig amber, detour `0x40034b5e` — conveniently a
6-byte `lea`, exactly one instruction). Both use `per_track_part[track] != DAT_80000002`.
Validated 4/4 and 5/5 in `tools/emu_led.py` / `tools/emu_trig.py`.

## Hardware crash and fix — the GUI patch was not reentrant

**Symptom**: `EXCEPTION  VEC:0B  SR:2000  ADDR:000C94CA`. Vector 11 is the unimplemented
F-line trap, and the address contains no defined code — i.e. the CPU jumped to garbage.
Repro: play B1 P1, switch to B2 P1, hold `[SCENE B]` and turn a track's amp volume.

**Cause**: `tools/patch_gui.s` installed a return-hook using ONE global slot (`SAVE_RET`
`0x80006c34`) and ONE flag (`DID_OVERRIDE` `0x80006c33`), and `cleanup` jumped through
`SAVE_RET` unconditionally:

1. outer entry: `SAVE_RET = retOuter`, `(sp) = cleanup`, `DID_OVERRIDE = 1`
2. nested entry: `SAVE_RET = retInner` — **clobbers retOuter**
3. inner returns → cleanup restores, clears the flag, jumps `retInner` (fine)
4. outer returns → cleanup sees the flag already 0, skips the restore, and jumps
   `SAVE_RET` = `retInner`, **an already-consumed address** → wild jump → F-line

A second defect rode along: in step 2 the nested entry saved the *already overridden*
globals as if they were the originals, so the restore left them corrupted.

**Fix** (`tools/patch_gui2.s`): guard at the top of `setup` — if `DID_OVERRIDE` is already
set, a nested entry neither overrides nor hooks the return, and runs like stock. One slot
is then sufficient because only one override can be live. The guard branch must be `bne.w`;
`.b` is out of range (the jump crosses the whole 130-byte override block). Validated 4/4 in
`tools/emu_gui2.py`, including the nested case.

**Lesson for future patches here**: any hook that stores per-call state in a fixed global
must either guard against reentry or keep a stack. The emulator harnesses only exercised
single calls, which is why this survived validation and only surfaced on hardware.

## BANK/PTN selection: removing the 4-second countdown — logs `out/ghidra_{bankptn,timeout,countdown,timerstruct,timerrefs}.log`

Manual (Banks and Patterns): pressing `[BANK]` or `[PTN]` opens a SELECT window that
expires in four seconds; `[NO]` exits. On the unit the countdown is drawn as four boxes
that empty once per second.

**State machine** — `FUN_4005a044(_, event)` is the PTN key handler, `event` 1 = press,
0 = release:

- `_DAT_460d1742`: 0 normal, 1 key held, 2 SELECT window open
- `_DAT_460d1ab2`: set on press to `(mode != 2)` — this is what makes **press-again-to-exit
  already work in stock firmware** on both keys (confirmed on hardware). Only the timeout
  and the key LED were missing from what the user wanted.

**The timed window** — `FUN_40059f8c(text, ticks, enable, on_timeout)`:

```
_DAT_460d1e5c = window handle      _DAT_460d1e60 = on_timeout
_DAT_460d1e50 = ticks >> 2         _DAT_460d1e58 = reload
_DAT_460d1e54 = 4                  _DAT_460d1e4c = enable
```

`0xf0 >> 2 = 60` ticks per box, `_DAT_460d1e54 = 4` boxes → the four seconds.
`FUN_40056ab8` is the tick; it gates on `tst.l (0x460d1e4c)` and on expiry calls
`FUN_40056a70` (close + callback). Callers pass `enable`: PTN `1`, SELECT BANK `0`,
`BANK %c: SELECT PTN` `0` — the BANK path enables it afterwards via `FUN_40031200`
(`moveq #1,D0 ; move.l D0,(0x460d1e4c)`), whose only caller is `FUN_4007b26c`.

**Patch (2 bytes)**: `FUN_40056ab8` → `rts` (`4ab9…` → `4e75`). Safe because the whole
timer is exclusive to bank/pattern selection — `FUN_40059f8c` has exactly three callers,
all of them SELECT windows, and the tick has one caller. Closing on a trig press goes
through `FUN_40056b00` from `FUN_4007b2fc`, independent of the countdown. The four boxes
stay full and act as a mode indicator. **Confirmed on hardware.**

**Methodology note**: a scalar-operand sweep MISSES absolute-long operands — Ghidra models
those as references. That is why an early sweep found neither `tst.l (0x460d1e4c).l` nor the
writers of `_DAT_46c82456`. Use `ReferenceManager.getReferencesTo` for globals; keep the
scalar sweep only for immediates and struct offsets.

## PERSONALIZE menu structure — logs `out/ghidra_{personalize,flags,settingsblock,settertbl}.log`

OS 1.40C has **16 items**, not the 12 in the 1.40A manual (added: `SHORT SAMPLE NAME`,
`RECORD QUICK MODE`, `EXT LEN GRID-REC`, `LED BRIGHTNESS`). Three parallel arrays:

| array | address | entries |
|---|---|---|
| labels | `0x400b2a34` | 16 |
| value getters | `0x400b2a74` | 16 |
| `LED BRIGHTNESS` values (`LOW`/`MID`/`MAX`) | `0x400b2ab4` | 3 |
| setters | `0x400b2ac0` | 16 |

Contiguous, `0x400b2a34`–`0x400b2aff`, immediately followed by unrelated FILE MANAGER data
— so **they cannot be extended in place**.

- `FUN_40068e00(win)` renders: label `[i]`, then calls getter `[i]` for the right-hand
  column. Count `_DAT_460e4678`, cursor `_DAT_460e4670`, scroll `_DAT_460e4668`.
- `FUN_40068fd0(key)` handles input: calls setter `[cursor]`. Setters take `(delta, flag)`
  on the stack, add to the current value and clamp.
- Each setting is its **own 32-bit word**, not a bit in a shared mask:
  `MUTE FOCUSES TRK` `0x80000090`, `QUANTIZE LIVE REC` `0x800000ac`,
  `DIS. PAGE AUTOCOPY` `0x800000c0`, `EXT LEN GRID-REC` `0x800000cc`,
  `LED BRIGHTNESS` `0x800000d0`.
- **Free words inside the block**: `0x800000a8`, `0x800000d4`, `0x800000d8`, `0x800000dc`
  (zero references anywhere).
- No settings word is referenced by the project serializer `FUN_40086d7a`, yet the settings
  survive power cycles → the block lives in **battery-backed RAM** (consistent with the
  Startup Menu's EMPTY RESET, which the manual describes as clearing settings). A new flag
  in a free word should therefore persist with no file-format change. *Inferred, not yet
  verified on hardware.*

To add items: relocate all three arrays to the cave with more entries, repoint the
references, write a getter/setter pair per item, and raise the count.

**Item count** — `FUN_40068fa8` is the list init:

```asm
tstl 0x46c8d18c ; sne d0 ; mvsb d0,d0 ; moveq #15,d1 ; subl d0,d1   -> 15 or 16
movel d1,-(sp) ; pea 5 ; pea 0x460e4668 ; jsr 0x4007ec60            -> list_init(list, rows, count)
```

Raising the immediate to 17 gives 17 or 18 and **preserves the conditionality** of the
16th item (which depends on `0x46c8d18c`). One byte.

**Careful**: the getter and setter arrays are reached by `lea`, but the label array is
loaded as an **immediate into D5** (`move.l #0x400b2a34,%d5` at `0x40068efc`). A sweep
for `lea` alone misses it.

Implemented in `tools/patch_menu.s` + `tools/patch_flags.s`: `NO BANK/PTN TIMER`
(`0x800000d4`) and `LAZY TRANSITIONS` (`0x800000d8`, one switch for lazy part apply +
GUI-in-transition + sticky scenes + both dirty indicators). Both default to unchecked, so
an unconfigured unit behaves exactly like stock — every patch got an early-out gate.
Glyphs: `0x400b5e90` checked, `0x400b5e8e` unchecked. Setters are `(flag + delta) & 1`,
which turns both [YES] and the arrows into a toggle.

## Composition testing — `tools/emu_image.py`

**V6 froze on the logo screen while all 25 per-stub emulator tests were green.** Adding a
gate to `save_stub` shifted `restore_stub` by 10 bytes, and the exit detour at
`0x40009664` kept jumping to the old address, landing inside `save_stub`'s tail.
A second stale detour (`xf_stub`, shifted 8 bytes by the `scene_stub` gate) was found by
the same check before it could cause the next crash.

The per-stub harnesses cannot catch this: each loads a freshly assembled `.bin` at a fixed
address and tests its logic in isolation. Nothing tested whether the detours **in the
assembled image** point where the symbols ended up.

`tools/emu_image.py` runs against the real patched image and checks:

1. every detour targets **exactly a known symbol** — not merely "somewhere in the cave",
   which is what made the bug subtle: `0x400d6538` was inside the cave and inside
   `save_stub`; it just was not an entry point;
2. no target lands in the middle of another stub;
3. real execution from each detour site, flagging any PC that touches a cave byte not
   belonging to a stub (i.e. garbage);
4. no stub overlaps the next.

Verified against a deliberately re-broken image: it fails both statically and dynamically.
**Detour targets are now derived from the symbol tables at build time, never hardcoded.**

Three layers now cover a build: per-stub logic (the `emu_*.py` harnesses), image
composition (this), and reproducibility (`sysex/apply_patch.py`). Both hardware bugs in
this project — the reentrancy crash and this freeze — escaped through gaps between layers,
not through a layer.

## Feature set redefined (R2) — what was dropped and why

After instability on hardware, the spec was rewritten and the build restarted from stock:

- **GUI-in-transition: removed.** It did the *opposite* of the new spec — it wrote encoder
  edits into the SOURCE Part and deliberately kept the track dirty, whereas an encoder move
  must now *end* the transition. It was also the patch that crashed (`VEC:0B`, and a second
  `VEC:04` with `ADDR:00000000` consistent with its `cleanup` jumping through a null
  `SAVE_RET`) and the only one that overrode shared globals.
- **Amber scene-trig indicator: removed.** It scanned all 8 tracks and OR-ed the result,
  forcing a per-track property into one global light — so it latched on permanently and
  conveyed nothing. The scan was the symptom, not the cause: the track LED needs no scan
  because the painter already iterates tracks and each pass checks its own.
- **Track LED: fixed.** It was missing the `&& sounding` half of the condition that the
  original design (`HANDOFF.md`) specified — an idle track with a stale `per_track_part`
  read as dirty forever. Voice-active byte at `0x800049d8 + track*0xa8`.
- **New — encoder ends the transition** (`tools/patch_enc.s`, same hook site the removed GUI
  patch used). The destination Part's parameters no longer exist by then: `apply_part`
  computed them and `restore_stub` overwrote them, which is exactly how the track stays
  protected. So `restore_stub` now snapshots them on the way past — at that instant the
  voice buffer still holds the destination values — into `0x80006e00 + track*0x40`, and
  `enc_stub` copies them back when an encoder moves on a dirty track.

`tools/build.py` replaces the ad-hoc packing: it starts from the stock image, derives every
detour from the linker symbol tables, and aborts if any assumption about the stock bytes
fails.

## CF-card flashing — `tools/make_bin.py`

MIDI SysEx takes minutes at 31250 baud. The manual's §8.5.2 OS UPGRADE path reads a `.bin`
from the root of the CF card instead. Decoding the official `.bin` showed the ELUP payload
is simply:

    [4-byte BE length][ELEK container]

— exactly the container `elektron-firmware-tool` already builds, so no new format work was
needed, only the forward direction of the obfuscation `tools/bin_decode.py` already
reverses. `rot16` and `bswap` are involutions, so inverting is direct:

    encode: x = k ^ mixer ^ p ;  c = rot16(x) ^ XOR_A   (variant 0, k & 0x800000 == 0)
                                 c = bswap(x) ^ XOR_B   (variant 1)

with the feedback `k` being the previous **cipher** word.

Getting the patched container out: the `.syx` is 7460 SysEx messages each with its own
framing, so rather than reverse that transport, `EFT_EMIT_CONTAINER=<path>` was added to the
vendored tool (5 lines) to dump the container it already builds internally.

**Validation**: `make_bin.py` regenerates Elektron's official `.bin` **byte-for-byte** from
that file's own container. Nothing about the format is inferred. Confirmed working on
hardware.

One caveat: a container whose size is not a multiple of 4 needs the payload padded to a word
boundary (ours needed 2 bytes; the official happened to align). The device reads the declared
length and ignores the tail. `FUN_4007f748` validates the checksum and returns an error code
*before* touching flash, so a malformed `.bin` is rejected rather than half-applied.

## Lazy transitions — final shape (R10) and the LED saga

The shipped feature: on a pattern change to a different Part, sounding tracks keep the
previous Part's params (no jump), the track LED dims, and the track adopts the destination
Part on any modification — sequencer trig, manual trig, or an encoder move.

- **Audio**: `patch.s` (save/restore + destination snapshot) + `patch_enc.s`. The encoder
  path was the last piece of point (c). The destination params no longer exist when the
  encoder moves — `apply_part` computed them and `restore_stub` overwrote them — so
  `restore_stub` snapshots them into `DEST_SNAP` (0x80006e00, at the instant the voice
  buffer still holds them) and `enc_apply` copies them back. **There are FIVE encoder
  editors** (`0x40052e98`, `0x40052ae8`, `0x40053498`, `0x40053a68`, `0x4005435c`), same
  shape but different prologues; hooking only one made the feature look dead. All five are
  trampolined in `patch_enc.s`.
- **LED**: `patch_led.s`, eight instructions — `per_track_part[track] != active Part` →
  dim (`0xF`→`0x5`). That is the whole indicator, and it was ALREADY WORKING before this
  round; the user said so explicitly.

### The LED mistake, recorded so it isn't repeated

I "fixed" a working indicator and spent many hardware flashes making it worse. Root causes,
each a thing assumed rather than verified:

1. The "always dirty" bug belonged to the **amber scene-trig** indicator (removed), which
   OR-ed all 8 tracks into one light. `led_stub` never had it — the painter iterates tracks
   and each pass checks its own. I conflated the two and edited the healthy one.
2. Added a `&& sounding` test reading `0x800049d8` — a byte that **pulses** — so the LED
   flickered. The audio patch reads it once per pattern change; in a per-frame painter it is
   not a boolean.
3. Added a patch-owned dirty mask at `0x80006c70` — which the **firmware writes**, proven by
   the flicker returning exactly when the mask was present (R5/R6/R7/R9) and gone when it was
   removed (R8/R10). "Free RAM" was never verified; the earlier `GhidraRamFree` scan misses
   computed/indexed writes. Proven-stable patch RAM ends around `0x80006c64` (scene block);
   `0x80006c70` is past it.

**Rule**: when something worked and now doesn't, the first suspect is what I changed, not the
firmware. And never trust "this address looks free" — the only RAM proven safe is what an
already-working patch reads back correctly.

### Definition (R10): dim == "not yet re-trigged since the Part change"

After the LED saga, the feature was redefined around what the code naturally does rather than
chasing an encoder-clears-the-dim behaviour. The dim means: a **sounding** track that changed
Part and has **not been re-trigged**. A trig settles `per_track_part[track] = active` durably
and clears it; an encoder move does not (it applies the destination sound via `DEST_SNAP`, but
`per_track_part` is firmware-owned and re-asserted to source until a genuine trig). Under this
definition the encoder-not-clearing-the-dim is correct by design: the LED and the encoder are
two orthogonal signals — "re-trigged yet?" vs "apply the destination sound now". No further RAM
hunt or `per_track_part` lifecycle mapping is needed. (Kept for reference: `0x100f8598`, the
"param edited, re-apply" flag, has many writers and no direct reader — polled via a computed
address.)

---

## ARP key-scale (F knob) — RE map for adding scales (Greek modes / blues)

Educational investigation into extending the arpeggiator's "key scale" (ARPEGGIATOR
SETUP, F knob) beyond the stock major/minor. No binary was changed. All addresses
verified against `out/raw/section_3_MAIN_OS.bin` (file_offset = vaddr − 0x40000400);
analysis ran in a fresh fully-analyzed project at `out/ghidra_arp` (scripts:
`tools/GhidraArp*.java`).

### Mechanism (manual §15.4.4)
The F knob forces arpeggiated notes *and* the per-step note offsets onto a key scale;
it "affects the note trigs of the track even if the MODE setting is OFF" → the actual
pitch quantizer sits on the shared MIDI note-trig output path, not inside the arp loop.

### Selector — fully mapped and patchable
- **Per-track storage**: arp struct byte at `_DAT_46c82456 + pattern*0x18b2 + track*0x24
  + 0x8f273` (offset +3; +0 is the arp LEN byte). In the compact load struct
  (`FUN_400260d0`) it is field **+0x16**.
- **Encoder handler**: `FUN_4007a2ec`, branch `param==5`. Reads the byte
  (`mvz.b (1,A0),D0` @0x4007a428), min `0x400d4066`=0, count `0x400d4096`=**0x19 (25)**,
  `subq #1` → max 24, clamp, write back (`0x4007a466`).
- **Enum**: 25 states = `0` OFF, `1..24` = 12 roots × {major,minor}, encoded as one
  value: `root=(v-1)>>1`, `quality=(v-1)&1` (0=MAJ, 1=MIN). No hidden scales.
- **Label render** (`FUN_4003b790`, descriptor @0x400d40c6): draws root-note name +
  suffix from two parallel 25-entry pointer tables — roots @`0x400a7e54`, suffixes
  @`0x400a7eb8` (`MAJ`=0x400b5750, `MIN`=0x400b4419). Guard `moveq #0x18` @0x4003b7ca.
  The scale is TEXT only (root + maj/min); no keyboard is drawn — so new scales are
  cheap visually (just add suffix strings + widen the tables).

### To add qualities (major,minor → +dorian,phrygian,lydian,mixolydian,locrian,blues)
Encoding is root×quality, so 8 qualities ⇒ 1 + 12×8 = **97 states**. Selector/label side:
1. count datum `0x400d4096`: `19`→`61` (25→97).
2. formatter guard `moveq #0x18` @0x4003b7ca (`70 18`→`70 60`).
3. table-copy sizes `pea (0x64).w` @0x4003b7a0 / @0x4003b7b6 (100→388=`0x184`), enlarge
   `FUN_4003b790` stack frame accordingly.
4. rebuild both label tables to 97 entries (root ×8 per pitch class; suffix cycling
   8 abbreviations e.g. DOR/PHR/LYD/MIX/LOC/BLU) — packed literal pool, cannot grow in
   place, relocate + repoint the two `pea` bases (@0x4003b7a4→0x400a7eb8,
   @0x4003b7ba→0x400a7e54).

### Runtime quantizer — LOCATED (`FUN_4009f794`), all bytes verified
The MIDI note-trig emitter for all 8 MIDI tracks (called from `FUN_400a1608`), runs for
every track's note trigs regardless of arp MODE. Reads the scale byte from a **third RAM
mirror** at `0x46c76df1 + track*0x44` (not the project struct +0x8f273; `FUN_400260d0`
writes all three mirrors on load). Algorithm (verified):

```
scale = mirror[track][0x31]                 ; 0x4009fad2  move.b (0x31,A0),D0
if (scale == 0) noQuantize                   ; OFF
root = (scale-1) >> 1                         ; asr.l #1     (root 0..11)
K    = (scale & 1) ? 0x0C : 0x15              ; btst #0 @0x4009fae4; odd=MAJOR(12), even=MINOR(21)
local_44 = K - root
idx  = (note + local_44) % 12                 ; 0x4009fb6a moveq #0xC / divsl.l
note = note + snaptable[idx]                  ; 0x4009fb74 lea 0x400d80a0 ; add.l (A0,idx*4),D0
```

**One snap table only** @`0x400d80a0` (file `0xd7ca0`), 12×int32 =
`[0,-1,0,-1,0,0,-1,0,-1,0,-1,0]` → in-scale PCs `{0,2,4,5,7,9,11}` = **MAJOR**.
Minor has no table of its own: it reuses major rotated by the relative-major offset
(K=21 vs 12; +9 mod 12). Snapping is always DOWN 1 semitone for out-of-scale tones.

Verified byte anchors: scale read `0x4009fad2` (`10280031`); quality bit `0x4009fae4`
(`08000000`); minor K `0x4009faec` (`7415`); major K `0x4009faf8` (`760c`); OFF guard
`0x4009fb58` (`4aaeffc06f22`); table lea `0x4009fb74` (`41f9400d80a0`).

### Adding scales is FEASIBLE — two tiers
- **5 Greek modes = FREE** (all rotations of major, reuse table `0x400d80a0`, new K each):
  Dorian 14, Phrygian 16, Lydian 17, Mixolydian 19, Locrian 23 (Ionian 12 / Aeolian 21
  already present). `local_44 = K - root` stays > 0 for all roots, satisfying the guard.
- **Blues** `{0,3,5,6,7,10}` is non-diatonic → needs one new 12×int32 snap table + a
  conditional table base at the lookup `lea 0x400d80a0` (`0x4009fb74`).
- **Code change (not data-only):** rewrite the decode block `0x4009fad2–0x4009fafc`
  (~46 B). With 8 qualities the split becomes `root=(v-1)%12 / quality=(v-1)/12` feeding a
  small switch that picks K (and, for blues, the alternate table). Relocate to a code cave
  + detour (same pattern as the other patches); pair it with the selector/label widening
  above (enum 25→97 at `0x400d4096`, formatter guard `0x4003b7ca`, label tables
  `0x400a7e54`/`0x400a7eb8`).

### IMPLEMENTED — 12-quality arp key scale (`tools/patch_arp.s`, emulator-validated)

Standalone build `tools/build_arp.py` → `out/mainos_arp.bin` (stock + arp only, for
isolated testing). Cave at `0x400d7000` (inside the proven-free R10 run, clear of R10's
stubs). Adds 10 qualities to the F-knob: MAJ MIN + DOR PHR LYD MIX LOC BLU PHD MEL OCT HIR.

- **Encoding**: value 0 = OFF; 1..144 = root*12 + quality (root = (v-1)/12 slow/outer,
  quality = (v-1)%12 fast/inner). Enum count datum `0x400d4096`: 25 → 145.
- **decode_cave** (detour @0x4009fad2, replaces the 46-byte local_44 block): sets
  `local_44 = 12*quality + (12-root)` (0 for OFF). The 12*quality term vanishes under the
  stock mod-12, so the existing idx computation still yields (note-root) mod 12; and the
  lookup recovers quality as (local_44-1)/12 — so NO extra stack slot / frame change is
  needed (the prologue movem saves regs at the frame bottom, so growing the frame was
  unsafe). Division by 12 done with the magic multiply (v*171)>>11 (exact for 0..143;
  avoids ColdFire divide-form uncertainty).
- **lookup_cave** (detour @0x4009fb74, replaces `lea 0x400d80a0`): idx += quality*12;
  note += UT[quality*12+idx] (signed byte); preserves D2/D3.
- **fmt_cave** (detour @0x4003b790, replaces FUN_4003b790): computes root/quality and
  draws NOTETAB[root] + QUALTAB[quality] instead of the stock 25-entry tables. Same
  callback contract (draw suffix at pos via 0x40013a08, tail-draw root at pos+5).
- **UT** unified snap table (144 signed bytes, quality-major): MAJ/MIN reproduce stock
  `0x400d80a0` exactly; 7-note scales snap down; BLU/OCT/HIR snap to nearest. A snap-up at
  note 127 wraps to a negative byte and the firmware drops it (benign, top of MIDI range).

Validation `tools/emu_arp.py` (Unicorn): decode 145/145, lookup 18432/18432 (scale×note),
MAJ parity 0 mismatches, formatter labels correct. Composes with R10 (no detour/cave
overlap) — mergeable as a new revision. NOT yet hardware-tested.

---

## Bank load from CF is ASYNC and does NOT stop audio (enables live bank paging)

Educational RE of whether a single-bank reload halts playback (for a "page in 16 banks
mid-performance" feature). Verdict: **it does not stop the sequencer/audio, and the load
runs on a dedicated background task** — the same concurrency that streams samples from CF
while playing. Scripts: `tools/GhidraBank*.java`.

### Two-tier bank storage (new structural finding)
- **Resident bank blobs**: `0x400e21e0 + bank*0x9b340` (635,200 B/bank, 16 banks ≈ 10 MB).
  Cold store in RAM, deserialized from `<proj>/bankNN.work` by FUN_4008ded0.
- **Live working copy**: patterns at `0x46c82456 + pat*0x18b2` — filled from the blob only
  when a bank becomes current (FUN_4000faf0), gated on `DAT_80000002` = playing bank.

### RELOAD BANK call chain (verified addresses)
- Menu builder FUN_40063590 → confirm handler FUN_40063bf8.
- FUN_40063bf8: FUN_400a10c8 (reset UI/MIDI scratch — NO transport stop) + FUN_40022778
  posts job `{type=0x14, mask, begin=0x40023230, done=FUN_40023bf4}` via FUN_40000c3c to
  queue @0x460d17ce (sets ColdFire soft-IRQ 0xfc04c010|=0x800 to wake consumer).
- Consumer = dedicated task **FUN_4008445c** (created FUN_40040b94, prio 1, own 0x4000
  stack), blocking-dequeues FUN_40000d00, switch on msg type:
  - type 0x14 → FUN_4008f0b0(mask): loops bits 0..15, copies `bankNN.strd → .work` via
    FUN_40016388 (buffered FS copy, no ATA spin). On done posts type 6.
  - type 6 → FUN_400905d4(mask): loops bits 0..15, opens `bankNN.work`, FUN_4008ded0
    deserializes into `0x400e21e0 + bank*0x9b340`. For NON-playing banks: RAM fill +
    FUN_4000fa98(mask,0) only. Live re-apply gated `if (DAT_80000002==bank)`
    (FUN_4000faf0/FUN_400a1030/FUN_40009094).
- End re-sync FUN_40023998 → FUN_400238a4 (re-derive voice/engine to current position;
  clock never stops). Short-circuit this when the playing bank is not in the mask.
- Blocking "WORKING PLEASE WAIT" (0x400b68b2) is used only by OS-upgrade/other paths
  (FUN_40070db8/FUN_4006e450), NOT the reload path (which uses the non-modal
  "RELOADING BANK" 0x400b3898 overlay FUN_400808bc).

### Feasibility — "page 16 banks from a sibling project, no audio stop"
~90% exists: FUN_4008f0b0/FUN_400905d4 already accept a 16-bit bank mask and loop all 16
into disjoint per-bank regions on the background task, concurrent with audio, no stop.
To build: (1) redirect the filename builder from FUN_40025230(0,0) (current project) to a
sibling project dir; (2) trigger the type-6 job with a mask excluding the playing bank;
(3) short-circuit FUN_400238a4 when the playing bank isn't in the mask.
- "PRELOAD" (0x400be7c5) is a dead string (no xref) — not a usable primitive.
- **Hard usage constraint**: sample slots (Flex/Static pool) are PROJECT-level, not bank-
  level; parts reference samples by slot. Sibling projects must share the same sample
  pool/slot assignments, or paged banks play the wrong/absent samples. Flex RAM is not
  reloaded by a bank load either → siblings should share Flex assignments.

### HARDWARE-VALIDATED: non-playing bank loads from CF without stopping audio

De-risking experiment (throwaway builds, `tools/patch_exp_bankload.s` + `tools/build_exp.py`)
confirmed on a real MKI the assumption behind the live bank-paging feature.
(All on-hardware testing in this repo is on the user's **Octatrack MKI** — the user does
not own a MKII. Every flash the user has done — Build A, the bank-paging experiments, the
Session 9 soft-mute V1–V6 — was on the MKI. The stock 1.40C image is byte-identical for
MKI/MKII; the boot-time `0x46c8d18c` probe is what adapts it, e.g. hiding the MKII-only
`LED BRIGHTNESS` PERSONALIZE item on the MKI.)

Method: hooked the reload confirm handler FUN_40063bf8 (the sole caller of the poster
FUN_40022778) to (a) skip the synchronous pre-step FUN_400a10c8 and (b) force the reload
mask to a NON-playing bank `(current+1)&15`; plus NOP the end-of-load re-sync call
`jsr FUN_400238a4` at 0x400239a2 (`4ebaff00` → `4e71 4e71`).

Findings, in order (each isolated one variable):
- v1 (mask hook only, pre-step still ran): **audio cut at the instant of confirm** →
  the cut is FUN_400a10c8 (per-track note/voice scratch reset), which runs synchronously
  on confirm, NOT the async load. (Also fixed a self-inflicted VEC:04: a 6-byte detour at
  0x40022778 spilled 2 bytes into the following `lea`; resume must replicate the displaced
  `lea` and land at 0x40022782.)
- v2 (skip pre-step, non-current bank, re-sync kept): immediate cut gone; **audio cut a few
  steps AFTER confirm** → the delayed cut is the end-of-load re-sync FUN_400238a4.
- v3 (skip pre-step + skip re-sync + non-current bank): **audio kept playing through the
  entire load, no cut.** ✓

Conclusion: the async loader task filling a non-playing bank's disjoint RAM region
(0x400e21e0 + bank*0x9b340) does not disturb playback. The only two things that stop audio
are the confirm-menu pre-step and the end-of-load re-sync — both avoidable when the loaded
bank(s) exclude the playing bank. The live bank-paging feature is therefore viable; the
remaining work is plumbing (sibling-project detection, PAGE-key state machine, YES/NO popup,
redirect the load path to the sibling project dir) + the sample-pool-sharing usage constraint.

### S1 HARDWARE-VALIDATED: redirected sibling bank load, no audio stop

Bank paging Stage 1 (tools/patch_bankpage_s1.s, tools/build_bankpage_s1.py) confirmed on the
MKI. Three detours over R11: (1) gate FUN_40025230 @0x40025244 — global g_redirect (char*)
overrides the projname==0 default (0x100f8378) when set; (2) trigger at FUN_40063bf8 @0x40063bfe
— skip pre-step FUN_400a10c8, sprintf("%s_2", 0x100f8378) into a cave buffer, set g_redirect,
mask = 0xffff & ~(1<<curbank) (0x100b14ce), tail-post via FUN_40022778; (3) done at FUN_40023998
@0x400239a2 — clr.l g_redirect + skip re-sync (replicate displaced `pea (0x1).w`, resume 0x400239aa).
The RELOAD gesture loaded the sibling "<name>_2" project's 15 non-playing banks into RAM with the
sequencer running and NO audio stop; a paged bank then played the sibling's patterns. Confirms the
FUN_40025230 redirect gate + the masked multi-bank load are the correct, audio-safe mechanism.
g_redirect/sib_name live in the code cave (writable SDRAM) — worked fine on hardware.

### S3/S3b: PAGE-key bank paging UX (R12) — cycling emulator-validated

Bank paging integrated into build.py as R12 (tools/patch_bankpage.s). Three detours over the
R11 image, cave at 0x400d7400:
- **page_cave** ← FUN_4004ffc4 @entry ([PAGE] key, keycode 0x1b). Gate: edge==1 (press) AND
  in SELECT BANK (`_DAT_460d1e5c!=0 && _DAT_460d1e60==0x4007b408`) AND no popup open
  (`_DAT_460e5cd0==0`). If gated: advance g_page `(page&3)+1` (1→2→3→4→1), build the target
  name into sib_name (page 1 = base `<name>` via `sprintf("%s")`, pages 2–4 = `<name>_N` via
  `sprintf("%s_%d")`), show `FUN_4006d57c("LOAD BANKS?", 1, {&sib_name}, 3, confirm_handler)`,
  swallow the key (rts). Else fall through (replicate displaced `lea -0x10,SP`+`movem`, resume
  0x4004ffcc).
- **confirm_handler**: YES (p==0) → g_redirect=sib_name, mask=`~(1<<playingbank)`, post via
  FUN_40022778, re-enter SELECT BANK via `FUN_4007af80(0x2f,1)`. NO → nothing.
- **gate_cave** ← FUN_40025230 @0x40025244 and **done_cave** ← FUN_40023998 @0x400239a2:
  the S1 redirect + conditional-re-sync mechanism (done_cave now does the stock re-sync when
  g_redirect==0, so a normal RELOAD still re-syncs).

Validation: `tools/emu_bankpage.py` (Unicorn, runs real sprintf) — cycling + name construction
1→2→3→4→1 with correct `<name>`/`<name>_N` = ALL PASS. The core load path is the S1/S3a
mechanism (hardware-proven). **S3b's UX additions (cycling, dynamic name, PAGE hook) are
emulator/static-validated only — pending hardware test.**

Deferred (need hardware + the vtable, see DESIGN_BANKPAGE.md):
- **Existence gate**: only page when `<name>_2` exists, else stock PAGE. Recipe worked out:
  build `<name>_2`, `FUN_40025230(0, name)` → path `0x460bf112`, `FUN_40025650(path)` (nonzero
  = valid project; it checks `<path>` and `<path>/AUDIO` via the FS vtable `_DAT_46c823fa`).
  Not shipped because that vtable is uninitialized in the static image → not emulator-testable,
  and it sits on the PAGE critical path. Currently PAGE always pops the confirm in SELECT BANK
  (NO declines); a load of a missing page falls to the stock error dialog.
- **Skip-missing-page** cycling; the **page LED** (FUN_400135b0(id,0xF)); the **16th-bank
  catch-up** on bank change; the **save guard** while paged.

### Bank paging existence check — via file-open, not a dir predicate (R12, hardware-validated)

The sibling-existence check first tried FUN_40025650 (the firmware's "valid project?"
predicate). Hardware diagnostics (tools/patch_bankpage_diag.s) showed it returns 0 even for
the CURRENTLY-LOADED project (C=0) — its FS vtable `_DAT_46c823fa` does not resolve project
DIRECTORIES from this call site. Switched to a FILE-open check: existence = can we open
`<sibling>/bank01.strd` via the loader's own open helper FUN_40016864(fh, path, "r", buf,
0x10000) (D0>=0 = opened; close with FUN_4001677c). Diagnostics confirmed the sibling's
bank01.work AND bank01.strd both open (wrk=1 std=1).

Critical rule learned: **only ever open SIBLING files, never the playing project's** — a
diagnostic that opened the current project's bank01.strd and closed it made the NEXT open
fail with -2 (closing a handle the firmware holds open for playback corrupts FS state).
chk_sibling only touches `<name>_N` files, so it is safe. Validated end-to-end in
tools/emu_bankpage.py with the open/size/close vtable slots stubbed to simulate a card with
base + _2 + _3 (no _4): the PAGE cycle skips _4 and, with no `_2`, PAGE stays stock.

Open refinement (for the non-modal redesign): skip the CURRENTLY-LOADED page in the cycle
(track g_loaded, updated on YES-load, reset to base on project load) so it never offers a
useless reload of the page you are on.

### Bank paging SHELVED — the audiopool is the real blocker

Bank paging (loading sibling-project banks live) was reverse-engineered and hardware-proven to
load without stopping audio, but cancelled. Reason: it only avoids the audio stop by requiring
siblings to SHARE the sample pool. `PROJECT → CHANGE` stops playback because it reloads the
**audiopool** (samples); paging sidesteps that only by not touching samples. So paging brings in
new patterns/parts but not new sounds — too limiting, and it doesn't solve the root problem
(loading genuinely new material live). The real, unsolved frontier is a **live audiopool swap**
(new Flex/Static samples into RAM without halting the DSP/playback). Shipped firmware reverted to
R11 (arp key scales + lazy transitions). The bank-paging sources/emulators/diagnostics remain in
tools/ and DESIGN_BANKPAGE.md as documented, reusable RE.


## Session 4 — Ghidra headless decompilation now runnable in-sandbox (no macOS host needed)

Previous sessions noted `analyzeHeadless`/JDK "live only on the host Mac, not in this
environment" and treated real Ghidra decompilation as blocked until run manually there.
That's no longer true for the Linux device-bridge sandbox specifically (still true for the
plain cloud container, which has no access to `octamax/` at all): it has Java, network
egress to github.com/api.github.com/pypi.org (but NOT ports.ubuntu.com, deb.debian.org,
ftp.gnu.org, conda channels — those 403/timeout through the sandbox's proxy), gcc/g++, and
the mounted project folder — enough to build and run headless Ghidra end to end.

### Recipe (portable JDK 21 + Ghidra 12.1.2, built for linux_arm_64, no root)
The sandbox is aarch64 Linux; Ghidra 12.1.2's release zip only ships a `decompile` native
binary for `linux_x86_64`/`mac_arm_64`/`mac_x86_64`/`win_x86_64` — none run on aarch64
Linux (no qemu-user available either). Built it from Ghidra's own bundled source instead:

1. `curl` a portable Temurin JDK 21 tarball (`OpenJDK21U-jdk_aarch64_linux_hotspot_*.tar.gz`
   from the `adoptium/temurin21-binaries` GitHub releases — the Adoptium API itself
   (`api.adoptium.net`) 403s through this proxy, but GitHub releases work) and extract.
2. `curl` `ghidra_12.1.2_PUBLIC_*.zip` from the `NationalSecurityAgency/ghidra` GitHub
   releases and extract. Both downloads complete in ~15s each at ~40MB/s — no need to
   background them (see gotcha below).
3. `Ghidra/Features/Decompiler/src/decompile/cpp/Makefile` only special-cases
   `x86_64`/other(→ treated as 32-bit x86) under Linux — no aarch64 branch. Patch it:
   `ARCH_TYPE=` (empty) and `OSDIR=linux_arm_64` for `ifeq ($(ARCH),aarch64)`. All the
   bison/flex-generated `.cc` files (`grammar.cc`, `pcodeparse.cc`, `slghparse.cc`,
   `slghscan.cc`, `xml.cc`) ship pre-generated in the release zip — no bison/flex needed.
4. Don't build the `decomp_opt` target (the standalone console decompiler) — it pulls in
   `analyzesigs.cc`/`loadimage_bfd.cc` via the Makefile's `EXTRA` wildcard, which need
   `<bfd.h>` (binutils-dev headers, not installed, apt has no root here anyway). The
   target Ghidra's Java side actually shells out to is **`ghidra_opt`**
   (`CORE+DECCORE+GHIDRA` sources only, no bfd dependency). Also blank `BFDLIB=-lbfd` at
   the top of the Makefile (only `libbfd-2.38-system.so` is present, no `-lbfd`-resolvable
   dev symlink, and `ghidra_opt`'s link line doesn't need it once `EXTRA` is out of the
   picture anyway).
5. `make ghidra_opt -j6` — clean build, ~80 objects, done in one pass (~1 min). Copy the
   resulting `ghidra_opt` binary to
   `Ghidra/Features/Decompiler/os/linux_arm_64/decompile` (create that dir; it doesn't
   ship in the zip).
6. Ghidra project ownership: opening a project created by a different OS user throws
   `ghidra.util.NotOwnerException: Project is owned by <original-user>` — the sandbox's
   shell user doesn't match. Fix: `export GHIDRA_JAVA_OPTIONS="-Duser.name=<original-user>"`
   before calling `analyzeHeadless` (the script forwards this env var straight into the
   JVM's `-D` args; overrides `System.getProperty("user.name")`, which is what Ghidra's
   ownership check reads).
7. Then the documented invocation from the `GhidraResolveNN.java` header comments works
   as-is (just point `JAVA_HOME`/`PATH` at the extracted Temurin instead of the
   homebrew paths those comments assume):
   ```
   export JAVA_HOME=<path>/jdk-21.0.12.1+1
   export PATH="$JAVA_HOME/bin:$PATH"
   export GHIDRA_JAVA_OPTIONS="-Duser.name=<original-user>"
   <ghidra>/support/analyzeHeadless ~/Documents/octamax/ghidra_project octamax \
     -process "section_3_MAIN_OS.bin" -noanalysis \
     -scriptPath ~/Documents/octamax/tools -postScript GhidraResolveNN.java
   ```
   Full run (JVM start + project open + 4-function decompile) took **6.4s** — cheap enough
   to just re-run per script, no need to keep a session open.

**Gotcha — nothing backgrounded survives between sandbox shell calls.** `nohup ... &`,
`disown`, and even `setsid` all get reaped the moment the invoking call returns (tested
directly: a `setsid`-detached `sleep 25` was gone with no trace by the next call, ~20s
later). Large downloads/builds have to either complete inside one call's ~45s window, or be
genuinely resumable (`curl -C -`, `make`'s object-file caching) so a second call in the same
shell-less style continues the work rather than restarting it. In practice both the JDK
(196MB) and Ghidra (546MB) downloads and the `ghidra_opt` build finished in a single call
each, so this only matters if egress is slower next time.

Local build products (`jdk-21.0.12.1+1/`, `ghidra_12.1.2_PUBLIC/`) live in the sandbox's own
scratch home, not under `~/Documents/octamax` — they don't persist across sessions and
aren't part of this repo. Re-running the recipe above from scratch takes about 3 minutes.

### Confirmed via real decompilation: FUN_400a1eea's `a0` precondition, and where DIRECT is read

Two things session 3 part 4 could only infer from disassembly are now decompiler-confirmed:

- **`a0` is a genuine implicit input**, not something the function sets up itself: Ghidra's
  decompiler independently flags `byte *in_A0;` as a live-in register and the function's
  very first action is `*in_A0 = ~*in_A0;` (byte-complement-in-place — a flag/state toggle
  at entry, matching the raw `not.b (a0)` instruction that faulted when called cold).
  Corroborates: `FUN_400a1eea` cannot be called without first finding and replicating
  whatever caller sets up `a0`.

- **DIRECT (`TRIGQUANT`, blob-relative `+0x48fe`) has exactly one static/register-relative
  runtime reader in this function**, at `puVar45[uVar20*0x8b0 + 0x48fe]` inside a per-track
  loop (`uVar20` = track 0..7, `0x8b0` = the confirmed per-track stride), itself gated by
  `DAT_800065b6 == 0` (a sub-step counter reset every full step — this block runs once per
  step, not every tick). Full excerpt in `out/ghidra/GhidraResolve26_session4.txt` (search
  `0x48fe`), decompiled C:
  ```c
  if (DAT_800065b6 == '\0') {
    cVar11 = puVar45[uVar20 * 0x8b0 + 0x48fe];      // DIRECT/quantize-index byte, this track
    if (uVar20 * 0x8b0 == 0) {                       // TRACK 0 ONLY — different path, no skip-check
      if (puVar45[CONCAT22(cVar11 >> 7,0x8e55)] == '\0') { iVar15 = (int)(char)puVar45[0x8e53]; }
      else { iVar15 = (int)(char)puVar45[0x48f8]; }
    } else {
      if (cVar11 < 1) goto LAB_400a37f0;             // TRACKS 1-7 — DIRECT(-1) same as index-0: skip
      iVar15 = *(int *)(&DAT_400d80dc + cVar11 * 4);  // else: quantize-index -> step-length table
    }
    if (0 < iVar15) { ... quantize-window / note-reschedule logic, gated on a step counter
                          (_DAT_800065b2) modulo iVar15 ... }
  }
  LAB_400a37f0:
  ```
  Two things worth chasing next session, in order of how cheap they are to check:
  1. **Track 0 is handled asymmetrically from tracks 1-7.** Tracks 1-7 skip this whole
     block identically for DIRECT and quantize-index-0 (`cVar11 < 1`). Track 0 never takes
     that skip at all — it always evaluates a different sign-of-`cVar11` branch reading
     `puVar45[0x8e53]`/`puVar45[0x48f8]` instead. If the user's repro pattern happens to sit
     on track 0, this asymmetry is a very plausible bug site; if not, it's likely unrelated
     and the tracks-1-7 skip path is the one to trace against PLAYS_FREE.
  2. `puVar45[uVar20*0x8b0 + 0x48f9]` (blob-relative `+0x48f9`, i.e. exactly the
     unidentified "`-3(a1)`" field flagged in session 3 part 4) is read a little further
     down in the *same* `DAT_800065b6`-gated block (line ~1117 of the log) — so that
     mystery byte and DIRECT are consumed together here, not in unrelated code paths.
  This block is quantize/reschedule logic, not obviously the "manual trig key" handler
  itself — but it's the confirmed, and only, place DIRECT is read at runtime in the
  function session 3 already identified as the heaviest MIDI-track-state user, so it's the
  strongest concrete lead so far for how DIRECT actually influences sequencer behavior.

Next step: find `FUN_400a1eea`'s real caller (xref search on `0x400a1eea` — not yet done
this session) to learn the real `a0` value/type, so the emulator can call it with a valid
precondition and single-step both the track-0 special case and the tracks-1-7 skip path
with PLAYS_FREE toggled, watching for where behavior actually diverges.


## Session 4 continued (part 2) — SCALE_MODE located, and a correction to the earlier
## FUN_400a1eea reading

New test pair supplied by the user (real hardware exports, same track/pattern/trig as all
prior tests): `test1_PFD_scale` = `test1_PFD` (Plays Free ON, Direct selected) with track
scale mode ALSO set to "per track" in the OT UI. This is the user's confirmed **known-good
repro of the actual bug** (all three preconditions: Plays Free + Direct + per-track scale
mode). Diffed against `test1_PFD` the same way as every prior pair.

### Finding 4: SCALE_MODE is a new byte at blob `+0x48fd` / file `0x4964`, completing the
tight per-track MIDI-trig header

`project.work`/`.strd` differ by 3 bytes (`MIDI_CLOCK_RECEIVE`, `MIDI_TRANSPORT_RECEIVE`
0→1, `MIDI_MODE` 1→0) — incidental setup for a playable repro (feeding it live MIDI), not
scale mode; ignore for this purpose. `bank01.strd`/`.work` differ by exactly 2 content bytes
+ the checksum trailer (delta +2, consistent with 2 bytes each `+1` — Finding 2 holds for a
4th data point). Ran `tools/emu_bankdeserialize.py` on both (now installable in-sandbox:
`pip3 install --user --break-system-packages unicorn`) to get real, firmware-computed
blob-relative offsets rather than hand-mapping file offsets:

```
test1_PFD vs test1_PFD_scale — 2 differing bytes (blob-relative):
  0x48fd (18685): 0x00 -> 0x01
  0x8e55 (36437): 0x00 -> 0x01
```

`+0x48fd` sits **immediately between** the already-confirmed `PLAYS_FREE` (`+0x48fc`) and
`TRIGQUANT`/DIRECT (`+0x48fe`) — resolves the "constant `0x00` spacer, role unknown" byte
flagged in session 3 part 1. The tight per-track/per-pattern MIDI-trig header (file-relative
in this test bank; add the usual bank/pattern offsets for the general case) is now:

```
0x4962: 0xFF          (still unidentified — unchanged across all 5 test projects now)
0x4963: PLAYS_FREE    (0/1)
0x4964: SCALE_MODE    (0/1) -- NEW, this session
0x4965: TRIGQUANT/DIRECT (-1=DIRECT, 0-16=index)
```
(blob-relative, bank 0 / pattern 0: `+0x48fc`/`+0x48fd`/`+0x48fe` respectively, same layout
just shifted, per the file↔blob correspondence established in session 3 part 3.)

### Correction to session 3 part 4 / session 4 part 1: the "track-0 special case" in
### FUN_400a1eea is NOT gated on track index — it's gated on TRIGQUANT==0, for every track

Session 4 part 1's Ghidra decompile of `FUN_400a1eea` rendered a branch as
`if (uVar20 * 0x8b0 == 0)` (read at the time as "only for track 0"). Wrote a second script
(`tools/GhidraResolve27.java`) to pull the **raw disassembly** around every instruction
referencing `0x48fc/0x48fd/0x48fe/0x8e52-0x8e55/0x48f8/0x48f9` in this function, specifically
to check that reading against real asm rather than the decompiler's algebraic rendering —
good thing, because it was wrong. The actual instructions (per-step loop, gated on
`DAT_800065b6==0` same as before):

```asm
tst.b   (DAT_800065b6).l
bne.w   LAB_400a37f0                    ; once-per-step gate, same as before
move.l  #0x8b0,D0
muls.l  D7,D0                           ; D0 = track_index(D7) * 0x8b0
lea     (0x0,A4,D0*1),A0                ; A0 = per-track pointer (A4 = this pattern's blob base)
mvs.b   (0x48fe,A0),D0                  ; D0 = sign-extended TRIGQUANT/DIRECT byte, THIS track
bne.b   LAB_400a3662                    ; if D0 != 0 (i.e. NOT quantize-index-0, incl. DIRECT=-1): skip to the DIRECT/index handling below
move.w  #-0x71ab,D0w                    ; D0==0 path (quantize-index 0): D0.w = 0x8e55 (D0's high word was already 0 from the sign-extended-0 byte above, so this is a compact way to set D0=0x00008e55)
tst.b   (0x0,A4,D0*1)                   ; test blob[A4 + 0x8e55]  <-- the newly-confirmed byte
beq.b   LAB_400a3656
mvs.b   (0x48f8,A0),D2                  ; blob[0x8e55]!=0: D2 = PER-TRACK byte at A0+0x48f8 (4 bytes before PLAYS_FREE)
bra.b   LAB_400a3672
LAB_400a3656:
move.l  #0x8e53,D0
mvs.b   (0x0,A4,D0*1),D2                ; blob[0x8e55]==0: D2 = PATTERN-level byte at blob+0x8e53
bra.b   LAB_400a3672
LAB_400a3662:
tst.l   D0
ble.w   LAB_400a37f0                    ; D0<0 (DIRECT): skip, unchanged from before
lea     (0x400d80dc).l,A1
move.l  (0x0,A1,D0*0x4),D2              ; D0 in 1..16: quantize-index -> step-length table (unchanged)
```

The branch that matters is `bne.b LAB_400a3662` on **the TRIGQUANT byte itself being
nonzero**, not on track index — `D0` had briefly held `track_index*0x8b0` two instructions
earlier, but is fully overwritten by the `mvs.b (0x48fe,A0),D0` load before the branch. The
decompiler's `uVar20 * 0x8b0 == 0` rendering conflated these two unrelated uses of the same
register into a spurious algebraic identity. **This block runs identically for all 8 tracks**
whenever that track's TRIGQUANT byte is exactly `0` (quantize-index 0) — there is no
track-0-only special case. Retracting the "track 0 handled asymmetrically" lead from the
session 4 part 1 handoff; it doesn't hold up against raw disassembly.

What actually happens, correctly stated: when a track's TRIGQUANT is DIRECT (`-1`) OR any
nonzero quantize index (`1..16`), behavior is as already documented (DIRECT skips this
quantize-window block entirely; nonzero index looks up a step-length table). Only when
TRIGQUANT is exactly `0` does **SCALE_MODE (blob `+0x8e55`, a per-pattern-scoped byte
distinct from but correlated with the per-track `+0x48fd` byte found above) decide where
the fallback quantize-window length comes from**: the pattern-shared byte at `+0x8e53` when
SCALE_MODE is 0, or this specific track's own byte at `+0x48f8` when SCALE_MODE is nonzero —
i.e., literally "does the quantize-index-0 default come from the pattern or from this
track", which is exactly what a PER-PATTERN vs PER-TRACK scale-mode toggle should mean
semantically. Good independent confirmation that `+0x8e55` is the real "scale mode" bit
consumed at runtime, not just a coincidentally-correlated flag.

**Open question, not yet resolved**: the per-track `SCALE_MODE` byte found at `+0x48fd` this
session is used **nowhere** in `FUN_400a1eea` — a literal-target search across the whole
function (all 9 candidate offsets, `tools/GhidraResolve27.java`) found zero references to
`0x48fd`. Only the pattern-scoped `+0x8e55` copy is read here. So either `+0x48fd` is
write-only bookkeeping the UI keeps for its own display purposes, or it's read by a
still-unidentified different function — worth an image-wide literal search for `0x48fd`
next (same technique as the earlier `0x48fe` dead-end, so also check for register-relative
access the way `+0x8e55` needed raw disassembly to find, not just a literal-byte scan).

There is also a SECOND, independent read of `+0x8e55` earlier in `FUN_400a1eea`, well before
the per-step loop (`adda.l #0x8e54,A2` / `tst.b (0x1,A2)` at `0x400a1fbc`/`0x400a1fc2` — this
is pattern-load-time-shaped setup code (computes `A2 = bank_base + current_pattern*0x8ed8 +
0x8e54`, i.e. blob-relative `+0x8e54`, then tests the next byte = `+0x8e55`), gating a loop
that seeds a per-track byte array from a lookup table at `0x400aba50` when SCALE_MODE is
nonzero — same "seed a per-track array once, only when a flag is on" shape session 3 already
found for `PLAYS_FREE` seeding `0x80006508[track]` at pattern load. Not fully traced this
session; flagging the shape since it's the same pattern as a confirmed real mechanism.

Full raw disassembly context for every hit saved to
`octamax/out/ghidra/GhidraResolve27_session4.txt`.

### Updated next step
1. Find `+0x48fd`'s actual runtime reader (whole-image search, expect it needs
   register-relative reasoning like `+0x8e55` did — a plain literal-byte scan already missed
   it once for DIRECT and would likely miss it again).
2. Still outstanding from part 1: find `FUN_400a1eea`'s real caller to get a valid `a0` for
   emulation.
3. Once both land, re-run the emulator/decompiler trace with all three flags (PLAYS_FREE,
   SCALE_MODE, DIRECT) set exactly as `test1_PFD_scale` has them (the user's confirmed real
   repro) and compare against single-flag-off variants to find where behavior actually
   diverges into the bug.


## Session 4 continued (part 4) — likely found the manual-trig key handler itself, and a
## coherent end-to-end mechanism for the bug

Continuing directly from part 2/3: whole-image operand scan (`tools/GhidraResolve28.java`,
186,343 instructions, every function in the program) for who reads
`PLAYS_FREE`/`SCALE_MODE`/`DIRECT` (`+0x48fc`/`+0x48fd`/`+0x48fe`) turned up `FUN_4009f3a4`
reading `PLAYS_FREE` and `DIRECT` directly (outside the sequencer's per-step loop) — sitting
inside the `0x4009be00-0x4009f650` region session 3 part 4 already flagged as hosting an
unnamed function tied to MIDI track state, but never pinned down. Decompiled it
(`tools/GhidraResolve29.java`), found its callers (`tools/GhidraResolve30.java`), and
decompiled the biggest caller (`tools/GhidraResolve31.java`). Together these resolve the
"where is `+0x48fd` read?" open question from part 2 and give a coherent, traceable path
from a manual key press through to the DIRECT-consuming logic.

### `FUN_40044584(track, pressOrRelease)` — very likely THE manual-trig key handler

`param_1` = track index (0-15: 0-7 audio, 8-15 MIDI via `param_1-8`), `param_2` = 0 or 1
(rejects anything else). For MIDI tracks, reads a 3-valued byte at exactly
`+0x48fd` (blob-relative, via `_DAT_46c82456 + pattern*0x8ed8 + track*0x8b0 + 0x48fd` —
**this is `SCALE_MODE`, confirmed live-read at runtime**, resolving part 2's open question)
and dispatches on it. For audio tracks the analogous byte lives at a *different* offset in a
*different* per-track region (`+0x55` within a `0x91a`-strided block, not the `0x8b0`-strided
MIDI header) — scale mode is stored per track-type, not at one canonical offset.

Simplified MIDI-track logic (full raw decompile in
`out/ghidra/GhidraResolve31_session4.txt`):

```c
uVar4 = track - 8;
if (_DAT_80000012 != 0) {                        // MIDI-mode gate (role TBD)
  cVar1 = SCALE_MODE[track];                       // 0, 1, or 2 -- see open question below
  if (isRelease) {                                 // param_2 == 0
    if (cVar1 == 2) FUN_4009f3a4();                 // only value 2 does anything on release
    return;                                          // 0 and 1: no-op on release
  }
  // isPress (param_2 == 1):
  if (cVar1 == 1) {
    if (FUN_4009b290(track) == 1)                    // "is this track already active?"
      { FUN_4009f3a4(track); goto setKeyBit; }        // already active -> re-trigger path
    // else falls through to FUN_4009b5c8(track) below (not yet active -> normal start)
  } else if (cVar1 != 2) goto setKeyBit;             // cVar1==0: skip straight to setKeyBit
  FUN_4009b5c8(track);                                // "normal" trig-start (not decompiled yet)
  setKeyBit: _DAT_460d1794 |= (1 << track);
  return;
}
/* _DAT_80000012 == 0: entirely different path -- direct MIDI note-on/off scheduling via
   FUN_40005030/FUN_40042d1c/FUN_4004271c. Not the bug's precondition (PF+Direct+ScaleMode
   presumably requires the _DAT_80000012 != 0 branch); not traced further this session. */
```

`FUN_4009b290(track)` is a one-line accessor: returns `DAT_80006500[track]` (the
already-documented MIDI mute/active array) — i.e. **"is this track already active/playing
right now?"** So for `SCALE_MODE == 1` (our confirmed test value — see open question,
this may not be literally "per track") on a **press**, `FUN_4009f3a4` only fires when the
track is *already active*; otherwise the normal start path (`FUN_4009b5c8`) runs instead.
This is exactly the shape of a manual **re-trigger while already playing** — which lines up
with Plays Free being a precondition (a Plays Free track is the kind you'd press again while
it's still sounding, since it isn't locked to the step grid).

### The likely end-to-end bug mechanism, now traceable start to finish

```
FUN_40044584(track, press=1)                          -- manual key press, MIDI track
  SCALE_MODE[track] == 1                               -- per-track scale mode (our repro)
  FUN_4009b290(track) == 1                              -- track already active
    -> FUN_4009f3a4(track)
         gated on PLAYS_FREE[track] != 0                -- Plays Free ON (our repro)
         reads DIRECT[track]
           if DIRECT == -1 (selected, our repro) OR pattern-loaded-flag != 1:
             -> CLEARS DAT_80006500/0x800064d0/0x800064f0/0x800064e0/... (the track's
                active/playing state arrays) entirely
             -> FUN_400a539c(track)  (resets more per-track note/voice scratch state,
                                       sets a "release" flag to 1 for that track)
             -> FUN_40000c3c(0x460d17ae, ...)  (posts an event/message -- the same
                                                  "wake consumer task" primitive used
                                                  elsewhere for async work, per session 3)
           else:
             -> just flips bits in _DAT_80006680/_DAT_80006682 (the SAME bitmask pair
                FUN_400a1eea's per-step quantize-window logic reads/clears)
```

Read plainly: with all three of the user's confirmed preconditions active, a manual
re-trigger of an already-playing MIDI track routes into the DIRECT-selected branch of
`FUN_4009f3a4`, which **wipes the track's active-state bookkeeping and posts a generic
event, instead of taking the bit-flip path that (via `_DAT_80006680`/`_DAT_80006682`)
`FUN_400a1eea`'s step engine is set up to consume.** That's a plausible, concrete mechanism
for "manual trig silently does nothing / stops the track" under exactly the reported
conditions — though **whether the clear-and-post-event branch is actually wrong, or is
supposed to properly restart the note through some effect of the posted event that just
hasn't been traced yet, is not yet confirmed.** `FUN_40000c3c(0x460d17ae, ...)`'s effect and
`FUN_4009b5c8`'s behavior (the "normal start" path this whole thing is an alternative to)
are the natural next things to decompile.

### Open question: is SCALE_MODE really binary, or a 3-way enum?

`FUN_40044584` dispatches on `SCALE_MODE` having 3 distinct values (`0`, `1`, `2`), each with
different behavior, on both audio and MIDI tracks. Our confirmed diff only exercised a
`0x00 -> 0x01` transition (the user's "per track" setting). It's not yet established whether
the OT UI's scale-mode setting genuinely has a 3rd state (`2`) reachable some other way (a
3-position menu?), or whether `1` and `2` are actually the same conceptual "per track" mode
reached via different code paths for unrelated reasons, or something else. Worth asking
the user directly what OT UI options exist for this setting, and/or exporting a 3rd test
variant to see if a byte value of `2` is reachable at all. This matters because our repro's
observed behavior (value `1`, gated through `FUN_4009b290`'s activity check) may not be the
same path a value-`2` project would take (value `2` skips the `FUN_4009b290` gate entirely
on press, and behaves differently on release too).

### Next step
1. Decompile `FUN_4009b5c8` (the "normal start" path `FUN_40044584` takes instead of
   `FUN_4009f3a4` for tracks that aren't already active) and `FUN_40000c3c`'s target at
   `0x460d17ae` — need both to know what SHOULD happen on a normal re-trig, to confirm the
   DIRECT branch in `FUN_4009f3a4` is actually the divergence point and not intentional.
2. Resolve the value-`1`-vs-`2` SCALE_MODE question (ask the user about the real UI, or get
   a 3rd test export).
3. `_DAT_80000012` (gates whether this whole code path runs at all) and `DAT_8000004c`
   (checked in the `else` branches) are both new, unidentified globals worth naming.


## Session 4 continued (part 5) — user correction: it's not "scale mode", it's the manual-
## trig response mode (ONE/ONE2/HOLD); and the actual bug mechanism is now traceable

Two corrections from the user, both important:

1. **The 3-valued byte at blob `+0x48fd` (file `0x4964`) found in part 2/4 is NOT scale
   mode.** It's Elektron's own manual-trig-key **response mode** setting, with three named
   options: **"ONE"** (retrigger the track every press — what all our test projects are set
   to), **"ONE2"** (toggle: one press starts, the next press stops), and **"HOLD"** (plays
   only while the key is held down). Renaming this field `TRIG_MODE` going forward (still
   at the same confirmed offset — the location and the fact that it's read in `FUN_40044584`
   are unaffected, only its *meaning* was misidentified). "Scale mode" as a concept may not
   exist at this offset at all; if the user's OT project also has a real scale/track-length
   setting, it lives somewhere else, not investigated this session.

2. **A Plays-Free MIDI track manually triggered should start running even when the OT's
   overall sequencer transport is stopped.** This is expected/correct behavior, not a bug —
   and it directly explains why `_DAT_800065b8` matters here.

### `_DAT_800065b8` is very likely per-pattern "sequencer actually stepping" state, not a
### static "loaded" flag

Whole-image write search (`tools/GhidraResolve32.java`): all 3 writes to `_DAT_800065b8`
are `move.l Dn,(0x800065b8).l` — full 32-bit writes — and **all 3 sites are inside
`FUN_400a1eea`** (the per-step sequencer engine), not at pattern-load time as session 3
assumed ("MIDI pattern loaded" flag). Given it's written by the step engine itself and
tested as `!= 1` (not a simple zero check), the better working theory is that it reflects
whether the sequencer is actively stepping this pattern right now — which would explain
exactly why the user's point (2) matters: **when the overall transport is stopped, this
would plausibly read something other than `1`**, and both functions below treat
`_DAT_800065b8 != 1` as equivalent to DIRECT being selected. Not fully confirmed (would
need to watch it live across a transport stop/start), but it now has a much more precise
role than "loaded".

### The bug, traced concretely: `FUN_4009f3a4`'s restart path is missing the activation step
### that `FUN_4009b5c8` (the real "start" function) performs

`FUN_40044584`'s `TRIG_MODE == 1` ("ONE") press-handler logic (part 4) calls
`FUN_4009b290(track)` — "is this track already active?" (`DAT_80006500[track]`) — and
branches: **already active → `FUN_4009f3a4(track)`; not active → `FUN_4009b5c8(track)`.**
Decompiled `FUN_4009b5c8` this round (the "not active, so start it" path) and it is
unmistakably the Plays-Free start sequence: for MIDI tracks it checks PLAYS_FREE first
(non-Plays-Free tracks bail to a different function, `FUN_4009b95a`, not yet examined),
reads DIRECT, and:

```c
if ((cVar5 != -1 /* not DIRECT */) && (_DAT_800065b8 == 1 /* sequencer stepping */)) {
    // normal quantized case: just flip the step-engine bitmask, defer to FUN_4009b95a
    ...
    FUN_4009b95a();
    return;
}
// DIRECT selected, OR sequencer not actively stepping (transport stopped): full start --
(&DAT_46c77b89)[track] = DAT_800065be;    // save current pattern/bank
... [a large block: copies pitch/note/timing scratch state, initializes per-track buffers]
FUN_400a539c(track);                        // per-track note/voice reset
(&DAT_80006500)[track] = 1;                 // <-- ACTIVATES the track
FUN_40000c3c(0x460d17ae,&DAT_400abac8);     // posts the same event as FUN_4009f3a4
```

Compare directly against `FUN_4009f3a4`'s equivalent branch (part 3), same gating condition
(`cVar1 == -1 || _DAT_800065b8 != 1`), reached when the track is **already active**:

```c
(&DAT_80006500)[track] = 0;                 // <-- DEACTIVATES the track (opposite!)
... [clears the other per-track state arrays to 0]
FUN_400a539c(track);                        // same call
FUN_40000c3c(0x460d17ae,&DAT_400abac8);     // same event
```

**Both functions call the identical pair `FUN_400a539c(track)` +
`FUN_40000c3c(0x460d17ae,...)` in this branch. `FUN_4009b5c8` additionally does the full
state re-initialization and sets the track active (`DAT_80006500[track] = 1`) before that
pair; `FUN_4009f3a4` only clears state and sets it inactive (`= 0`) before the same pair.**
Given "ONE" mode is supposed to *restart* an already-playing track — i.e. conceptually
stop-then-immediately-start-again — `FUN_4009f3a4`'s branch does the "stop" half and never
does the "start" half. The track goes silent and stays silent, instead of restarting.

This lines up with every reported precondition and the user's point (2) simultaneously:
DIRECT selected and/or the transport being stopped both land in the exact same "clears
instead of restarts" branch (they're OR'd together in the gating condition), and Plays Free
is required simply because it's what makes `FUN_4009f3a4`/`FUN_4009b5c8` reachable for MIDI
tracks at all (non-Plays-Free tracks take the `FUN_4009b95a` path entirely). **This is now
the leading, well-evidenced candidate for the actual bug mechanism**, not just a lead.

### Open items
- Exact `TRIG_MODE` value mapping: `1 = ONE` is confirmed (user-stated + test data). Value
  `2`'s dispatch (press → unconditional `FUN_4009b5c8`; release → `FUN_4009f3a4`) reads more
  like **HOLD** (press starts, release stops) than "ONE2", contrary to the initial guess
  last round. Value `0` (press → unconditional `FUN_4009b5c8`, no active-check; release →
  no-op) doesn't obviously match either remaining name from the code alone — a toggle
  ("ONE2") would need state persisted *across* separate press events, which might live
  inside `FUN_4009b95a` (not yet decompiled) rather than in this dispatcher. Worth a 3rd
  test export (`TRIG_MODE` = the untested value) to pin this down definitively, same
  methodology as every other field this project has confirmed.
- `FUN_4009b95a` (the non-Plays-Free / normal-quantized path both functions defer to) is
  still undecompiled — likely holds the ONE2 toggle logic if that guess above is right.
- Not yet proposed a fix — the natural one (`FUN_4009f3a4`'s branch should call
  `FUN_4009b5c8` instead of/after clearing, rather than only clearing) needs the full
  register/stack context checked before treating it as safe; flagging as the shape of the
  fix, not a confirmed patch.


## Session 4 continued (part 6) -- DAT_80000012 identified as project-level "MIDI_MODE"
## setting (likely bug precondition #3); FUN_40044584 ground-truthed via raw disassembly;
## HOLD (=2) confirmed exactly; value 0 still unresolved; FUN_4009b95a is an empty stub

Two threads pursued: (1) fully ground-truth the TRIG_MODE dispatch in `FUN_40044584` against
raw disassembly (not just decompiled C, given the earlier decompiler-misrender lesson from
part 3), and (2) chase down `DAT_80000012`, the single global that gates whether the entire
TRIG_MODE-based dispatch even runs for MIDI tracks -- a strong candidate for the real
"scale mode = per track" bug precondition #3, since our confirmed per-track TRIG_MODE byte
turned out not to be scale mode at all (see part 5).

### `DAT_80000012` = a project-state boolean sourced from a text config key literally named
### "MIDI_MODE"

`DAT_80000012` has exactly **one write site** in the whole image (found earlier via
`GhidraResolve32`'s write-scan): inside `FUN_400866c4`, a ~7100-byte function that is
unmistakably a **text-based project/state-file line parser** (it reads lines byte-by-byte,
splits on `=`, and switches on section headers `SAMPLE`, `SETTINGS`, `STATES`, `META`, then
on a long chain of `KEY_NAME` string compares within the `STATES` section: `RELOAD_BANK`,
`PASTE_PATTERN`(bank index), `ARRANGEMENT`, `ARRANGEMENT_MODE`, `MIDI_MODE`, `RENAME_PART`,
...). This is very likely the parser for the project's saved/live-state text blob (separate
from the binary bank-file format this project has focused on so far).

The `MIDI_MODE` case, decompiled:
```c
iVar2 = FUN_40013e14(local_12d, s_MIDI_MODE_400b7e8b);   // strcmp against "MIDI_MODE"
if (iVar2 == 0) {
    ...
    iVar2 = FUN_400144c4(puVar3);      // parse the value after '='
    if (bVar10) {
        if (iVar2 < 0) {
            _DAT_100b14de = 0;
            _DAT_80000012 = 0;
        } else {
            _DAT_100b14de = iVar2;
            _DAT_80000012 = iVar2;
            if (0 < iVar2) {            // clamp to boolean
                _DAT_100b14de = 1;
                _DAT_80000012 = _DAT_100b14de;
            }
        }
    }
}
```
So `DAT_80000012` is a **boolean project setting, loaded once from a `MIDI_MODE=` line in a
text state/config blob**, clamped to 0 or 1. It is read (never written) everywhere else,
always as the outer gate in `FUN_40044584`:
```c
tst.l (0x80000012).l
beq.w 0x40044710      // MIDI_MODE == 0: entirely different code path (direct MIDI-out
                       // scheduling, does NOT read TRIG_MODE at all)
// falls through when MIDI_MODE != 0: reads TRIG_MODE (+0x48fd) and dispatches through the
// FUN_4009b5c8 / FUN_4009f3a4 pair analyzed in part 5 -- i.e. THIS is the whole codepath
// the bug lives in.
```
**This is a strong candidate for bug precondition #3.** The internal firmware name
("MIDI_MODE") doesn't obviously match the user's own description ("track scale mode = per
track"), but functionally it fits perfectly: it's a single global boolean, set once from
project state (not per-step, not per-track), and it gates whether the ONE/ONE2/HOLD
TRIG_MODE dispatch (where the actual bug lives) is reachable at all vs. an entirely
different, separately-implemented direct-MIDI-scheduling path when it's off. Whatever the
UI calls it, this is almost certainly the flag the user was describing -- flagging the name
mismatch explicitly rather than asserting the UI label, since that hasn't been directly
confirmed (would need a project text-state export to see the literal `MIDI_MODE=` line and
correlate it against the known-good/known-bad UI setting).

### `FUN_40044584` ground-truthed via raw disassembly (not just decompiled C)

Given the part-3 lesson about a decompiler misrender, the TRIG_MODE dispatch was re-checked
against raw disassembly end to end (`GhidraResolve33`). It confirms the decompiled structure
from part 5 exactly, with one addition -- the MIDI-track press dispatch (D3=track 8-15,
D4=press(1)/release(0), D0=TRIG_MODE byte sign-extended) is:

```
press (D4==1):
  D0 == 0  -> unconditionally call FUN_4009b5c8 (start), NO active-state check first
  D0 == 1  -> call FUN_4009b290(track) [active?]; if active -> FUN_4009f3a4 (the buggy
              clear-only path); if not active -> FUN_4009b5c8 (start)
  D0 == 2  -> unconditionally call FUN_4009b5c8 (start), NO active-state check first
              (same call as D0==0 -- these two share the exact same call site)
release (D4==0):
  D0 == 0  -> no call (falls straight to generic tail/no-op)
  D0 == 1  -> no call
  D0 == 2  -> call FUN_4009f3a4 (stop)
```

**Value `2` matches HOLD exactly and unambiguously**: press always (re)starts, release
always stops. Confirmed, not just inferred.

**Value `1` is bulletproof as ONE** (hardware test-data ground truth from every export this
project has). Its dispatch is the odd one out: it's the only value that bothers to check
active-state via `FUN_4009b290` before deciding whether to start or hand off to the
clear-only stop path. Conceptually this is exactly "restart if already playing, start if
not" -- i.e. correct ONE intent -- but the "restart" half is implemented as a bare stop
(`FUN_4009f3a4`'s clear-without-reactivate branch, per part 5) instead of stop-then-start,
which is the bug.

**Value `0` remains unresolved.** By elimination it should be ONE2 (toggle: first press
starts, second press stops), but the dispatch code doesn't match a toggle at all -- it just
unconditionally calls `FUN_4009b5c8` on every press with no active-state check, and does
nothing on release. Checked whether `FUN_4009b5c8` itself might contain the toggle/active
check (in case it was hiding there instead of in the dispatcher) -- it does not: its full
decompile (recovered from the `GhidraResolve32` log) has no active-state read anywhere;
it either defers to `FUN_4009b95a` (quantized/non-DIRECT case) or unconditionally does the
full re-init-and-activate sequence (DIRECT-or-not-stepping case), regardless of whether the
track was already active. So value 0's press behavior would, if anything, always sound like
a correct **restart-every-press**, not a toggle -- more like a second flavor of "ONE" than
"ONE2". Possibilities, none confirmed: (a) value 0 is simply never emitted by the real UI
(reserved/default-only) and ONE2 is actually value... there is no 4th value though, so this
seems unlikely; (b) ONE2's toggle-off check happens further upstream, before
`FUN_40044584` is even called (e.g. the caller only invokes this dispatcher on transitions,
suppressing the "off" press before it gets here) -- not yet checked; (c) our identification
of which named mode maps to which value is simply wrong in some way not yet apparent from
static analysis alone. **Next concrete step to resolve this: a third real test export with
TRIG_MODE set to ONE2 specifically (not just "the untested byte value"), so the byte value
can be read directly via the emulator deserializer the same way every other field in this
project has been confirmed** -- guessing further from code alone isn't productive past this
point.

### `FUN_4009b95a` is a literal empty stub

Both `FUN_4009b5c8`'s and `FUN_4009f3a4`'s "quantized / not DIRECT / sequencer stepping"
branches defer to `FUN_4009b95a()` after flipping bits in `_DAT_80006680`/`_DAT_80006682`.
Decompiled in full this session: `void FUN_4009b95a(void) { return; }` -- a true no-op, 10
bytes (entry + rts, effectively). This rules it out as a hiding place for ONE2 toggle logic
or any other quantized-path state machine; the real work in the quantized case is entirely
the bit-flip that happens just before the call, consumed later by `FUN_400a1eea`'s per-step
engine. Likely vestigial (a hook point that no longer does anything in this firmware
version) rather than a bug.

### Also fully confirmed this round: `FUN_4009b290`

```c
uint FUN_4009b290(uint param_1) {
  if ((int)param_1 < 0) return _DAT_800065b8;
  return (uint)(byte)(&DAT_80006500)[param_1 & 0xf];
}
```
Simple active-state getter: `DAT_80006500[track]` per-track (the same array `FUN_4009b5c8`
sets to 1 on activate and `FUN_4009f3a4`'s buggy branch sets to 0 on deactivate), or
`_DAT_800065b8` itself when called with a negative sentinel. No new information beyond
part 5's earlier partial view, but now the full body is confirmed rather than summarized.

### Open items (updated)
- **Precondition #3 identity**: `DAT_80000012`/"MIDI_MODE" is now the strongest candidate,
  but the internal name doesn't confirm the UI label the user used ("scale mode = per
  track"). Not confirmed against a project state-text export yet.
- **TRIG_MODE value 0**: still unresolved; needs a 3rd real test export with ONE2 selected,
  same methodology as every other confirmed field.
- Fix shape is unchanged from part 5: `FUN_4009f3a4`'s DIRECT-or-not-stepping branch clears
  and deactivates a track but never re-runs the reactivation sequence `FUN_4009b5c8`
  performs in its equivalent branch. Still not proposed as a concrete patch pending full
  register/stack verification.


## Session 5 (Claude Code, on the user's Mac) — toolchain moved to native macOS; Open Item 1
## resolved (dead end); handoff-5's "SCALE_MODE not in the trig chain" claim is WRONG;
## step-engine quantize handler fully mapped

Environment change: this session runs as Claude Code directly on the user's Apple-Silicon Mac
(OS user `kyoti_m4`), not the old Cowork Linux sandbox. Consequences:

### Toolchain (replaces the from-source `ghidra_opt` build recipe in the Session 4 intro)
- **Ghidra**: Homebrew formula at `/opt/homebrew/Cellar/ghidra/12.1.2/` →
  `analyzeHeadless` = `/opt/homebrew/Cellar/ghidra/12.1.2/libexec/support/analyzeHeadless`.
  The official `mac_arm_64` native `decompile` binary ships and works
  (`.../libexec/Ghidra/Features/Decompiler/os/mac_arm_64/decompile`) — **no from-source
  build needed**, exactly as handoff-5 predicted.
- **JDK 21**: `/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home`.
- Invocation that works (headless run ≈ 4 s, JVM + project open + scripts):
  ```
  export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home
  export PATH="$JAVA_HOME/bin:$PATH"
  /opt/homebrew/Cellar/ghidra/12.1.2/libexec/support/analyzeHeadless \
    ~/Documents/octamax/ghidra_project octamax -process "section_3_MAIN_OS.bin" \
    -noanalysis -scriptPath ~/Documents/octamax/tools -postScript GhidraResolveNN.java
  ```
  `GHIDRA_JAVA_OPTIONS="-Duser.name=kyoti_m4"` is **not** needed (running as the project
  owner). The "Could not determine local host name" and `DuplicateFileException` benign
  errors from the old sandbox do **not** appear here.
- **macOS TCC gotcha (cost ~an hour at session start)**: `~/Documents` is TCC-protected and
  the process that actually touches the FS is Anthropic's `claude` binary
  (`com.anthropic.claude-code`, separately Developer-ID-signed), **not** VS Code. Granting
  VS Code Full Disk Access does nothing. Fix: add
  `~/.vscode/extensions/anthropic.claude-code-<ver>-darwin-arm64/resources/native-binary/claude`
  to Full Disk Access (path is version-stamped → re-add after each extension update), or run
  `claude` from Terminal.app instead. Test project folders (`test1_*`) also exist as copies
  on `~/Desktop` (readable without the grant).

Scripts this session: `tools/GhidraResolve36.java` … `GhidraResolve38.java`. Raw logs:
`out/ghidra/GhidraResolve3{5,6,7,8}_session5.txt`.

### Open Item 1 RESOLVED — `FUN_4009a670` is a load-time bounds-CLAMP, not on the trig path

Decompiled in full (`GhidraResolve36`). It walks all 8 audio tracks (stride `0x91a`), then all
8 MIDI tracks (stride `0x8b0`), then the pattern-level fields, **clamping every field to its
legal range** and returning the count of corrections made. Callers (all load/init-time, none
runtime):
- `FUN_4008cebc` — the bank deserializer, calls it once on the freshly-loaded blob.
- `FUN_4009abdc` — the pattern **initializer** ("new empty pattern" defaults), tail-calls it.
- `FUN_40025770` — bulk "validate all 16 patterns of a bank" loop (`adda.l #0x8ed8,A2`).

**Not reachable from `FUN_40044584` / the manual-trig path at all.** Dead end for the bug
mechanism — but it hands us authoritative field ranges (blob-relative, per track for the
per-track ones):
| offset | field | clamp range |
|---|---|---|
| `+0x48f8` | per-track quantlen | `[2, 0x40]` |
| `+0x48f9` | (unnamed, MIDI) | `[0, 6]` |
| `+0x48fa` | (unnamed, MIDI) | `[0, 0x1e]` |
| `+0x48fb` | (unnamed, MIDI) | `[-1, 1]` |
| `+0x48fc` | **PLAYS_FREE** | `[0, 1]` |
| `+0x48fd` | **TRIG_MODE** | `[0, 2]` (⇒ 3 states ONE/ONE2/HOLD, consistent) |
| `+0x48fe` | **DIRECT/TRIGQUANT** | `[-1, 0x10]` |
| `+0x48ff` | (unnamed, MIDI) | `[0, 1]` |
| `+0x8e50` | pattern length (u16) | `[2, 0x400]` |
| `+0x8e52` | pattern scale idx | `[0, 6]` |
| `+0x8e53` | pattern fallback quantlen | `[2, 0x40]` |
| `+0x8e54` | pattern "scale offset" (see below) | `[0, 6]` |
| `+0x8e55` | **SCALE_MODE** | `[0, 1]` — **binary at pattern level** (settles part-4's "is it 3-valued?" — no) |
| `+0x8e56` | (pattern) | `[-1, 0x10]` |
| `+0x8e57` | (pattern) | `[0, 3]` |
| `+0x8e58` | (pattern, i32) | `[0x2d0, 0x1c20]` else `0xb40` |

`FUN_4009abdc` init defaults: pattern `+0x8e50=0x10, +0x8e52=2, +0x8e53=0x10, +0x8e54=2,
+0x8e55=0, +0x8e56=0, +0x8e57=0, +0x8e58=0xb40`; per-track (both types) first 8 bytes
`{0x10, 2, 0, 0xff, 0, (u8)_DAT_80000094, 0, 0}`.

### CORRECTION to handoff-5 Open Item 2 — `FUN_4009b5c8` **does** read SCALE_MODE (`+0x8e55`)

Handoff-5 states `+0x8e55` "is not read anywhere in the manual-trig-key dispatch chain
(`FUN_40044584`, `FUN_4009b5c8`, `FUN_4009f3a4`)". **`FUN_4009b5c8` reads it.** It was
already visible in the `GhidraResolve32` decompile and is now confirmed against **raw
disassembly** (`GhidraResolve38`, `0x4009b6d0`–`0x4009b704`, in the function's full-init
tail which Ghidra has split off as a separate `candidate_4009b64e` listing):
```asm
; D1 = 0x400e21e0 + bank*0x9b340 ;  D6 = current pattern ;  D3 = track index (0..15)
move.l #0x8ed8,D2 ; muls.l D6,D2 ; move.l D1,D0 ; add.l D2,D0
movea.l D0,A0 ; adda.l #0x8e54,A0        ; A0 = pattern-block + 0x8e54
lea (-0x7fff99c2).l,A1                    ; A1 = 0x8000663e  (&DAT_8000663e)
tst.b (0x1,A0)                            ; <-- SCALE_MODE, pattern-level +0x8e55
beq.b .normal
  move.l #0x91a,D0 ; muls.l D3,D0 ; add.l D2,D0   ; D0 = track*0x91a + pattern*0x8ed8
  movea.l D1,A0 ; lea (0x51,A0,D0*1),A0           ; A0 = blob + bank + that + 0x51
.normal:
move.b (A0),(0x0,A1,D3*1)                 ; DAT_8000663e[track] = *A0
```
The whole-image operand scan (`GhidraResolve35`) missed it because `+0x8e55` is reached as
`[regA + 0x8e54] + 1` — register-relative, the documented blind spot (3rd time now:
DIRECT read, the earlier `+0x8e55` read in `FUN_400a1eea`, and this one).

So SCALE_MODE's effect on the trig path: in `FUN_4009b5c8`'s **full-init branch only**, it
picks the *source byte* copied into `DAT_8000663e[track]`:
- SCALE_MODE == 0 (Normal): pattern-level byte `+0x8e54`.
- SCALE_MODE != 0 (per-track): per-track byte at `blob + pattern*0x8ed8 + track*0x91a + 0x51`.
  **Note the `0x91a` (audio) stride applied to a raw track index that is 8–15 for MIDI** —
  for MIDI track 8 that resolves to blob-relative `+0x4921` (inside MIDI-track-0's sub-block
  but not at a named field). Present identically in raw asm and decompile — not a misrender.
  Looks anomalous (audio stride on a MIDI index); could itself be a firmware bug or the
  per-track scale byte for MIDI genuinely lives in a `0x91a`-strided shared array. **Unverified.**

`FUN_4009f3a4` still does **not** reference `+0x8e55` in either branch (re-confirmed against
full decompile + raw asm, `GhidraResolve37`).

### `DAT_8000663e` fully characterised — it's a per-track "scale offset", consumed by the
### step engine's quantize handler

`DAT_8000663e[track]` is written by exactly two places:
1. `FUN_4009b5c8` full-init branch (seed, SCALE_MODE-gated, above).
2. `FUN_400a1eea` (the per-step engine), inside its once-per-step quantize handler
   (`DAT_800065b6 == 0` gate), in the `_DAT_80006680` "soft (re)start at boundary" sub-block
   — with the **same SCALE_MODE gate**:
   ```c
   if (puVar45[0x8e55] == '\0')  *scaleoff = puVar45[0x8e54];               // Normal: pattern byte
   else                          *scaleoff = puVar45[track*stride + 0x51/0x48f9];  // per-track byte
   cVar = tbl[*scaleoff] - tbl[DAT_8000663d];      // tbl = DAT_400aba50[]
   DAT_800065db/cb[track] = clamp(...);
   if (tbl[DAT_8000663d] - tbl[*scaleoff] < 1) { DAT_80006508[track] = 1; FUN_400a539c(track); }  // REACTIVATE
   else                                          _DAT_80006684 |= bit;                              // pending
   _DAT_80006680 &= ~bit;
   ```
   (`DAT_8000663d` is a separate single byte one address below — a global "current/target
   scale offset". `DAT_400aba50` is a small translation table, same one part 2 flagged.)
It is **read** only via the on-stack pointer table `FUN_400a1eea` builds (`lea 0x8000663e,An
; move.l An,(slot,SP)` at `0x400a292a` / `0x400a2970` / `0x400a3cb4` — these are *address
stashes*, not content reads; the content reads are the `*pcStackNN` derefs above).

### The step engine's quantize handler is **entirely skipped when DIRECT is selected**

`FUN_400a1eea`'s per-track quantize block (both the `_DAT_80006680` soft-restart and the
`_DAT_80006682` soft-stop sub-blocks) begins:
```asm
mvs.b (0x48fe,A0),D0      ; DIRECT byte, this track
bne.b  .handleNonZero     ; != 0
 ... (D0==0, quantize-index 0): SCALE_MODE picks +0x8e53 vs +0x48f8 as the window length ...
.handleNonZero:
tst.l D0
ble.w  LAB_400a37f0       ; D0 == -1  (DIRECT selected)  -> SKIP the whole handler for this track
 ... (D0 in 1..16): table lookup ...
```
So with DIRECT selected, the step engine does **nothing** for the track's soft
restart/stop — it neither papers over nor re-creates the missing reactivation.

### Where this leaves Open Item 2 (still open, but sharper)

On paper the bug should reproduce on **DIRECT + PLAYS_FREE + ONE + MIDI_MODE alone**,
independent of SCALE_MODE:
- `FUN_4009f3a4` takes its clear branch because `cVar1 == -1` (DIRECT) — deactivates, posts
  event, never reactivates, never touches `_DAT_80006682`.
- `FUN_400a1eea` skips the track (DIRECT) — no recovery.
- SCALE_MODE is absent from both paths; its only role is choosing the *seed value* of
  `DAT_8000663e[track]` at first start, and `DAT_8000663e` is only consumed by the
  step-engine handler that DIRECT already causes to be skipped.

Yet the user has confirmed on real hardware that pattern-scale = **Per Track** is required
(A1 Per-Track shows the bug, A2 Normal doesn't, all else identical). Unreconciled. Leading
hypotheses now, none verified:
1. The anomalous `track*0x91a + 0x51` MIDI seed read in `FUN_4009b5c8` (audio stride on a
   MIDI index) lands on a byte whose value flips some *other* downstream decision when
   SCALE_MODE is per-track — needs the emulator to see what's actually at `+0x4921…+0x49xx`
   for the repro banks and who else reads it.
2. There is a second SCALE_MODE consumer still hidden behind register-relative addressing
   (the operand scan has now demonstrably missed `+0x8e55` reads **twice**). A dedicated
   pass that walks every `adda/lea #0x8e5x` / `#0x8e40..0x8e60` immediate and every
   `(disp,An)` with `disp` in that window, across the whole image, is warranted before
   trusting "only these N functions read it".
3. `_DAT_800065b8` ("stepping") and/or `DAT_800065b6` (sub-step gate) are computed
   differently under per-track scale, changing which `FUN_4009f3a4` branch is taken. Not
   traced.
4. Mapping error somewhere (e.g. "trigger quantization = Direct" is not `+0x48fe == -1` in
   the per-track-scale case, because `+0x48fe`'s meaning shifts with scale mode — cf. the
   `+0x8e53`-vs-`+0x48f8` swap the step engine already does for quantize-index 0).

### Next steps (revised priority)
1. **Extend `emu_bankdeserialize.py` into an actual execution harness** for `FUN_4009f3a4`
   and the `FUN_400a1eea` quantize handler: load a real repro bank blob into RAM at
   `bank_blob_base`, set `DAT_800065bd/be` (bank/pattern), `_DAT_800065b8`, then call
   `FUN_40044584(8, 1)` twice and watch `DAT_80006500[8]` / `DAT_80006508[0]` /
   `_DAT_80006680/82/84` / `DAT_8000663e`. Run it once with `test1_PFD_scale` (bug repro)
   and once with `test1_PFD` (per-track scale OFF) and diff the RAM trace — this is the
   direct way to see what SCALE_MODE actually changes, rather than more static staring.
2. Whole-image **register-relative** scan for `+0x8e54/+0x8e55` (immediates `0x8e40..0x8e60`
   in `adda/lea/addi/movea`, plus `(disp,An)` displacements in that window). The operand
   scan is confirmed unreliable for this offset.
3. Decompile `FUN_4009f2f8` (called by `FUN_4009f3a4`'s MIDI clear branch, `param = track-8`)
   — small, not yet looked at; the only sub-call in the buggy branch besides `FUN_400a539c`
   and `FUN_40000c3c` that hasn't been read.
4. Still outstanding from part 6: 3rd hardware export with **TRIG_MODE = ONE2** to resolve
   value `0`.
5. Fix shape unchanged; still blocked on items 1–2.


## Session 5 part 2 — [SUPERSEDED BY PART 3 — the `+0x48fd`/ONE2 story below is NOT the
## reported bug; kept for the byte-level facts and the harness build-out only]
## ~~BUG REPRODUCED IN EMULATION. Root cause is the `+0x48fd` dispatch byte~~

Built `tools/emu_trigbug.py` — a Unicorn execution harness (reuses `emu_bankdeserialize.py`'s
file-read hook to deserialize a real bank, writes the blob to the real base `0x400e21e0`,
sets the ~8 globals the trig path reads, then calls the real `FUN_40044584(track, press)`
**twice** to simulate re-pressing an already-playing track's trig key). Run log:
`out/ghidra/emu_trigbug_session5.txt`. Notes on the harness:
- Unicorn m68k faults (`UC_ERR_EXCEPTION`) on the privileged `move SR,Dn` / `move #imm,SR`
  / `move Dn,SR` critical-section guards. Handled at runtime: a `UC_HOOK_CODE` callback
  detects those encodings (`w & 0xFFC0 in {0x40C0,0x42C0,0x44C0,0x46C0}`) and advances PC
  past them. Safe for a single-threaded trace.
- Stubbed to `rts`: `FUN_40000c3c` (event post), `FUN_40010bc8` (MIDI send), `FUN_400108b0`.
  Left real: `FUN_4009b290`, `FUN_4009b5c8`, `FUN_4009f3a4`, `FUN_4009b95a`, `FUN_400a539c`,
  `FUN_4009f2f8`.
- `FUN_40044584` uses a **different** blob pointer + pattern index than the b5c8/f3a4/1eea
  trio: it reads the blob base from the pointer at `_DAT_46c82456` and the pattern index
  from `DAT_100b14d0` (byte), NOT `0x400e21e0` / `DAT_800065bd`/`be`. Harness sets all of
  them (`[0x46c82456] = 0x400e21e0`, `[0x100b14d0] = 0`, `[0x800065bd/be] = 0`,
  `[0x80000012] = 1` MIDI_MODE, `[0x800065b8]` = stepping flag).

### Ground truth from deserializing all 5 real test banks (`scratchpad/insp_banks.py`)

Pattern 0, MIDI track 0 (= track index 8) header bytes, and pattern-level `+0x8e55`:

| project | +0x48fc PLAYS_FREE | +0x48fd | +0x48fe DIRECT | +0x8e55 SCALE_MODE |
|---|---|---|---|---|
| test1_PF_        | 1 | **0** |  0 | 0 |
| test1_PFD        | 1 | **0** | -1 | 0 |
| test1_PFD_scale  | 1 | **1** | -1 | **1** |
| test1nil         | 0 | **0** |  0 | 0 |
| test1nil_scale   | 0 | **0** |  0 | **1** |

Key: `test1nil_scale` has pattern SCALE_MODE `+0x8e55 = 1` but `+0x48fd = 0` — so **`+0x48fd`
is NOT a mirror of the pattern scale-mode bit.** Only `test1_PFD_scale` (the confirmed
hardware repro) has `+0x48fd = 1`. `test1_PFD` vs `test1_PFD_scale` still differ by exactly
2 bytes: `+0x48fd` 0→1 and `+0x8e55` 0→1.

### The emulation result (identical for `stepping = 1` and `stepping = 0`)

```
test1_PFD  (+0x48fd = 0):
  press #1:  DISP -> B5C8(start)                          -> active[8] = 1
  press #2:  DISP -> B5C8(start)                          -> active[8] = 1   (restarts, keeps playing) OK
test1_PFD_scale  (+0x48fd = 1):
  press #1:  DISP -> B290(inactive) -> B5C8(start)        -> active[8] = 1
  press #2:  DISP -> B290(ACTIVE)   -> F3A4(retrig)
                    -> F2F8(note-off sweep) -> 4x midisend -> A539C(reset) -> C3C(event)
             -> active[80006500][8] = 0  AND  active[80006508][0] = 0        (fully de-activated) BUG
```

Matches the user's behavioural description exactly: after the buggy re-press the track is
fully de-activated (both the audio-indexed `DAT_80006500[8]` and the MIDI-indexed
`DAT_80006508[0]` go to 0), so it neither sounds nor advances — "only the step-1 C ever
fires, the step-2 C# never does."

### Root cause, now concrete and demonstrated

`FUN_40044584`'s MIDI-track press handler dispatches on the byte at
`blob + pattern*0x8ed8 + (track-8)*0x8b0 + 0x48fd`:
- **`+0x48fd == 0`**: `if (cVar1 != 0)` is false → falls straight through to
  **`FUN_4009b5c8(track)` unconditionally, with no active-state check**. A re-press of an
  already-playing track therefore re-runs the full start/re-init → the track restarts. **No bug.**
- **`+0x48fd == 1`**: calls `FUN_4009b290(track)` first; **track already active → `FUN_4009f3a4(track)`**,
  whose DIRECT branch (`+0x48fe == -1` ⇒ `cVar1 == -1`) clears `DAT_80006500`/`DAT_80006508`
  and the other per-track arrays, sweeps note-offs (`FUN_4009f2f8`), calls `FUN_400a539c`,
  posts the event — and **never re-activates**. Track goes silent and stays silent. **Bug.**
- `+0x48fd == 2` (HOLD): press → unconditional `FUN_4009b5c8`; release → `FUN_4009f3a4`.
  (Not the bug — press always restarts.)

So the **necessary-and-sufficient trigger is `+0x48fd == 1` together with `+0x48fe == -1`
(DIRECT) and `+0x48fc == 1` (PLAYS_FREE)**. `_DAT_800065b8` (stepping / transport) does
**not** matter — DIRECT alone forces `FUN_4009f3a4` into the clear-only branch.
`+0x8e55` (pattern SCALE_MODE) is **not** in the mechanism at all — its only role is
picking the `DAT_8000663e` seed source (Session 5 part 1), and that value is only consumed
by the step-engine quantize handler which DIRECT causes to be skipped. It is a **passenger**
that happens to co-vary with `+0x48fd` in the `test1_PFD_scale` export.

### RESOLVED (user-confirmed): `+0x48fd` IS TRIG_MODE; the mapping is 0=ONE, 1=ONE2, 2=HOLD

User: *"It was somehow set to ONE2. I missed that."* So `test1_PFD_scale` differs from
`test1_PFD` by **two** independent UI changes — pattern scale → Per Track (`+0x8e55`, a **red
herring**, plays no role) and the MIDI track's trig mode → **ONE2** (`+0x48fd`, the actual
cause). Confirmed TRIG_MODE value map (corrects parts 4–6, which had guessed `1 = ONE`):

| `+0x48fd` | mode | `FUN_40044584` press behaviour | release |
|---|---|---|---|
| 0 | **ONE**  | unconditional `FUN_4009b5c8` (restart every press) | no-op |
| 1 | **ONE2** | active? → `FUN_4009f3a4` : `FUN_4009b5c8` | no-op |
| 2 | **HOLD** | unconditional `FUN_4009b5c8` | `FUN_4009f3a4` |

ONE2 is the only mode that calls `FUN_4009f3a4` on *press*, and only when the track is
already playing — i.e. a manual **re-trigger while playing**. With Direct selected that
lands in `FUN_4009f3a4`'s clear-only branch → silence. Open Item 2's "why does scale mode
matter" puzzle is fully dissolved: it never did. Open Item 3 ("value 0 isn't a toggle") too:
value 0 is ONE, not ONE2.

**Updated bug preconditions (all 4 required, superseding the handoff's list):**
1. MIDI track.  2. Plays Free (`+0x48fc == 1`).  3. Trig quant = Direct (`+0x48fe == -1`).
4. **Trig mode = ONE2** (`+0x48fd == 1`).  *(Not pattern scale; not `_DAT_800065b8`.)*
Plus `_DAT_80000012` / MIDI_MODE must be on for the whole TRIG_MODE dispatch to run.

### (historical) the ambiguity that led here — what is `+0x48fd` and why did it flip?

`+0x48fd` is Elektron's per-MIDI-track manual-trig **response-mode** byte (part 5: ONE / ONE2
/ HOLD), clamped `[0,2]` by `FUN_4009a670`. The emulated dispatch says:
- value 0 → "restart every press" — behaviourally this is **ONE**.
- value 1 → "if already playing, hand to `FUN_4009f3a4`" — the buggy one; behaviourally a
  **toggle-ish / ONE2**.
- value 2 → HOLD (confirmed earlier).
This is the **opposite** of the part-4/5 assumption that `1 = ONE`. Under the emulated
behaviour, `0 = ONE` and `1 = ONE2`, which also dissolves the part-6 "value 0 doesn't act
like a toggle" puzzle (it isn't ONE2, it's ONE).

**Open question for the user** (the last thing blocking a clean writeup): when `test1_PFD_scale`
was exported from `test1_PFD`, which OT UI setting(s) changed? If the MIDI track's trig mode
was set to **ONE2** at that time, everything is consistent and the bug is simply "ONE2 +
Plays Free + Direct MIDI track: manual re-trigger stops instead of toggling/restarting". If
*only* "pattern scale → Per Track" was changed, then enabling Per-Track scale has a side
effect of also writing `+0x48fd = 1`, and the two are genuinely linked in the firmware's
save path (would need to trace the pattern-settings writer, `FUN_4008a6fc`/serializer side).

### Candidate fix — 1 byte, validated in the harness

The bug is entirely in `FUN_40044584`'s ONE2 press dispatch calling `FUN_4009f3a4`
(stop-only) where it needs a restart. Fixing `FUN_4009f3a4` itself is wrong — HOLD *release*
also calls it and must stop. So fix the call site:

```
             0x400446a2   beq.b 0x400446c8      67 24     "ONE (+0x48fd==0): unconditional FUN_4009b5c8"
  patch ->   0x400446a2   bra.b 0x400446c8      60 24     make ONE2 fall through to the same call
```

**One byte: file offset `0x442a2` (= `0x400446a2 - 0x40000400`), `0x67` → `0x60`.**
Effect: ONE2 press now always routes to `FUN_4009b5c8` (start/restart every press) — exactly
like ONE — instead of `active ? FUN_4009f3a4 : FUN_4009b5c8`. HOLD is unaffected (HOLD press
already lands on `0x400446c8`; HOLD release is on the separate `D4==0` path). ONE and ONE2
become behaviourally identical.

Harness validation (`out/ghidra/emu_trigbug_fix_session5.txt`), `active[8]` after presses 1/2/3:

| bank (track 8, pattern 0) | mode | stock | patched |
|---|---|---|---|
| test1_PFD        | ONE  | `1 1 1` | `1 1 1` (unchanged) |
| test1_PFD_scale  | ONE2 | `1 0 1` ← **bug** | `1 1 1` ← **fixed** |
| test1_PF_ (stepping=0) | ONE, not Direct | `1 1 1` | `1 1 1` (unchanged) |
| test1_PF_ (stepping=1) | ONE, not Direct | `0 0 0`* | `0 0 0`* |

*`test1_PF_` has DIRECT=0 (quantize-index 0), so `FUN_4009b5c8` takes the "soft" path
(`_DAT_80006680 |= bit`, defer to step engine) and never sets `active[8]` directly — the
harness doesn't run `FUN_400a1eea` so it stays 0. Correct behaviour, not a regression.

**Caveat / open question for the user:** this makes ONE2 press-on-playing *restart* instead
of *toggle-off*. If ONE2 is meant to be "retriggerable one-shot" (the reading that makes
this a bug at all), that is exactly right and ONE2==ONE is acceptable. If ONE2 is genuinely
meant to *toggle* (press on / press off) and the only defect is that the toggle-off path is
a hard clear rather than a clean stop, then the fix instead needs `FUN_4009f3a4`'s clear
branch to stop the track *cleanly enough that the step engine or transport can restart it* —
a bigger change needing free-space for a trampoline (no room to inline stop-then-start at
the call site: the pushed `FUN_4009f3a4` arg can't be reused without also widening the
shared `addq.l #4,SP` at `0x400446d0`, which other press paths reach with only one arg).

### Audio tracks: same prerequisites, NO bug (user-confirmed) — why

`FUN_40044584`'s audio path (`param_1 < 8`) has the **same** `cVar1==1 → already-active →
FUN_4009f3a4` dispatch shape as MIDI, reading the mode byte from the audio struct at
`+0x55` (within the `0x91a` stride) instead of `+0x48fd`. Two candidate reasons audio is
immune, not yet disambiguated:
1. The audio dispatch is gated behind `(DAT_8000004c & 1) != 0`; when that bit is 0, a
   manual audio-track trig goes to `FUN_4003f3a8(track, track+0x18, 0x7f)` entirely — it
   never touches `FUN_4009f3a4`/`FUN_4009b5c8`. (MIDI, by contrast, is gated on
   `_DAT_80000012` / MIDI_MODE, which the repro has set.)
2. Even if `FUN_4009f3a4` does run for an audio track and de-activates it, an audio track
   is locked to the step grid — the next sequencer step re-triggers it from the pattern's
   own trig data, so the missing reactivation is invisible. A **Plays Free** MIDI track has
   no such safety net (that is *why* PLAYS_FREE is a precondition), so the de-activation
   sticks.
Worth a quick check of `DAT_8000004c`'s meaning and the audio `+0x55` byte's value in the
test exports, but this doesn't change the MIDI root cause.

### Next steps
1. Get the user's answer on the `+0x48fd` question above (ONE2 vs a scale-mode side effect).
2. Extend the harness to drive `FUN_400a1eea` a few steps after the re-press (needs the `a0`
   precondition + more globals) to show C then C# firing for `test1_PFD` and nothing for
   `test1_PFD_scale` — a full behavioural repro, not just the active-flag proxy.
3. Draft the `FUN_4009f3a4` patch and validate it in `emu_trigbug.py`.


## Session 5 part 3 — user disclosed two things that overturn part 2's conclusion; the REAL
## root cause is a MIDI stride bug in `FUN_4009b5c8`'s per-track-scale read

Two facts from the user:
1. *"It was somehow set to ONE2 [`+0x48fd == 1`]. I missed that."* — so `test1_PFD_scale`
   differs from `test1_PFD` by **two** UI changes, not one.
2. **On real hardware the bug reproduces with Plays Free + Direct + per-track scale for
   ALL THREE trig modes (ONE, ONE2, HOLD)** — trig mode is *not* a precondition.
3. Audio tracks with the identical settings are fine.

Part 2's `+0x48fd`/ONE2 → `FUN_4009f3a4` story is therefore **not the reported bug** (it's
trig-mode-specific, which #2 rules out). It's a real secondary code smell — keep it filed,
but it is not this. Part 2's byte-level facts still stand: `+0x48fd` = TRIG_MODE
(0=ONE/1=ONE2/2=HOLD), confirmed; `test1_PFD_scale` has `+0x48fc=1, +0x48fd=1, +0x48fe=-1,
+0x8e55=1`.

### The real mechanism — `FUN_4009b5c8` reads the MIDI per-track scale byte with the AUDIO stride

`FUN_4009b5c8`'s full-init branch, SCALE_MODE(`+0x8e55`)-gated seed (Session 5 part 1 quoted
this and under-weighted it). Raw asm `0x4009b6f2`–`0x4009b704`:
```
D3 = param_1 (track; 8..15 for MIDI)   D2 = pattern*0x8ed8   D1 = 0x400e21e0 + bank*0x9b340
if (blob[pattern-block + 0x8e55] != 0):          # SCALE_MODE = "Per Track"
    D0 = D3 * 0x91a                               # <-- AUDIO track stride, on a MIDI index
    A0 = D1 + D0 + D2 + 0x51                       # = blob + track*0x91a + 0x51
DAT_8000663e[D3] = *A0                             # D3 = 8..15  ->  writes DAT_80006646[0..7] (aliased)
```
For an **audio** track (`param_1` 0–7) `blob + track*0x91a + 0x51` is that audio track's real
scale byte — and `FUN_400a1eea`'s audio loop reads the exact same expression, so audio is
self-consistent → **audio has no bug** (matches fact #3).

For a **MIDI** track (`param_1` 8–15) the audio stride `0x91a` on index 8 lands at blob
`+0x4921` — `0x21` bytes into MIDI track 0's *trig data*, not any scale field — and it
drifts a further `0x6a` per track. The correct MIDI read (what `FUN_400a1eea`'s MIDI loop
uses) is `blob + pattern-block + (track-8)*0x8b0 + 0x48f9`. `FUN_4009b5c8` never added the
MIDI branch for this one read.

Harness proof (`emu_trigbug.py` → `scale_evidence()`, log `out/ghidra/emu_trigbug_scale_session5.txt`):

| bank | SCALE_MODE | `FUN_4009b5c8` reads | → `DAT_80006646[0]` after press | `DAT_400aba50[idx]` |
|---|---|---|---|---|
| test1_PFD       | 0 (Normal)   | pattern byte `+0x8e54` = 2 | **2** (valid) | `[2]` = 6  ✓ |
| test1_PFD_scale | 1 (Per Track)| `blob[+0x4921]` = **0xff** | **255** ← out of range | reads 0x3fc past a 13-entry table = garbage |

`DAT_400aba50` = `int32[13]` = `{3,4,6,8,12,24,48,96,48,24,12,6,0}` (step-length table,
valid indices 0–12).

### Why all four preconditions are needed, and why trig mode is not

- **Per Track** (`+0x8e55 == 1`): only then does `FUN_4009b5c8` do the buggy audio-stride
  read. Normal scale uses pattern `+0x8e54` (a valid small index).
- **Direct** (`+0x48fe == -1`): needed *twice over*. (a) `FUN_4009b5c8`'s soft path
  (`cVar5 != -1 && stepping`) returns **before** the corrupting seed write — only the
  Direct/full-init path reaches it. (b) In `FUN_400a1eea`'s MIDI per-step loop, a Direct
  track hits `if (cVar11 < 1) goto LAB_400a37f0` and **skips the block that would recompute
  `DAT_80006646[track]` from the correct `+0x48f9` source** — so the garbage is never healed.
- **Plays Free**: the gate that lets a MIDI track reach `FUN_4009b5c8` at all (non-PF →
  `FUN_4009b95a` stub).
- **Trig mode**: `FUN_4009b5c8` never reads `+0x48fd`. ONE / ONE2 / HOLD all call
  `FUN_4009b5c8` on the first manual trig of a PF+Direct track → all three corrupt
  `DAT_80006646[track]` identically. ✓ matches fact #2.

### Why "C fires, C# never does"

`FUN_400a1eea` has a second MIDI loop (uVar20 = 8..15, `pcVar34 = &DAT_80006646` incrementing)
whose per-track step-advance gate is:
```c
if (DAT_80006508[track]==1 && DAT_400aba50[DAT_80006646[track]] <= (byte)(subcounter+1)) {
    ... advance to next step, emit its note ...
    if (SCALE_MODE!=0) DAT_80006646[track] = blob[(track-8)*0x8b0 + 0x48f9];   // heal -- but only AFTER an advance
}
```
- Normal: `DAT_400aba50[2] = 6 <= subcounter+1` → true once per 6 ticks → advances, step 2's
  C# fires.
- Bug: `DAT_400aba50[255]` = a huge garbage int → `huge <= (byte)(...)` is **always false**
  → the track never advances past step 1, and the heal line (which needs a successful
  advance) never runs. **Permanent stall after the first note.** The initial C is emitted by
  the manual-trig start path itself; step 2's C# needs this loop, which is wedged.

### The fix — in `FUN_4009b5c8`, not the dispatcher

`FUN_4009b5c8`'s per-track-scale seed must use the MIDI stride/offset for MIDI tracks:
`blob + pattern-block + (param_1-8)*0x8b0 + 0x48f9` instead of `+ param_1*0x91a + 0x51`.
No room to do the different address math in the 18 bytes at `0x4009b6f2`–`0x4009b704`
(and `D3` can't be clobbered — it indexes the destination store at `0x4009b704`), so this
needs a trampoline to a code cave. **Patch not yet drafted.** The old part-2 one-byte
`+0x48fd` patch is **withdrawn** — it addressed the wrong path.

`emu_trigbug.py`'s `scale_evidence()` gives a direct pass/fail for any candidate:
after a press, `DAT_80006646[0]` must be a valid index (0–12), not 255.

### Still worth doing
- Full `FUN_400a1eea` run in the harness for the end-to-end C/C# behavioural repro (the
  static trace above is strong but not executed).
- Confirm the drifting per-track offset for MIDI tracks 1–7 (`blob + track*0x91a + 0x51`).
- A hardware export with Plays Free + Direct + per-track scale + trig mode **ONE** would
  nail fact #2 against the byte layout (all current exports with the bug config happen to
  also be ONE2).


## Session 6 (Claude Code) — the fix: drafted (`tools/patch_trigscale.s`), emulator-validated,
## built into TWO flashable images (A: stock+fix, B: +MAXOLYDIAN mods), reproducible-packaged,
## drift confirmed for all 8 MIDI tracks, and a flash-failure playbook written. Not yet flashed.
## [SUPERSEDED by Session 7: Build A flashed to a real Octatrack MKI 2026-08-28 — fix confirmed, no regression.]

### The patch

Detour + code cave, exactly as Session 5 predicted (no room in place — 18 bytes at
`0x4009b6f2`–`0x4009b703`, `D3` live as the store index at `0x4009b704`).

- **Detour** `0x4009b6f2` (6 bytes, replaces `move.l #0x91a,D0` precisely): `jmp 0x400d7b00`.
  The 3 instructions after it (`0x4009b6f8`–`0x4009b703`, 12 bytes) are left orphaned —
  unreachable, and nothing branches into them: the SCALE_MODE==0 path is the
  `beq 0x4009b704` at `0x4009b6f0`, which lands *past* them with `A0` already set to
  `&blob[pattern-block + 0x8e54]` from `0x4009b6e0` (unchanged Normal-scale behaviour).
- **Cave** `0x400d7b00` (62 bytes, in the `0x400d64da..0x400d7c3c` zero cave `build.py`
  already uses; `patch_arp` ends at `0x400d7224`, so there's a wide gap):
  ```
  moveq #7,D0 ; cmp.l D3,D0 ; blt .midi          ; 7 < track -> MIDI (8..15)
  ; audio (0..7): unchanged -- A0 = D1 + D3*0x91a + D2 + 0x51 ; jmp 0x4009b704
  .midi: D0 = D3-8 ; D6 = 0x8b0 ; D0 *= D6 ; D0 += D2 ; D0 += 0x48f9
         A0 = D1 ; A0 += D0 ; jmp 0x4009b704       ; = blob + pat*0x8ed8 + (trk-8)*0x8b0 + 0x48f9
  ```
  `D6` (pattern index) is dead past `0x4009b6d6`, reused as the multiply scratch — ColdFire
  `muls.l` wants a register source, and `D0` holds the running product. `0x48f9` is folded
  into `D0` with `addi.l` because ColdFire indexed addressing only has an 8-bit displacement
  (`lea (0x48f9,A0,D0)` won't assemble; `lea (0x51,A0,D0)` in the audio arm is fine).
  Assemble: `m68k-elf-as -mcpu=5407` (cfv4), same as every other stub.

### Validation (`tools/emu_trigbug.py`, `scale_evidence()` extended; `FIX_SCALE` patch-dict)

`out/ghidra/emu_trigbug_fix_session6.txt`:

| bank | SCALE_MODE | STOCK `DAT_80006646[0]` after press | FIXED | audio `DAT_8000663e[0]` stock/fixed |
|---|---|---|---|---|
| test1_PFD       | 0 | 2 (valid, `aba50[2]=6`)        | 2 (unchanged — Normal path bypasses the detour) | 0x00 / 0x00 |
| test1_PFD_scale | 1 | **255** (OOB → garbage step len → stall) | **2** (valid, `aba50[2]=6`) | 0x00 / 0x00 |

Also ran the harness with `IMG_PATH` pointed at the real built `out/mainos.bin` (fix
compiled in, no patch dict): `test1_PFD_scale` → `DAT_80006646[0] = 2`, identical to
`test1_PFD`; audio untouched. `python3 tools/build.py` applies cleanly (EXPECT guard
`203c0000091a` at `0x4009b6f2`; cave verified free).

### Open item 3 — DONE. `emu_trigbug.py drift_check()` (`--drift`), log appended to
`out/ghidra/emu_trigbug_fix_session6.txt`.

The buggy `track*0x91a + 0x51` read lands a different amount past each MIDI track's real
scale byte (`(track-8)*0x8b0 + 0x48f9`): drift = `0x91a - 0x8b0 = 0x6a`/track, so the buggy
offset for MIDI trk N is `0x48f9 + 0x28 + N*0x6a` (blob-rel, bank/pat 0):

| MIDI trk | buggy off | correct off | buggy − correct |
|---|---|---|---|
| 0 | 0x4921 | 0x48f9 | +0x28 |
| 1 | 0x523b | 0x51a9 | +0x92 |
| 2 | 0x5b55 | 0x5a59 | +0xfc |
| … | … | … | +0x6a each |
| 7 | 0x88d7 | 0x85c9 | +0x30e |

Emulated what-if (RAM blob forced `+0x48fc=1`/`+0x48fe=0xff` on all 8 MIDI tracks, press
each): STOCK → `DAT_8000663e[8..15]` all 255 (these test tracks are empty so every drifted
offset happens to read 0xff = "no trig"; a populated track would give assorted non-index
garbage). FIXED → every MIDI track gets its own valid `+0x48f9` byte (2). No audio regression.

### Flash prep — DONE. TWO builds, both carrying the identical always-on fix (no PERSONALIZE gate).

**Build A — fix on otherwise-stock 1.40C** (version field untouched, stays `1.40C`):
- `tools/build_trigscale_only.py` (new) applies just the detour+cave to stock →
  `out/mainos_trigscale_only.bin` (72 bytes vs stock: 2 hunks).
- `.syx`: `out/OCTATRACK_OS1.40C_PLAYSFREEFIX.syx` (EFT `-c 3 …` **no `-V`**; checksums ok;
  section 3 decompresses byte-identical; only 0x4009b6f2 / 0x400d7b00 differ from stock).
- `.bin`: `out/OCTATRACK_PLAYSFREEFIX.bin` (`make_bin.py out/elek_pffix.bin`; ELUP ok).
- JSON: `sysex/patches/playsfreefix-r1.json` (2 hunks, `display_version: null`).
- `apply_patch.py -p playsfreefix-r1.json` → sha-identical to the EFT build (`a2f5d5bd…`).

**Build B — fix + the MAXOLYDIAN mods** (= shipped R11 + this fix; R12 was the shelved
bank-paging):
- `tools/build.py` now includes `patch_trigscale` → `out/mainos.bin` (1489 bytes vs stock).
- `.syx`: `out/OCTATRACK_OS1.40C_MAXO_R13.syx` (EFT `-c 3 out/mainos.bin -V MAXOLYDIAN`).
- `.bin`: `out/OCTATRACK_MAXO_R13.bin`.
- JSON: `sysex/patches/maxolydian-r13.json` (31 hunks); `apply_patch.py` default repointed
  here. `apply_patch.py` → sha-identical to the EFT build (`29eec95b…`).

Tooling notes:
- `sysex/gen_patch_json.py` (new) diffs stock vs a built image and emits the hunk JSON +
  hashes; `--trigscale-only` / `--name` / `--display-version keep` for build A.
- `apply_patch.py` now skips `-V` when `display_version` is null/empty (keeps the stock
  version field byte-identical — that's how build A's `.syx` stays "otherwise stock").
- Old `maxolydian-r10.json` left in place but superseded (predates the R11 arp patch too).
- `FLASHING.md`: both builds documented (A/B), plus a step-by-step hardware repro/fix/
  regression test (PLAYS FREE + Direct + Per-Track scale, step-1/step-2 MIDI trigs, manual
  trig, sequencer stopped → stock plays only C, fixed plays C then C#).

### Failure playbook — DONE (`FLASHING.md` §6, and `octamax_handoff_7.md` for the next session)

`FLASHING.md` §6 "If flashing fails, or the flashed OS misbehaves" covers: always-recover-first
(STARTUP MENU → MIDI UPGRADE → stock `.syx`); classify into (a) transfer never completed →
transport, not the patch (slow SysEx, re-copy CF, re-verify the local file with
`elektron-firmware-tool -i` / `bin_decode.py`); (b) flash finished but OS won't boot → flash A
if B was flashed (isolates the fix from the mods); if A also bricks, the fix fails on real
silicon → code change needed; (c) OS runs but fix inactive / regression → re-run the repro,
check A vs B. Plus a "Debugging the fix" checklist (D6 liveness, orphaned bytes, cave
executability at 0x400d7b00 — fallback 0x400d7300, ISA, bisect via a no-op cave).

### Still open

- Flash a build to the real unit and confirm the stall is gone (`FLASHING.md` "Testing the
  MIDI manual-trig fix"). Expect the user to try build A first. Recovery net: hold [FUNC] on
  boot → STARTUP MENU → [TRIG 3] MIDI UPGRADE → send
  `downloads/extracted/OCTATRACK_OS1.40C.syx`.
- (nice-to-have) Full behavioural `FUN_400a1eea` run in the harness — deferred; the function
  is huge (A0 struct live-in, `[A0+0x6632]` word, many globals + sub-step counters, calls
  FUN_4009cf4c/d1e8/e884/33968/4a668). The static trace + `scale_evidence`/`drift_check`
  already pin the mechanism and the fix.
- (nice-to-have) Hardware export with trig mode **ONE** (all bug-config exports so far ONE2).
- The withdrawn part-2 ONE2 oddity — only if the user reports it independently.


## Session 7 (Claude Code) — HARDWARE CONFIRMED. Build A (stock 1.40C + Plays-Free fix)
## flashed to a real Octatrack **MKI** unit; the MIDI manual-trig stall is gone and no
## regression observed. The fix is now hardware-validated.

**2026-08-28** — the user flashed `out/OCTATRACK_OS1.40C_PLAYSFREEFIX.*` (Build A: fix on
otherwise-stock 1.40C, no MAXOLYDIAN mods) to an actual **Octatrack MKI**. Result: the unit
boots normally, OS version still reads `1.40C` (Build A leaves the version field untouched
by design), and the previously-reproducible bug — a Plays-Free MIDI track with trig quant
Direct + pattern scale Per Track stalling after step 1 on a manual trig — **no longer
occurs**. Works without issue; no regression reported.

Notes:
- Elektron ships the same OS 1.40C image for the Octatrack MKI and MKII, so the RE (done
  against the MKII `.syx`) and the fix apply to both. This is the first on-hardware
  confirmation and it happened on an **MKI**.
- Only Build A has been flashed. Build B (`OCTATRACK_OS1.40C_MAXO_R13.*` = fix + MAXOLYDIAN
  mods) is unchanged and still carries the identical fix; its non-fix mods were already
  hardware-confirmed in earlier sessions, but the R13 bundle as a whole has not been
  re-flashed since adding `patch_trigscale`. If flashing B later, the §6 playbook still
  applies (flash A to isolate the fix from the mods).
- The emulator-green-≠-hardware-good caveat is now discharged for the fix itself (Build A).

### Status roll-up

- MIDI manual-trig bug: **root-caused, fixed, built, emulator-validated, and hardware-confirmed
  on MKI (Build A).** Done.
- Deferred nice-to-haves (full `FUN_400a1eea` behavioural harness; a trig-mode-ONE hardware
  export; the withdrawn part-2 ONE2 oddity) remain deferred — none are needed now that the
  fix is confirmed on real hardware.

---

# Session 8 (2026-08-28) — NEW BUG: MIDI-track LFO SETUP knobs transmit CC on the wrong channel

## The bug (Elektronauts thread 87588, reported by the user in 2019 on MKI OS 1.30B)

Editing **SPD / DEP on a MIDI track's LFO SETUP page** makes the Octatrack transmit the
6 LFO CCs (**CC 28–33** = LFO Speed 1-3 / Depth 1-3) on the MIDI channel assigned to the
**twin audio track** (audio track N), not the MIDI track's own channel.
- Without MIDI loopback: stray CC 28–33 on audio track N's channel (confirmed with a MIDI
  monitor by sezare56).
- With loopback: those CCs come back in and drive **audio track N's** LFO depth/speed →
  the audio track audibly cuts out. This is how the user originally noticed it.
- Only the **LFO SETUP** page, only **twin tracks**, not bidirectional, audio/MIDI channels
  must differ. Normal LFO (MAIN) page reportedly unaffected.
Target for our fix: **OS 1.40C** (behaviour assumed to persist; not yet reproduced on 1.40C).

## Manual facts (OS 1.40C MKII manual, `tool-results/otmk2.txt`)

- Audio LFO: **LFO MAIN** page = SPD1-3, DEP1-3 (§11.4.7). **LFO SETUP** page (FUNC+LFO) =
  per-LFO PMTR, WAVE, MULT, TRIG, SPD, DEP (§11.4.8). SPD/DEP are **mirrored** on both pages.
- MIDI LFO MAIN / MIDI LFO SETUP "work just like" the audio ones (§15.4.5/6). MIDI LFO PMTR
  can target the MIDI track's own MAIN-page params (note/vel/len/PB/AT/CC1-10).
- Appendix C.7 (audio CTRL CHANGE MAPPINGS): CC 28–33 = "LFO param #1-6 (Speed 1-3, Depth
  1-3)", flagged **TRN + REC** — but transmit only when PROJECT>MIDI>CONTROL>**AUDIO CC OUT**
  = EXT / INT+EXT (default INT = no send). Explicit opt-in.
- Appendix C.8 (MIDI MODE CTRL CHANGE MAPPINGS): "the auto channel **responds to**…" CC 28–33
  = "MIDI LFO param #1-6" — listed **REC only**. **There is no "MIDI CC OUT" setting.** So per
  the manual a MIDI track has no business *transmitting* CC 28–33 when you touch an LFO knob.

## Agreed fix direction (user, this session)

**Suppress** CC transmission from the MIDI LFO SETUP page's SPD/DEP encoders (rather than
"fix the channel"): the emitted CCs are undocumented, ungated, wrong-channel, and nobody can
rely on them. Kills both the no-loopback noise and the loopback glitch. Verify first whether
LFO MAIN transmits at all / on which channel; if it legitimately does on the MIDI track's own
channel, make SETUP match MAIN instead of pure-suppress.

## RE progress (Ghidra headless, `tools/GhidraLfo*.java`, project already analyzed, 2194 fns)

Load base 0x40000400. `_DAT_80000012` = audio(0)/MIDI(≠0) selector. `DAT_100b14cc` = current
track, `DAT_100b14cf` = displayed pattern, `DAT_80000000`/`80000003` = active track / sounding
pattern, `_DAT_46c82456` = live project blob base, pattern stride 0x18b2.

### Page plumbing — CONFIRMED
- **LFO SETUP page** shared audio+MIDI. Title strings: `"MIDI LFO SETUP"` @0x400b47d5,
  `"LFO SETUP"` @0x400b47da (tail of the same bytes).
- **Page descriptor** @~0x400bc030: `+0x24`=title(0x400b47da), `+0x54`=open FUN_40058390,
  `+0x58`=teardown FUN_40055de0, `+0x5c`=renderer **FUN_400572e8**, `+0x80`=FUN_4003ad8c.
- **FUN_40058390** = page-open. Installs the param table via
  `FUN_400326d4(&DAT_400d37f6 [audio] | &DAT_400d4162 [MIDI], 0xffffffff, &DAT_400bbc72)`.
- **Param-descriptor structs**: audio LFO page @**0x400d37f6**, MIDI LFO page @**0x400d4162**.
  Each holds **12 params**: 0-5 = LFO MAIN (SPD1-3,DEP1-3), 6-11 = LFO SETUP (PMTR,WAVE,MULT,
  TRIG,SPD,DEP). 6-byte labels start at struct+0x16 (0x400d380c / 0x400d4178). Struct also
  has min array @+0x6a, range @+0x9a, and FUN_400a6994 inputs @+0x18a/+0x18e.
- Renderer FUN_400572e8: audio blob offsets 0x90482 / 0x8f072 / 0x8f3e2; MIDI 0x90512 /
  0x8f268 / 0x8f26b. LFO **designer waveform** editors (no MIDI TX): FUN_400381c8 (set step),
  FUN_40037f40 (interp mask), FUN_400383e4 (rotate), FUN_40038148 (invert).

### Encoder-edit path — PARTLY MAPPED
- Main UI event loop = **FUN_40061a94**. Param-page encoder turn = **case '?'** (event 0x40):
  ```
  iVar17 = (pcVar6[2] % 6) + DAT_400a7280[pcVar6[2] / 6] * 6;   // <-- param-index remap table
  uVar10 = DAT_80000000; if (_DAT_80000012 != 0) uVar10 += 8;   // MIDI mode -> track idx +8
  FUN_40054cd8(uVar10, iVar17, delta);                          // apply
  ```
  `DAT_400a7280` (small per-page base table) NOT yet dumped — **next step**.
- **FUN_40054cd8(track, paramIdx, value)** = generic param apply.
  - `page = paramIdx/0x24`, `sub = paramIdx%6`.
  - `if (track < 8)` audio branch: writes blob, **NO MIDI transmit** (only FUN_4009da20).
  - `else` MIDI branch (track-8 = midi slot): writes blob @0x8f162 region, then
    **`FUN_4009eec8(midiSlot, paramIdx, value, 0)`** then `FUN_4009da20(track)`.
- **FUN_4009eec8(midiSlot, paramIdx, value, 0)** = MIDI-track param → live MIDI out. Acts
  ONLY for `paramIdx == 0x12` (PB → 0xE0), `0x13` (AT → 0xD0), `0x14..0x1d` (CC1..CC10 →
  0xB0, CC# from CTRL setup table). Channel = `*(byte)(… midiSlot*0x24 + 0x40171442) - 1 & 0xf`
  = the **MIDI track's own** channel. Does NOT itself handle LFO params (0x1e+).
- Other transmit siblings: **FUN_400438fc**, **FUN_40055008** (both call FUN_4009eec8);
  **FUN_400a14f0** (called from case 'H' with the *audio* active-track index, forces
  `FUN_40054cd8(track+8, 0x14+i, …)` and can emit a raw `chan|0xB0` via FUN_40010bc8).
- MIDI byte-out primitive: **FUN_40010bc8(nbytes, *bytes)**, 59 refs.

### Where the bug most likely is (hypotheses, unproven)
1. `DAT_400a7280[page]` for the LFO SETUP page remaps `iVar17` into the **0x14..0x1d** window,
   so FUN_40054cd8's MIDI branch → FUN_4009eec8 treats an LFO SPD/DEP edit as a CC1..CC10
   send. (Would explain "CC" but not obviously "CC 28-33" / "audio channel".)
2. A transmit sibling (FUN_400438fc / FUN_40055008 / FUN_400a14f0) resolves the channel from
   an **audio-track** table indexed by track number while the LFO SETUP edit is in flight.
3. The "audio channel" symptom = FUN_4009eec8 style code indexing `0x40171442` with the wrong
   base when `_DAT_80000012`/`DAT_80000002` (part) state points at the twin audio track.

### Immediate next steps
1. Dump `DAT_400a7280` (bytes) + the param-id ranges the LFO MAIN vs LFO SETUP encoders emit.
2. Decompile FUN_400438fc, FUN_40055008, FUN_4009da20 — find the CC-28..33 / audio-channel path.
3. Reproduce in the emulator (adapt `tools/emu_*`): drive case '?' with an LFO-SETUP paramId
   for a MIDI track whose twin audio track has a different channel; capture FUN_40010bc8 args.
4. Then design the suppression patch (likely a guard in FUN_40054cd8's MIDI branch, or in
   FUN_4009eec8, skipping transmit when paramIdx is an LFO param / page == LFO SETUP).

Scratch dumps: `scratchpad/lfo{2..13}.txt`. Manual text: `tool-results/otmk2.txt`.

---

## Session 8 continued — LIKELY ALREADY FIXED IN 1.40C (emulation-backed)

### The transmit mechanism, fully traced
- **FUN_400409f4** = polled "AUDIO CC OUT" transmitter (runs off HW timer `DAT_fc078xxx`).
  For each set bit `ch` in `_DAT_46c7e0de` (a **MIDI-channel** mask), for each set param bit
  in `[ch*0x10 + 0x46c7d7d8]`, transmits `CC (col*0x20 + bit) = value[ch*0x80 + 0x46c7bf2c + ...]`
  with status byte `(ch | 0xB0)`.
- **FUN_40033e3c(track, ccNum, value)** = the enqueue. Gated by `DAT_8000004a & 2` (= AUDIO
  CC OUT enable; INT clears it). Resolves `ch = *(char*)(0x8000003f + track)` — the **audio
  track's assigned MIDI channel** — skips if that channel collides with a MIDI track's
  channel, else stores value at `[ch*0x80 + ccNum + 0x46c7bf2c]` and sets `_DAT_46c7e0de` bit `ch`.
- **CC number for a param edit = `enc + 0x10 + DAT_400a72a8[_DAT_460d1684]*6`.** Empirically
  (emu sweep) `_DAT_460d1684`: 0→playback CC16-21, **1→LFO CC28-33**, 2→amp CC22-27,
  3→FX1 CC34-39, 4→FX2 CC40-45. (Matches manual Appendix C.7.)

### The shared SETUP-page encoder handler
- **FUN_400554e0(block)** (called by every param-SETUP page-open) does
  `_DAT_460d1684 = block; FUN_400326d4(def, 0, &DAT_400c085a)` — installs the encoder handler
  table @0x400c085a: encoders 0-5 → **FUN_40055008**, encoder 6 → FUN_4004eb24.
- Page-open → block: LFO SETUP (FUN_40058390) → **block 1**; PLAYBACK/MIDI-NOTE → 0;
  MIDI-CTRL1/EFFECT1 → 3; MIDI-CTRL2/EFFECT2 → 4.
- **FUN_40055008(enc, delta)**:
  - `if (_DAT_80000012 == 0)` (**audio mode**): writes param, then
    `FUN_40033e3c(DAT_100b14cc, enc + 0x10 + DAT_400a72a8[_DAT_460d1684]*6, value)`
    → for block 1 that's **CC 28-33 on DAT_100b14cc's audio channel**. (Correct AUDIO CC OUT.)
  - `else` (**MIDI mode**): `FUN_4009eec8(DAT_100b14cc, _DAT_460d1684*6 + enc, value, 0)`.
    FUN_4009eec8 only emits for param 0x12..0x1d (PB/AT/CC1-10). For the LFO block that's
    param **6..11 → FUN_4009eec8 does nothing.**

### Emulation result (`tools/emu_lfocc.py`, unicorn)
Drove FUN_40055008 for every `enc`×`_DAT_460d1684`×{audio,midi}. Faithful:
- audio + block 1 → `FUN_40033e3c(0, 28..33, val)`  ✅ audio CC out fires (expected).
- **midi + block 1 → FUN_4009eec8(0, 6..11, val, 0) → nothing transmitted.** ✅ no bug.
- midi + block 3/4 (CTRL pages) → FUN_4009eec8 param 18..29 → PB/AT/CC1-10 DO transmit
  (correct — that's the point of the CTRL pages).
Also swept all 13 `FUN_40033e3c` callers: only FUN_40055008 ever uses CC 28-33; the rest
use CC 0x31-0x7f (mute/solo/cue/arm/scene). **No page-enter bulk LFO-CC dumper exists.**

### Conclusion
On **OS 1.40C** there is no code path by which editing a MIDI track's LFO SETUP SPD/DEP
transmits CC 28-33 — the `_DAT_80000012` (MIDI-mode) gate in FUN_40055008 sends MIDI-track
edits down the FUN_4009eec8 path, which ignores LFO params. The 2019 report was against
**MKI OS 1.30B**; this looks **already fixed** (most plausibly: the `_DAT_80000012` guard
was added to FUN_40055008 in the 1.31/1.40 line). Could not obtain a 1.30B binary to diff.

### Recommended confirmation (hardware, user has the gear)
On the real Octatrack (1.40C): PROJECT>MIDI>CHANNELS set MIDI trk1→ch1, audio trk1→ch9;
PROJECT>MIDI>CONTROL set AUDIO CC OUT = EXT (or INT+EXT); MIDI monitor on the OUT.
MIDI mode → MIDI track 1 → LFO SETUP page → turn SPD / DEP. If **no** CC 28-33 appear on
ch9, the bug is fixed and this task is closed. If they DO appear, the repro is live and the
fix target is the missing `_DAT_80000012` guard / a second path — resume from here.

### Residual (if pursuing further)
- Emulate the full [MIDI]→open MIDI-LFO-SETUP→turn-SPD chain (page-open FUN_40058390 +
  event loop) rather than calling FUN_40055008 directly, to rule out an open-time emit.
- Obtain OT OS 1.30B/1.31 and diff FUN_40055008 to confirm what the fix was and when.

---

## Session 9 (2026-08-28) — Feasibility: "soft" audio-track mute (decay + FX tails, like trig mutes)

Goal: make FUNC+TRACK audio mute enter the amp release phase and let delay/reverb tails ring,
instead of the instant hard cut. Longstanding Elektronauts wish. User's hypothesis: the soft
behaviour already exists in the arranger-mute path. Scripts: `tools/GhidraMute{1..7}.java`;
dumps `out/ghidra/GhidraMute{1..7}_session9.txt`.

### Map of the mute subsystem (verified by decompile + byte-search for address constants)
- **Audio-track mute mask** = 8-bit `_DAT_460fab40` (bit t = track t muted). Managed by a
  self-contained "mute mode / QUICK MUTE" module at `0x40083480..0x40083f??`.
  - `FUN_40083480` → getter (used by LED refresh FUN_40030a6c/c60/e6c).
  - `FUN_40083ab4(keycode,phase)` → **mute a track**: `uVar1 = keycode-0x10`; `_DAT_460fab40 |=
    1<<uVar1`; if phase==1 → `FUN_400836d8()`.
  - `FUN_40083e40(keycode,phase)` → **unmute a track**: `_DAT_460fab40 &= ~(1<<...)`; → `FUN_400836d8()`.
  - `FUN_400836d8(arg,phase)` = re-evaluate all 8 voices after a mask change (phase 1=mute,0=unmute).
  - `FUN_40083544(track,phase)` = single-track version; **the arranger calls THIS** (see below).
- **Byte-search confirms `0x460fab40` is referenced ONLY inside `0x40083482..0x40083e86`.**
  The DSP frame builder / mixer / trig→voice path never read the raw mute mask.
- **Derived masks actually consumed by the audio engine**:
  - `_DAT_46c803d4` : low byte = CUE mask, high byte = MUTE mask. Also drives MIDI CC-out
    (FUN_40033e3c CC 0x34 cue / 0x35 mute / 0x36 mute-all, via FUN_4005e294 & FUN_4000e79c).
  - `_DAT_46c7ff64` : "silenced in MAIN out" mask (same <<8 layout). Read by `frame_builder`
    (FUN_4000c8a4 @0x4000c93e) and a sibling voice-cmd gate @0x4000b936, plus 0x4000ac44.
  - `FUN_400834d8(_,phase)` = the mute-page **commit** handler (ptr pair @`0x400d15e4`):
    phase 1 → `_DAT_46c803d4 |= _DAT_460fab40<<8; _DAT_46c7ff64 |= _DAT_460fab40<<8; CC 0x35`;
    phase 0 → `_DAT_46c7ff64 = 0`.
- In `frame_builder` the mute mask is applied by **gating pending voice commands**: `btst #8,cmd`
  then `... & ~_DAT_46c7ff64 ...`, and it *synthesises* a command `uVar10 = (cmd & 0xf000) |
  per_track_byte(0x800017f6) | 0x210` into the per-track slot — i.e. it forces flag 0x10 (the
  stop/one-shot bit) while **preserving the existing mode nibble**; it does not force 0xf000.

### The "soft release" machinery already exists (and is used elsewhere)
- `FUN_40008f84(track)` : start a graceful release — sets `DAT_8000184a |= 1<<t` (voice state
  "2" = releasing, per FUN_40000ee0), writes release param `0x2d`, calls FUN_4000672c.
- Consumed by `FUN_400068e4` (control-rate voice updater): on the release bit it writes
  **ramp targets** `0xf0000000/0xf0000000/0xf0000000` to voice+0x24/0x28/0x2c and `0xe0000000`
  to voice+0x40 (envelope-segment slope registers — a fast *ramp*, not a zero), then
  `FUN_40005c7c`/`FUN_40095ee0`/`FUN_4000432c` push it to the DSP frame amp.
- `FUN_40008fe4(track)` wraps it (also sets `DAT_8000184c=0xff`); `FUN_40008fe4(0xffffffff)` =
  "release ALL voices", called from transport STOP and every CHANGE SET / SYNC TO CARD flow
  (FUN_40063660/778/930/e28, FUN_4006437c, FUN_4006cc54) — so a clean fade on state change.
- `FUN_40083a7c` already does exactly the wanted thing (`for t in muted: FUN_40008fe4(t)`) but
  has **no discoverable caller** (dead, or dispatched — worth chasing).

### What FUN_400836d8 / FUN_40083544 actually do on mute
For **FLEX (machine 0) / STATIC (1)** with the **default** mute settings (mute-quantise/retrig
nibble `uVar6 == 0`): muting a *currently-sounding* voice sends **no stop/release command at
all**. Only machine types ≥2 (THRU/NEIGHBOR/PICKUP) get a `0x8040` command. The re-eval logic
only fires when a mute-retrig nibble is set (the STARTS SILENT / ONE / ONE2 / HOLD option).
→ The instant silence of a sustained sample under mute therefore comes from the
`_DAT_46c7ff64`/`_DAT_46c803d4` gate in the frame builder + whatever the DSP does with the
0x?10 command — **not** from the ColdFire voice logic.

### Arranger vs FUNC+TRACK
The arranger row-command interpreter is `FUN_40061a94` (switch over a command-byte stream;
handles TEMPO/SCENE/MUTE/… and sets the mute-retrig nibbles `_DAT_460d179a/9e/a2`). Its MUTE
command calls the **same `FUN_40083544`**. → There is **no separate softer arranger-mute path**
at the voice level. User's hypothesis not confirmed. The only arranger-specific knobs are the
shared mute-quantise nibble and the retrig-on-unmute mode.

### Feasibility verdict
Plausible, scoped like the Bug-1 fix, with ONE real unknown that is DSP-side:
1. The release primitive we want (`FUN_40008f84`, ramp targets `0xf0000000`) **already exists**
   and is call-ready. A detour at `FUN_40083ab4` / `FUN_400836d8` could, on mute (phase 1),
   additionally call `FUN_40008f84(track)` for FLEX/STATIC sounding voices; on unmute do nothing.
2. **Open question**: is the FX send tapped **pre** or **post** the mute gain? If pre (send is a
   fixed tap off the voice pre-mute-level), then killing the amp with a release already lets the
   tail ring — easy win. If post, tails die regardless of amp release and we'd need a frame/mixer
   routing change (harder; frame_builder is dense ColdFire that r2 mis-decodes, Ghidra partial).
3. Also need: does the DSP's 0x?10 "stop" do an instant cut or honour an envelope release? DSP
   program is DSP56300, located **inside the MAIN OS image** at ~`0x400e2000..0x4010fdf0`
   (~188 KB) — patchable in principle but needs a DSP56300 disassembler.

### Next steps if pursued
- Emulate (unicorn, like emu_trigbug) the mute of a sounding FLEX voice: watch voice+0x20..0x44
  and the frame amp slot for that track — instant 0 vs ramp — and see if a separate "send level"
  slot stays non-zero.
- Locate the per-track MAIN-mix and SEND-mix level words in the DSP frame double-buffer
  (`_DAT_800000e0 * 0x180` / `* 0x200` regions filled at the end of frame_builder) and check
  which the mute mask zeroes.
- Chase callers of `FUN_40083a7c` (already the desired loop).
- If pre-send tap confirmed: prototype a detour `FUN_40083ab4`→cave that calls `FUN_40008f84`
  on mute for sounding FLEX/STATIC, gated by a PERSONALIZE flag ("SOFT MUTE").

### Emulation results — `tools/emu_mute.py` (unicorn), log `out/ghidra/emu_mute_session9.txt`
Drove the real handlers against a synthetic "track 0 = sounding FLEX voice" pre-state.

**S1 — `FUN_40083ab4(0x10, 1)` (real FUNC+TRACK mute), FLEX voice sounding, default settings:**
the ONLY write is `_DAT_460fab40 = 0x01`. No voice command, no amp write, `_DAT_46c803d4`/
`_DAT_46c7ff64`/`DAT_8000184a` untouched. `FUN_400836d8` runs but is a no-op for FLEX/STATIC.
**S2 — arranger `FUN_40083544(0, 1)`** for FLEX / STATIC / NEIGHBOR: no writes at all. Confirms
the arranger mute path is identical (and equally inert on a sustained voice).

**S3/S5 — the release is one flag away.** `FUN_40008f84(t)` sets `DAT_8000184a |= 1<<t` (release
*state*) but NOT the ramp trigger. The ramp trigger is **`DAT_8000184c |= 1<<t`**. With that bit
set, the very next `FUN_400068e4` tick (the control-rate voice updater, already running every
audio frame) does, for that track:
```
pcurs[t]+0x24 = 0xf0000000   pcurs[t]+0x28 = 0xf0000000   pcurs[t]+0x2c = 0xf0000000
pcurs[t]+0x40 = 0xe0000000        (envelope-segment slope regs = steep negative = release)
-> FUN_40005c7c(t, pcurs[t], level, 0, 1, 0)
   -> FUN_40095ee0(0x80+t, level, ...) -> FUN_40099090(vframe[0x80+t], level, slope)
      writes AMPseg[0x80+t] +0x114=target(clamped >=0x2d0 floor) +0x118/+0x11c/+0x120=ramp
```
i.e. it ramps the **AMP stage** (per-track voice `vframe[0x80+t]`, `pcurs` struct base 0x80004f1c
stride 0x54) down to the 0x2d0 floor. That's upstream of the track insert-FX + mix routing.
(`FUN_40008fe4(t)` = `FUN_40008f84(t)` + `DAT_8000184c = 0xff`; that's why transport STOP /
CHANGE-SET, which call `FUN_40008fe4(-1)`, fade cleanly. `FUN_400972fc` sets a single bit
`DAT_8000184c |= 1<<t` for PICKUP.)

**S6 — the hard mute gate is NOT an amp ramp.** Pushing `_DAT_460fab40<<8` into
`_DAT_46c803d4`/`_DAT_46c7ff64` (via `FUN_400834d8`, the mute-page commit) then ticking
`FUN_400068e4` produced *no* release fingerprint — the instant silence must be the
voice-command injection in `frame_builder` (`(cmd & 0xf000) | ... | 0x210`, flag 0x10) and/or
the DSP's handling of it. So the two mechanisms are independent stages:
`AMP release` (pcurs/vframe, ramped, pre-FX)  vs  `mute gate` (voice-cmd/DSP, instant).

### Feasibility verdict (updated) — GREEN for a small detour patch
The wanted behaviour = **on mute, run the AMP release instead of relying on the instant gate**.
Emulation shows the release machinery is complete, already ticking, and triggered by one bit.

**Proposed fix**: detour `FUN_40083ab4` (mute-set; and/or `FUN_40083e40`/`FUN_400836d8`) → code
cave that, on phase==1, for each newly-muted track whose voice is sounding
(`(&DAT_800049d8)[t*0xA8+1] != 0`) does:
  `DAT_8000184c |= (1 << t);`  and  `FUN_40008f84(t);`
and (open design choice) suppress the frame-builder's injected stop for those tracks so the
sample tail + its FX feed decay naturally rather than being cut mid-release. Gate the whole
thing behind a PERSONALIZE flag ("SOFT MUTE"), same pattern as the other MAXOLYDIAN mods.
Unmute path unchanged (the existing STARTS-SILENT / ONE / ONE2 / HOLD logic still applies).

**Still unverified (needs DSP RE or hardware):** whether suppressing the injected stop is even
necessary — if the DSP treats `0x?10` on an already-releasing voice as "let the envelope
finish", the amp-release alone may be enough and no frame-builder change is needed. The DSP
program is DSP56300 at ~`0x400e2000..0x4010fdf0` inside the MAIN OS.

**Recommended validation before writing the patch:** extend `emu_mute.py` to also drive a few
frames of `frame_builder` (FUN_4000c8a4) with the mute mask live + the `DAT_8000184c` bit set,
and confirm the per-track `framelvl_46c938d4[t]` / `vframe[0x80+t]` amp actually ramps (not
snaps) and that the injected `0x?10` command doesn't zero it first.

### Frame-builder emulation — `emu_mute.py` scenario 7 (enter the per-track loop at 0x4000c87c)
The real frame-builder task is `FUN_4000b8f0` (5068 B, no callers → kernel/ISR-dispatched, full
decompile fails). Its per-track command-resolution loop is 0x4000c87c → 0x4000ca94 (then the
EMAC frame-assembly tail, un-emulatable). Drove just the loop with a still-sounding *sustained*
voice on track 0 (`(0x800017b8)[t]!=0`, `refresh_46c7faa4[t,0]` bit 0x20 set):

| pre-state | result for trk0 |
|---|---|
| unmuted | emits refresh cmd `0x2210` into `RESOLVEDCMD_46104d26[0]` |
| `_DAT_46c7ff64` bit8 set (force-mute) | emits the **same** `0x2210` — voice kept alive; the cut is the DSP **output** mute (post-FX) applied from `_DAT_46c7ff64` elsewhere in the frame |
| `_DAT_8000184e` bit8 set, `46c7ff64` clear | **clears `refresh_46c7faa4[t,0]`, emits nothing** — the voice just stops being fed and decays naturally |

So the two silencing stages are now fully characterised:
- `_DAT_8000184e` (silenced-set) → "stop refreshing" → natural decay, no injected stop.
- `_DAT_46c7ff64` (main-out silence) → DSP post-FX output mute → **instant, kills FX return too**
  = the behaviour the community wants gone.
- `DAT_8000184c` bit → `FUN_400068e4` AMP-envelope release ramp (pre-FX).

### Refined fix design — detour `FUN_400836d8` (the common apply-mute fn)
`FUN_400836d8` is called by every mute path (plain FUNC+TRACK via `FUN_40083ab4`; the
CUE/MUTE/SOLO key handlers `FUN_40030a6c/c60/e6c`; arranger via `FUN_40083544`'s sibling).
Detour it (behind a PERSONALIZE "SOFT MUTE" flag) so on phase==1, for each newly-muted track
`t` whose voice is sounding (`(&DAT_800049d8)[t*0xA8+1] != 0`) and machine is FLEX/STATIC:
```
DAT_8000184c   |= (1 << t)          ; kick the AMP release (FUN_400068e4 ramps vframe[0x80+t])
_DAT_8000184e  |= (1 << (t + 8))    ; stop the sustain-refresh -> looped samples wind down
```
and **suppress the `_DAT_46c7ff64` bit `(1<<(t+8))`** for those tracks (clear it after the
normal mute code sets it, or gate the setter) so the track's FX/mix output stays open and
delay/reverb tails ring out while the amp decays. Unmute: clear the `_DAT_8000184e` bit and let
the existing STARTS-SILENT/ONE/ONE2/HOLD re-trig run.

Open refinements for patch dev (not blockers): (1) `_DAT_46c7ff64` also carries the CUE mask
and drives MIDI mute-CC-out — make sure clearing the mute bit doesn't disturb cue or the CC.
(2) `FUN_40030a6c/c60` also XOR the mute state into the pattern blob (`blob+0x28/+0x30`) and
toggle `_DAT_460d10d4/d8` — confirm SOFT MUTE interacts cleanly with a saved/recalled pattern.
(3) find where `_DAT_46c7ff64`/`_DAT_46c803d4` actually get set from `_DAT_460fab40` on the
plain path (`FUN_400834d8` is the QUICK-MUTE commit; the plain path's setter is likely inside
`FUN_40030984` / `FUN_400839dc` — decompile those next).

### emu_mute.py scenario 8 — the decay honors the AMP-envelope RELEASE knob
There are **two** independent "stop a voice" primitives and they behave differently:

| primitive | set by | mechanism | fade shape |
|---|---|---|---|
| `DAT_8000184a` (bit/track) | `FUN_40008f84(t)` | frame_builder @`0x4000bd3c` reads it, OR's **bit 0x10 (note-off/gate-release)** into the voice command, then clears the bit. Same flag as a STATIC-machine note-off. | **DSP runs the voice's AMP-envelope RELEASE stage → the user's REL setting is honored.** `FUN_400068e4` writes *no* fixed slope for this path (emu-confirmed). |
| `DAT_8000184c` (bit/track) | `FUN_40008fe4(t)` / STOP / CHANGE-SET | `FUN_400068e4` writes fixed `0xf0000000` envelope slopes | hard ~ms declick fade, **AMP env ignored** |

So the SOFT MUTE detour must use **`FUN_40008f84(t)`**, *not* `DAT_8000184c`. Then muting a
sounding track = a note-off: the sound decays over its AMP RELEASE time (short REL → quick
fade, long REL → long fade), and delay/reverb tails ring from the FX buffers throughout.

**Watchdog caveat:** `FUN_40008f84` writes `0x2d`(=45) to `relparam_46c7dfba[t]`, a per-track
frame countdown (decremented in the `FUN_40052290` per-frame loop). When it reaches 0, a voice
still in release state 2 is force-freed (`FUN_40000ee0(t)==2` → `FUN_40006820(t)`). So a REL set
to "infinite"/very-long will NOT sustain forever after a mute — it is cut at ~45 control frames
(exact ms = 45 × audio-control-frame period, not yet measured; likely tens–low hundreds of ms).
The FX tail is unaffected (it lives in the FX buffers). If genuinely-infinite mute-sustain is
wanted, the same detour can also skip re-arming / neutralise that watchdog for soft-muted
tracks — a one-line addition.

Unmute semantics with this design (user-confirmed choice = "trig mute"): the voice is released
and then freed, so there is nothing playing underneath — **the track returns from the next
sequencer trig**, not mid-sample. (Stock OT mute resumes mid-sample because it keeps the voice
alive under a DSP output-mute; SOFT MUTE deliberately does not.)

### emu_mute.py scenarios 9 & 10 — CORRECTION to S1 + stock-mute command
S1 was **under-seeded**: the real MUTE key handler sets a mute-quantize word
(`_DAT_460d10d4`/`d8`/`d0` = 1, three modes) *before* calling `FUN_400836d8`, and S1 left those
at 0, which is why `FUN_400836d8` looked inert. With the quantize word set:

- `FUN_400836d8` (FLEX, mute, sounding) writes a **voice command to the `46c7e9fa` mailbox**:
  `0x1040 / 0x2040 / 0x4040` (bit `0x40` + the quantize nibble `<<12`). STATIC gives
  `0xa040`, or `voice_cmd(t, 0x80, 1)` (a *restart*) in the branch where the pending nibble
  already matches the voice's current one.
- Ran all three real key handlers (`FUN_40030a6c` / `c60` / `e6c`) end-to-end against a
  sounding FLEX voice. **None of them set `_DAT_46c7ff64`, `_DAT_46c803d4`, `_DAT_8000184a`,
  `_DAT_8000184c` or `_DAT_8000184e`.** Their only voice-engine effect is the `0x?040` mailbox
  write via `FUN_400836d8`.

So on the **plain FUNC+TRACK path**, `_DAT_46c7ff64` (the DSP output mute) is apparently **not
set at all** — the silencing is the `0x?040` mailbox command (bit `0x40`) resolved by the
frame builder / DSP. `_DAT_46c7ff64` is set by the **QUICK MUTE screen commit** (`FUN_400834d8`)
and the CUE/solo focus path (`FUN_4005e294`), not by track-key mute.

**Open (decides the exact patch):** what does the DSP do with a `0x?040` (bit-0x40) command —
instant cut, or deferred-to-quantize then cut, and does it kill the FX return? Bit `0x40` vs
bit `0x10` (note-off) vs `0xf000` (hard stop) semantics still not disassembled (DSP side).

### Revised patch plan
Two candidate detour strategies, to decide by building + emulating:
1. **Augment**: keep the stock `0x?040` command, additionally call `FUN_40008f84(t)` +
   `_DAT_8000184e |= 1<<(t+8)` for sounding FLEX/STATIC. If the stock command turns out to
   already be release-like (deferred), this alone may give the wanted behavior.
2. **Replace**: in the `FUN_400836d8` FLEX/STATIC mute branch, swap the `mailbox = uVar6|0x40`
   / `FUN_40005178(t, uVar6|0x10, 1)` for `FUN_40008f84(t)` (+ the 8000184e bit), so the voice
   gets a clean note-off instead of the `0x40` command.
Both behind a PERSONALIZE "SOFT MUTE" flag. Next step: assemble a minimal detour (strategy 1),
run it in emu_mute.py watching the `46c7e9fa` mailbox + frame output + amp segments, iterate.

### PROTOTYPE BUILT — `tools/patch_softmute.s` (strategy 1, augment) + emu_mute.py S11
Detour: `0x400836d8` (`FUN_400836d8` entry) ← `jmp 0x400d7b40` + nop (8 B, covers the
`lea (-0x3c,SP),SP` + `movem.l {D2-D7,A2-A6},(SP)` prologue). Cave at **0x400d7b40** (242 B,
fits the 0x400d7b3e..0x400d7c3b free tail past `patch_trigscale`; 9 B spare). Gate byte
**0x800000dc** (next free PERSONALIZE word after 0xd4/0xd8), 0 = stock.
Cave logic: save d0-d7/a0-a6; if gate off or phase∉{0,1} → run displaced prologue, resume at
`0x400836e0`. phase 0 (unmute): `SILENCED &= ~((~mask & 0xff) << 8)`. phase 1 (mute): for each
track in the mask that is FLEX/STATIC (`blob +0x8f385 ≤ 1`) and sounding
(`FUN_40000e50(t)`→`*(a0+1) != 0`): `FUN_40008f84(t)` + `*(u16)0x8000184e |= 1<<(t+8)`.

emu_mute.py S11 (stock vs patched, FLEX + STATIC):
- stock  → only `mailbox_46c7e9fa[0] <- 0x2040` (FLEX) / `0xa040` (STATIC).
- patched → same mailbox write **plus** `FUN_40008f84(0)` runs: `DAT_8000184a=0x01` (note-off
  armed → DSP AMP RELEASE), `relparam_46c7dfba[0]=0x2d` (45-frame watchdog), and the cave
  writes `_DAT_8000184e=0x0100`. Detour completes cleanly, no crash.
- unmute round-trip: `_DAT_8000184e` 0x0100 → 0x0000. ✓
Gate verified: `softmute=False` runs are byte-identical to stock.

**What emulation can't decide (needs a hardware flash):** whether the retained stock `0x?040`
command still hard-cuts on top of the note-off (→ switch to "replace": null the
`mailbox = D3|0x40` / `FUN_40005178(t, D3|0x10, 1)` stores in the FLEX/STATIC branches), and
whether the FX tail audibly survives. Build A candidate = stock 1.40C + this detour only.

### FLASHABLE BUILD — `tools/build_softmute.py` → "Build C" (2026-08-28)
Stock 1.40C + `patch_trigscale` (Bug 1, always-on, **bytes identical to `build_trigscale_only.py`**
— verified by diff) + `patch_softmute` (**always-on**, assembled with `--defsym ALWAYS_ON=1`
which drops the `tst.b 0x800000dc` gate; gated cave = 242 B, always-on cave = 232 B).
Caves: patch_trigscale @0x400d7b00 (62 B), patch_softmute @0x400d7b40 (232 B) — adjacent, no
overlap, end 0x400d7c28 < free-zone end 0x400d7c3c. 272 bytes changed vs stock, only at
`0x400836d8` (8-B detour) + `0x4009b6f2` (trigscale detour) + the two caves.
Outputs: `out/mainos_softmute.bin`, `out/OCTATRACK_OS1.40C_SOFTMUTE_PFFIX.syx` (622742 B,
version field stays "1.40C", EFT checksum + round-trip OK), `out/OCTATRACK_SOFTMUTE_PFFIX.bin`
(445820 B, CF-card path). Not yet hardware-flashed. FLASHING.md "Build C" + "Testing SOFT MUTE".

### V1 augment FLASHED → hard cut still won (user, 2026-08-28). V2 = REPLACE.
emu_mute.py check: after V1 (note-off + `_DAT_8000184e` bit + stock `0x?040` left in place),
frame_builder's per-track loop still resolves `RESOLVEDCMD[t] = 0x2040` → the stock deferred-mute
command reaches the DSP and hard-cuts. So the note-off never gets a chance.

**patch_softmute V2** (`tools/patch_softmute.s` rewritten): now *deletes* the stock command.
The detour is a `jmp`, so `(0,SP)=caller_ret` on entry. When there is work to do it saves
`caller_ret`, overwrites `(0,SP)` with `&post`, runs `FUN_400836d8`'s body (which still writes
`mailbox[t] = 0x?040`), and the body's `rts` then lands in `post`, which zeroes `0x46c7e9fa[t]`
and `0x800018be[t]` for every handled track and `jmp`s to the real `caller_ret`. Net for a
soft-muted sounding FLEX/STATIC track: only `FUN_40008f84(t)` (note-off) + `_DAT_8000184e` bit
reach the engine; the hard-cut command is gone. Mute mask (`_DAT_460fab40`) is untouched, so
the mute is remembered; LED / getmask paths see the real mask.
emu S11 (V2): stock → `RESOLVEDCMD[0] = 0x2040`/`0xa040` (hard cut). patched → `46c7e9fa[0]=0`,
`8000184a=0x01`, `460fab40=0x01`, `RESOLVEDCMD[0] = 0xeeee` (nothing emitted → voice decays).
Always-on flash cave (GATE byte = 0) verified: FLEX + STATIC fire, not-sounding tracks don't,
no crash. Cave moved to **0x400d7400** (330 B; the 0x400d7b40 slot ran past the free-zone end).

### Rebuilt Build C (V2)
`out/OCTATRACK_OS1.40C_SOFTMUTE_PFFIX.{syx,bin}` — caves patch_softmute @0x400d7400 (330 B) +
patch_trigscale @0x400d7b00 (62 B); detours 0x400836d8 (8 B) + 0x4009b6f2 (18 B); your bug
fix bytes still identical to `build_trigscale_only.py`; version field "1.40C"; EFT round-trip OK.
**Still not hardware-tested.**  If V2 still hard-cuts on hardware → the DSP note-off (bit 0x10)
does not produce a release for a FLEX one-shot mid-playback, and the fix has to force the AMP
envelope into its RELEASE segment directly (a DSP-frame `vframe[0x80+t]` / `pcurs` manipulation).

### V2 FLASHED → still hard-cuts, BOTH FLEX and STATIC (user, 2026-08-28).
So neither the stock `0x?040` command (deleted by V2, emu-confirmed) nor the note-off is the
operative mechanism. The FUNC+TRACK mute silences the track through a path not yet found —
candidates: (a) `_DAT_46c7ff64` DSP output mute set somewhere I stubbed; (b) the pattern/kit
param repush — `FUN_40030a6c/c60/e6c` XOR an 8-byte region at `blob + pat*0x8ed8 + trk*0x91a
+ 0x30` with `_DAT_460d10e2/e6` then call `FUN_40027e00` (repush to audio engine) — this path
was always stubbed in emu and doesn't decompile; (c) the FUNC+TRACK key path might route
through `FUN_40083ab4` (qmode 0) so `FUN_400836d8` emits *nothing* and the `0x?040` analysis
was a red herring for that entry point. `FUN_40027e00` has 258 refs; `_DAT_460d10e2` refs
cluster at `0x4002ea..0x40030` (FUN_4002exxx — the kit/scene param engine).

### DIAGNOSTIC D1 FLASHED → still hard-cuts, tails still die (user, 2026-08-28).
`build_softmute.py --d1` = patch deletes ONLY the stock `0x?040` mailbox command, nothing else.
Deleting it changed nothing → **the `FUN_400836d8` / `0x?040` mailbox command is NOT the mute
mechanism.** The whole 12-scenario `emu_mute.py` line of investigation was chasing the wrong
code path. Files `out/OCTATRACK_OS1.40C_SOFTMUTE_D1_PFFIX.{syx,bin}` kept (harmless; MIDI fix
intact).

### The ACTUAL mute mechanism (found via minimal-stub emu of FUN_40030c60)
FUNC+TRACK mute of an audio track runs `FUN_40030c60` (0x40030c60; a6c/e6c are the sibling
CUE/SOLO handlers). On phase 1 it:
1. `puVar1 = blob + pat*0x8ed8 + trk*0x91a + 0x28` — an **8-byte per-track FLAGS field** in the
   audio-track parameter block (the 0x91a-stride block).
2. `*puVar1 ^= _DAT_460d10e2 ; puVar1[1] ^= _DAT_460d10e6` — toggle the MUTE bit(s). The mask
   `_DAT_460d10e2/e6` is dynamic, owned by the **SCENE / parameter-override engine**
   (`FUN_4002exxx` cluster) — it is 0 in a bare emu call, set up by whatever enters "mute
   context". The MUTE-LED routine at ~0x4002ed00 lights the LED when
   `(_DAT_460d10e2 & param[+0x28]) | (_DAT_460d10e6 & param[+0x2c]) != 0`.
3. mirror to RAM `0x10016176 + pat*0x8ed8 + trk*0x91a` (8 B).
4. set dirty flags `*(blob + 0x9b332) = 1`, `_DAT_100f8598 = 1`.
5. `FUN_400836d8()` — the `0x?040` cosmetic quantize command (proven irrelevant by D1).
An **async param-sync task** (polls `_DAT_100f8598` / `+0x9b332`, 461 refs) then repushes the
whole track param block to the audio engine → the mute flag is applied, instantly, post-FX
(kills the send/return → no tail).

### Scope reassessment — this is NOT a Bug-1-sized detour
The mute is a **scene/parameter-override flag** (`FUN_4002exxx`, the SCENE engine — COVERAGE.md
marks Scenes "⬜ untouched, the OT's flagship feature") toggled in the track param block and
repushed by an async sync to the audio engine, which applies it in the (non-decompiling)
ColdFire frame-fill and/or the DSP.  Making it "trig-mute" style needs RE of the scene engine
+ the audio param-sync + likely the DSP mute-flag handling — a multi-session effort in the
hardest, least-decompilable subsystem, with an uncertain payoff (may bottom out at "the DSP
does it").

### V3 — narrowed goal: "mute = a per-track STOP" (user's chosen target)
User: *"the same behavior that occurs with a single stop command … sample audio cuts, but the
fx tails still ring."*  So: don't try to make the dry signal decay musically — just do what a
single STOP does to that one track.

The three CUE/MUTE/SOLO handlers each XOR an 8-byte flag field in the audio-track param block
(emu-confirmed offsets): `FUN_40030e6c` → +0x20, `FUN_40030c60` → **+0x28**, `FUN_40030a6c`
→ +0x30.  The MUTE-LED routine (~0x4002ed00) tests `_DAT_460d10e2 & param[+0x28]` → **+0x28 =
MUTE = `FUN_40030c60`.**

`tools/patch_softmute.s` rewritten (V3): **detour `FUN_40030c60`** (prologue `4fefffe8
48d7047c`, 8 B). When SOFT MUTE is on, for a non-PICKUP current track (`DAT_100b14cc`), on the
key press (phase 1): keep a patch-owned 8-bit soft-mute mask at `0x80006c66` (+ valid byte
`0x80006c67`=0x5a), toggle this track's bit, and —
  - now-muted  → `DAT_8000184c |= 1<<t` + `FUN_40008f84(t)`  (exactly a per-track STOP: the
    `FUN_400068e4` tick fast-fades the AMP; the FX inserts keep ringing)
  - now-unmuted → just clear the bit
  - then `rts` — **the stock `FUN_40030c60` body never runs**, so the post-FX param-mute flag
    is never set → the FX return stays open.
PICKUP tracks + phase-0 (key release) → run the stock body unchanged.
Trade-off (test build): mute state not written to the pattern (no save/recall persistence),
MUTE LED does not light.

emu (`emu_mute.py` `_softmute_patch` now points at 0x40030c60): press1 t2 → `DAT_8000184c`
bit2, `DAT_8000184a` bit2, `SMASK=0x04`, param+0x28 untouched (stock skipped); press2 →
`SMASK=0`; press3 → `SMASK=0x04`; PICKUP → stock. Cave 204 B @0x400d7400.

Build: `python3 tools/build_softmute.py` → `out/OCTATRACK_OS1.40C_SOFTMUTE_PFFIX.{syx,bin}`
(+ the MIDI manual-trig fix, bytes identical to Build A; version "1.40C"; EFT round-trip OK).
247 bytes changed vs stock. **Not yet hardware-tested.**

If FUNC+TRACK behaviour is *unchanged* after flashing → `FUN_40030c60` isn't the FUNC+TRACK
path; retry with the detour on `FUN_40030e6c` (+0x20) or `FUN_40030a6c` (+0x30).

### V3 FLASHED → "no change" (user).  V4 — hooked the real per-frame mute gate.
Minimal-stub emu of the real FUNC+TRACK path (`FUN_40040250(trackkey,1)`, button table at
0x400bfc30 → all track keys dispatch here → `FUN_40083ab4` → `FUN_400836d8`): the ONLY state
write is `_DAT_460fab40 |= 1<<t`.  Nothing else.  A periodic task then syncs that into
**`_DAT_80000008`** (bit 8+t = muted, bit 16+t = cued — same bits the LED painter `FUN_40083eb0`
reads).

**`FUN_40004dbc`** (entry 0x40004db8) is the per-frame mute gate: `D5 = _DAT_80000008`; per
track it writes several 16-bit level words into the DSP-frame double-buffer (`_DAT_80003c10`),
and for a muted track it `clr.w`s them — a **post-FX cut** (kills the FX return).  Source
arrays `0x80000c60` / `0x80000c80` / `0x8000485a`.  SOLO uses a separate branch gated by
`_DAT_80000037`.

**patch_softmute V4**: detour the one instruction that loads `_DAT_80000008` into D5
(`2a39 80000008` @0x40004dc6, exactly a 6-byte jmp).  Cave (66 B @0x400d7400): loads D5;
unless SOLO is active, for `muted = (D5>>8) & 0xff`:
  - `DAT_8000184c |= muted`  → `FUN_400068e4` fast-fades those AMPs (dry cuts, like STOP)
  - `D5 &= ~(muted<<8)`      → `FUN_40004dbc` keeps their frame level words → FX inserts
    still reach the mix → tails ring
The global `_DAT_80000008` is untouched (LED still shows muted).  emu: `_DAT_80000008`
unchanged, `DAT_8000184c` gets the muted bits, no crash.

### V4 FLASHED → track stays FULLY audible (no muting) but LED/UI mute indicator toggles.
Confirms: `FUN_40004dbc` **is** the gate (clearing the D5 mute bit un-mutes completely), and
`_DAT_80000008` bit 8+t **is** the per-track mute flag on the FUNC+TRACK path.  But V4's
per-frame `DAT_8000184c |= muted` did NOT fade the AMP — that byte is a one-shot STOP command;
re-writing it every frame just re-arms `FUN_400068e4`'s "restart release from current level"
(and `pcurs+0xc = 0`, the position reset) each tick → no convergence, sample keeps playing.

### V5 — edge-triggered note-off + maintained release state
`patch_softmute.s` V5: same hook (`move.l 0x80000008,D5` @0x40004dc6).  Cave (132 B): shadow
of the muted mask in patch RAM `0x80006c66`; unless SOLO:
  - `newly = muted & ~shadow` (0→1 edge) → `jsr FUN_40008f84(t)` **once** per newly-muted track
  - every frame: `DAT_8000184a |= muted` (maintain the release-state bit; frame_builder
    @0x4000bd3c consumes+re-arms it, the way a held note-off is maintained)
  - `D5 &= ~(muted<<8)` → `FUN_40004dbc` keeps the frame level words → FX inserts reach the mix
emu: `_DAT_80000008` untouched, `DAT_8000184a` bit set on the edge + maintained frame 2,
`FUN_40008f84` called once (edge only).  183 B changed vs stock.  **Not yet hardware-tested.**

If V5 STILL leaves the sample audible → the note-off (`DAT_8000184a`→frame_builder `cmd|0x10`)
does not close the AMP for a freely-looping voice, and the fix has to write the AMP envelope
segment registers directly (`pcurs[t]+0x20..0x44`, base 0x80004f1c stride 0x54 +bank 0x2a0) or
the per-voice frame `vframe[0x80+t]` — a bigger job, and possibly a DSP-side one.  That would
be the point to reassess whether this is worth continuing.

### V5 FLASHED → FX-tail goal WORKS.  Dry hard-cuts (clean, no click) even with AMP REL maxed
→ the note-off (`DAT_8000184a`→`cmd|0x10`) does a fast declick fade, does NOT run the AMP
envelope RELEASE segment.  User: this is fine ("fast but smooth"), ship it — it's the
Digitakt "quiet mutes" behaviour.  Residual: a ~1-frame trig attack blip on muted tracks.

### V6 (SHIP CANDIDATE) — `python3 tools/build_softmute.py [VERSTR]`
`patch_softmute.s` = two hooks, one cave (228 B @0x400d7400):
  - `pre`   @ FUN_40004dbc 0x40004dc6 (`move.l 0x80000008,D5`): unless SOLO — for muted tracks,
    0→1 edge → `FUN_40008f84(t)` once; every frame `DAT_8000184a |= muted` + `D5 &= ~(muted<<8)`
    (keep the frame level words → FX inserts ring).  shadow @ 0x80006c66.
  - `pre_v` @ FUN_40005178 0x40005178 (voice-cmd queue, prologue `4feffff4 48d7001c`): drop
    "start" commands (bit 0x80 set, bit 0x10 clear) for a muted audio track → **no trig blip**.
    STOP/retrig (0x10 set) and unmuted tracks pass through.  Returns D0=1.
`_DAT_80000008` untouched → MUTE LED + pattern-stored mute state still work.  SOFT MUTE
ALWAYS ON (no PERSONALIZE toggle — deferred; menu-array surgery is brick-risky).
emu-verified: hook1 sets the release bit / edge note-off; hook2 drops muted-START, passes
STOP/retrig/unmuted.  Manual-trig fix bytes identical to `build_trigscale_only.py`.

**Branding**: version string field is a fixed **10 chars** — `1.40C_KYOTI` (11) does NOT fit.
build_softmute.py defaults to **`140C_KYOTI`** (drop the `_`).  Pass a different 10-char
string as `argv[1]` to change it.  Internal version code `0178` stays intact.

Outputs: `out/OCTATRACK_OS1.40C_SOFTMUTE_PFFIX.{syx,bin}` (version "140C_KYOTI", EFT ok,
259 B changed vs stock).  **Not yet hardware-tested.**
- If good → add the PERSONALIZE toggle for a shippable gated build (patch_notimer-style: add
  "SOFT MUTE" as a 3rd relocated menu entry; `moveq #15`→`#18`; `lbl_/get_/set_softmute`
  writing 0x800000dc) and fold into `build.py` (STUBS `("patch_softmute", 0x400d7b40)` +
  DETOUR `(0x400836d8, "patch_softmute", "pre", "apply-mute funnel")`, EXPECT `4fefffc448d77cfc`).
- QUICK MUTE screen edge: it also sets `_DAT_46c7ff64` on a confirm/page action — with our
  `_DAT_8000184e` bit set too, S7 says the frame builder keeps emitting (voice stays alive).
  May want the detour to also clear `_DAT_46c7ff64` bit `1<<(t+8)` for soft-muted tracks.
- Optional: neutralise the 45-frame `46c7dfba` release watchdog for soft-muted tracks so a
  max-REL setting genuinely sustains.

### Does the fix also cover QUICK MUTE?  — almost certainly yes
Track-key handler for the mute modes = `FUN_40040250(track, evt)` (0x40040250): does double-tap
/ hold detection (`_DAT_400c0aac` last-key, `_DAT_460d5de0` hold count) then:
 - single press  → `FUN_40083ab4(track, 1)` → sets `_DAT_460fab40` bit + `FUN_400836d8()`
 - double-tap    → `FUN_40083ab4(track, 3)`
 - evt==2        → `FUN_40083ab4(track, 2)`
 - else          → `FUN_40083e40(track, ...)` (unmute) → `FUN_400836d8()`
This is the same handler for FUNC+TRACK live mute AND the QUICK MUTE screen — both land on
`FUN_400836d8` + the `0x?040` voice command. So a detour on `FUN_400836d8` covers both by
default. The QUICK MUTE screen additionally has `FUN_400834d8` (→ `_DAT_46c7ff64`) and
`FUN_40083488` (→ `_DAT_46c7fe22` → `_DAT_8000184e` via `FUN_4000ac18`) wired to a
page-descriptor / confirm action — need to check during patch build whether either independently
hard-mutes on top of the `0x?040` path (if so, one extra small hook; if the `0x?040` command is
itself the instant cut, nothing more needed). Keeping QUICK MUTE *instant* while changing only
FUNC+TRACK would actually be the harder option (they share the code path).

---

## Session 9 — STATE OF PLAY (read this first next time)

### SOFT MUTE — WORKING, shipped as a test build.  `140C_KYOTI`.
Goal: audio-track mute should let delay/reverb tails ring out instead of the stock instant
post-FX cut.  **Done** (V6).  Muting an audio track (FUNC+TRACK / MIXER menu / QUICK MUTE) now
behaves like a single STOP for that track: dry cuts with a fast clean fade (~few ms, no click,
does NOT honour the AMP REL knob), the track's FX inserts ring their tails, and a muted track's
sequencer trigs are silent.

**How** — `tools/patch_softmute.s`, two hooks in one 228-B cave @0x400d7400, built by
`tools/build_softmute.py` (folds in the EFT wrap + make_bin + `-V`).  Mechanism: the per-frame
mute gate is **`FUN_40004dbc`** — it reads **`_DAT_80000008`** (bit 8+t = track t muted) and
`clr.w`s that track's level words in the DSP frame double-buffer.
  - `pre`   @ 0x40004dc6 (`move.l 0x80000008,D5`): unless SOLO — for muted tracks, keep the
    frame level words (`D5 &= ~(muted<<8)`), maintain `DAT_8000184a |= muted` every frame, and
    `FUN_40008f84(t)` once on the 0→1 edge (shadow byte @ 0x80006c66).
  - `pre_v` @ 0x40005178 (voice-cmd queue): drop "start" commands (bit 0x80 set, 0x10 clear)
    for a muted audio track.
`_DAT_80000008` is never modified → MUTE LED + pattern-stored mute state keep working.

**Flashable:** `out/OCTATRACK_OS1.40C_SOFTMUTE_PFFIX.{syx,bin}` — also carries the MIDI
manual-trig fix (`patch_trigscale`, bytes identical to Build A / PLAYSFREEFIX).  Version field
`140C_KYOTI` (10-char max; `1.40C_KYOTI` = 11, won't fit).  259 B changed vs stock, all in the
3 hook sites + 2 caves.  ALWAYS ON (no PERSONALIZE toggle).  Revert = flash stock 1.40C.

**Flash history this session (all on the user's Octatrack MKI — the only unit used for
on-hardware testing in this repo; the user does not own a MKII):**
V1/V2/D1 hooked `FUN_400836d8` / its `0x?040` voice command — no effect (not the mute).
V3 hooked `FUN_40030c60` (the +0x28 mute-flag key handler) — no effect (not the FUNC+TRACK path).
V4 hooked `FUN_40004dbc` + per-frame `DAT_8000184c` — track stayed fully audible (184c is a
one-shot; per-frame re-write stutters).  V5 = V4 + edge note-off/maintained `DAT_8000184a` —
**FX tails rang, dry cut fast+clean, faint 1-frame trig blip**.  V6 = V5 + `pre_v` trig-blip
fix + `140C_KYOTI` branding.  **V6 not yet flashed.**

### NEXT SESSION — pick up here
1. **User flashes V6.**  Confirm: FX tails ring, no trig blip, boot screen says `140C_KYOTI`,
   MIDI manual-trig fix still works, SOLO still hard-cuts, other tracks unaffected.
2. **SOLO extension** (user asked; not started).  Make solo also let the non-soloed tracks'
   FX tails ring.  Same function (`FUN_40004dbc`), same technique — V6 currently bails on
   `tst.b 0x80000037` (SOLO flag).  Plan: emulate the SOLO path, confirm whether soloing sets
   the same `_DAT_80000008` mute bits for non-soloed tracks (likely) and whether
   `FUN_40004dbc`'s solo branch (0x40004dd4) uses the same frame-word layout as the normal
   branch (0x40004e3a).  If yes → remove the SOLO bail + make `pre_v` treat solo-muted tracks
   as muted.  Solo mask may instead be `_DAT_8000000c` (LED painter `FUN_40083eb0` reads it in
   the solo branch @0x40083eee; `_DAT_80000037` setters @0x40065172/8e, 0x400654e0/fc).
   Estimate: 1 emu pass + a V7 build.  Low brick risk.
3. **PERSONALIZE toggle** (deferred).  Add "SOFT MUTE" as a menu entry writing `0x800000dc`
   (patch_softmute.s already has the `.ifndef ALWAYS_ON` gate on that byte).  patch_notimer-
   style: relocate the 3 PERSONALIZE arrays (`OLD_LBL 0x400b2a34` / `OLD_GET 0x400b2a74` /
   `OLD_SET 0x400b2ac0`, 16 entries) to a cave with 17, repoint the REFS (see build.py lines
   70-78), bump `moveq #15` @0x40068fb2 → `#16`.  Provide `lbl_/get_/set_softmute` (glyphs
   0x400b5e90 on / 0x400b5e8e off).  Menu-array surgery is the one thing that has bricked this
   unit before — do it carefully, verify the 16 stock entries still render.
4. Optional refinements: dry-decays-over-REL (needs writing AMP env segment regs
   `pcurs[t]+0x20..0x44` @0x80004f1c stride 0x54 +bank 0x2a0 directly — bigger); cap the
   number of simultaneous ringing tails in solo.

### Session 9 tooling (all uncommitted)
`tools/patch_softmute.s`, `tools/build_softmute.py`, `tools/emu_mute.py` (11 scenarios — most
test the V1/V2 dead-end approach on `FUN_400836d8`, kept as the investigation record; the
`_softmute_patch()` helper now points at the V6 hook), `tools/GhidraMute{1..8}.java`,
dumps `out/ghidra/GhidraMute*_session9.txt` + `out/ghidra/emu_mute_session9.txt`.
The scattered V1-V5 narrative above this section is the working log; THIS section is current.

---

## Session 10 — MUTE MODE PERSONALIZE entry (test build, not yet flashed)

### What shipped this session
A new **test build** that puts SOFT MUTE behind a PERSONALIZE toggle instead of ALWAYS_ON.
The shipped V6 artifacts (`out/OCTATRACK_OS1.40C_SOFTMUTE_PFFIX.*`, ALWAYS_ON) are **untouched**.

  `python3 tools/build_mutemode.py`  ->
     out/OCTATRACK_OS1.40C_MUTEMODE.syx   (MIDI DIN)
     out/OCTATRACK_MUTEMODE.bin           (CF card, PROJECT -> OS UPGRADE)
     version string  `140C_KYOTI`
     589 bytes changed vs stock 1.40C

Stock 1.40C  +  patch_trigscale (MIDI manual-trig fix, byte-identical to Build A)
             +  patch_softmute V6 hooks, assembled **gated** (no ALWAYS_ON)
             +  patch_mutemode (the menu entry)

### PERSONALIZE menu deep dive (full writeup: this section + `out/ghidra/GhidraMenu1_session10.txt`)
Renderer `FUN_40068e00`, input `FUN_40068fd0`, list-init `FUN_40068fa8`.  Three parallel
16-entry arrays, contiguous, followed by unrelated data -> not extendable in place:
  labels  0x400b2a34   `char*`             ref: `move.l #imm,%d5` @0x40068efe  (IMMEDIATE, not lea)
  getters 0x400b2a74   `char*(*)(void)`    ref: `lea …,%fp`       @0x40068f0a
  setters 0x400b2ac0   `void(*)(int d,int wrap)`  refs: `lea …,%a0` @0x40069022 / 0x4006903e / 0x40069056
  (LED-BRIGHT value strings 0x400b2ab4 — LOW/MID/MAX — addressed absolutely, not relocated)
A **getter just returns a `char*`** drawn in the right column (x=0x4d).  Checkbox items return
a 1-glyph string (0x400b5e90 on / 0x400b5e8e off); **LED BRIGHTNESS returns "LOW"/"MID"/"MAX"** —
i.e. a multi-value text option is already a stock, shipping pattern (getter `FUN_40068c80`,
setter `FUN_4006907c`).  Setter ABI: `delta` @4(sp), `wrap` @8(sp) — [YES]=(+1,wrap), [RIGHT]=
(+1,clamp), [LEFT]=(-1,clamp).  Same ABI as `set_notimer`.
Count `FUN_40068fa8`: `moveq #15,%d1 ; sub %d0,%d1`, `%d0 ∈ {0,-1}` from `tst.l 0x46c8d18c`
=> **15 items, or 16 when 0x46c8d18c != 0**.  `0x46c8d18c` is the boot-time MKI/MKII probe
(set to 1 on the MKII path, 0 on the MKI) — `LED BRIGHTNESS` (index 15) is the one item gated
on it, which is why the user's **MKI shows 15 PERSONALIZE items, no LED BRIGHTNESS**.
**Nothing in the firmware keys off an absolute PERSONALIZE index** — every ref to the menu's
cursor/scroll/count/rows globals (0x460e4670/68/78/74) lives inside the 0x40068e00..0x40069074
block.  So the splice position for a new entry is entirely free.

### The surgery (build_mutemode.py — proven build.py technique)
1. Copy all 3 arrays into the free cave (LBL 0x400d7700 / GET 0x400d7760 / SET 0x400d77c0,
   68 B each) with **MUTE MODE spliced at index 2** — right after "PREVIEW WITHOUT FX":
      [0] QUANTIZE LIVE REC  [1] PREVIEW WITHOUT FX  [2] MUTE MODE  [3] MUTE FOCUSES TRK …
      … [15] EXT LEN GRID-REC  [16] LED BRIGHTNESS  (stays last, stays behind the MKII gate)
2. Repoint the 5 refs from the linker symbol table (guarded on the original bytes).
3. `moveq #15` @0x40068fb2 -> `moveq #16`  =>  16 items on the MKI (15 stock + MUTE MODE,
   LED BRIGHTNESS still hidden), 17 on a MKII.  Adds exactly the one new item on either.
`patch_mutemode.s` = `lbl_mutemode` "MUTE MODE" + value strings "OT"/"OT+FX" + `val_tbl` +
`get_mutemode` (return `val_tbl[clamp(0x800000dc,0,NMAX)]`) + `set_mutemode` (clamp on
[LEFT]/[RIGHT], wrap on [YES], over [0,NMAX]).  `.equ N_MODES,2` — bump to 3 + add `vm_2` for
the 3rd mode later.

### Flag word 0x800000dc == patch_softmute's GATE
0 = "OT" (stock instant post-FX cut)   1 = "OT+FX" (soft mute: dry cuts, FX inserts ring).
Free battery-backed PERSONALIZE word; default 0 => a fresh flash is stock.  OS upgrade resets
PERSONALIZE.  Persistence across power cycles is inferred, not yet hardware-verified — TEST IT.

### Two fixes to patch_softmute.s this session (affects a fresh build_softmute.py too)
- **gate now reads the 32-bit word** (`move.l GATE,%d0 ; cmpi.l #1,%d0 ; bne`), was
  `move.b GATE,%d0 ; beq`.  The `.ifndef ALWAYS_ON` gate path was never on hardware (V1–V6
  all ALWAYS_ON) and had a **big-endian bug**: the LED-BRIGHTNESS-style setter writes a full
  word, so `move.b` at 0x800000dc read the MSB (always 0) — the soft path would never engage.
  Also: `!= 1` (not `!= 0`) so a future mode 2 falls to the stock cut until implemented.
- **`pre` movem fixed**: `movem.l %d0-%d3/%a0` (5 longs = 20 B) into a `lea (-0x10,%sp)` frame
  (16 B) scribbled 4 B of `FUN_40004dbc`'s frame every call — latent in V1–V6.  `a0` is unused
  in `pre`; dropped it -> `movem.l %d0-%d3`.  A fresh `build_softmute.py` (ALWAYS_ON) now
  differs from the flashed V6 by exactly these 2 mask bytes (010f->000f, ×2).  `pre_v` was
  already safe (3 longs into 16 B — wasteful, not corrupting; left as-is).

### Verified (static + Unicorn) — `tools/emu_mutemode.py` : ALL GOOD
relocated arrays = stock[0:2]+MUTE MODE+stock[2:16]; 5 refs repointed; `moveq #16`; 3 detours
hit their symbols; `get_mutemode` returns OT/OT+FX for MUTE_MODE ∈ {-1,0,1,2,99}; `set_mutemode`
clamps/wraps correctly over [0,1]; gated `pre` engages the soft path only for MUTE_MODE==1.

### NEXT — hardware test on the MKI
Flash `out/OCTATRACK_MUTEMODE.bin` (CF) or `.syx` (MIDI).  Confirm:
1. PERSONALIZE lists **16 items**, `MUTE MODE` is 3rd (after PREVIEW WITHOUT FX), shows `OT`.
2. The other 15 stock items still render + behave (esp. the neighbours: QUANTIZE LIVE REC,
   PREVIEW WITHOUT FX, MUTE FOCUSES TRK).
3. LEFT/RIGHT/YES cycle `OT` <-> `OT+FX`.
4. `OT`  -> mute = stock instant cut.   `OT+FX` -> mute lets FX tails ring, dry cuts clean,
   no trig blip; SOLO still hard-cuts; MIDI manual-trig fix still works.
5. Set `OT+FX`, power-cycle -> setting persists.  OS re-flash -> back to `OT`.
6. Boot / SYSTEM STATUS shows `140C_KYOTI`.
Then: 3rd mute mode (user has a design in mind — separate session); SOLO extension still open.

### Session 10 tooling (uncommitted)
`tools/patch_mutemode.s`, `tools/build_mutemode.py`, `tools/emu_mutemode.py`,
`tools/GhidraMenu{1,2}.java`, dumps `out/ghidra/GhidraMenu{1,2}_session10.txt`.
`tools/patch_softmute.s` modified (2 fixes above).

---

## Session 11 — SOFT MUTE extended to SOLO (patch_softmute V7, in the MUTEMODE test build)

**MKI HW status: the Session 10 MUTEMODE build flashed and works well.** V7 rebuilds it with
solo support folded into the OT+FX mode — no separate toggle.  `python3 tools/build_mutemode.py`
-> same outputs, version `140C_KYOTI`, now **630 B vs stock**.

### The frame builder's SOLO branch (deep dive: `out/ghidra/GhidraSolo{1,2}_session11.txt`)
`FUN_40004db8` (hook site 0x40004dc6) branches on **`tst.b 0x80000037`** (the SOLO-mode flag,
set to 1 @0x400654de / cleared @0x400654fa; the solo-engage handler does NOT touch
`_DAT_80000008`).  `_DAT_80000008` layout: **bits 0..7 = per-track SOLO, 8..15 = MUTE,
16..23 = CUE** (confirmed via the AUDIO-CC-OUT emit in case 'L': CC49=mute bit8+t, CC50=solo
bit t, CC51=cue bit16+t).
- **not-solo branch** (0x40004e3a): per track, `clr.w` the mute-gated frame word iff bit 8+t set.
- **solo branch** (0x40004dd4): per track — bit t set (SOLOED) -> keep both words; else the
  words are AND-ed with **D1 = `(D5.low8 == 0) ? -1 : 0`** (the "is anything soloed?" mask) ->
  silenced; a non-soloed **and muted** track -> `clr.l` instead.
  It only ever tests D5 bits 0..15 (D3 starts at 0), never the cue bits.

### V7 mechanism (`tools/patch_softmute.s`, one shadow byte, no new RAM)
`pre` now computes a single **`silenced`** audio-track set per frame (D2, bits 0..7):
- not solo: `silenced = mute mask`  ->  clear those mute bits from D5 (as V6).
- solo + >=1 soloed: `silenced = ~soloed & 0xFF`  ->  **`D5 &= 0xFFFF0000`** so every track
  hits the "& D1" keep path AND D1 becomes -1 -> FUN_40004db8 keeps *every* track's frame
  words -> all FX returns ring.
- solo + none soloed: `silenced = 0` (stock; nothing cut yet).
Then (shared path): shadow-edge -> `FUN_40008f84(t)` once per newly-silenced track;
`REL_STATE |= silenced` every frame.  MUTE MODE == OT -> `clr.b SHADOW` + bail (byte stock).
`pre_v` drops a bare "start" voice-cmd for a silenced track: muted, OR (solo active AND
>=1 soloed AND this track not soloed).  Retrigs (stop bit set) always pass.
The shadow at 0x80006c66 is **reused** (widened from "muted mask" to "silenced set"); it is
now written every frame (incl. 0) so an OT->OT+FX switch or a solo release never leaves it
stale.  `pre` movem stays `%d0-%d3` (4 longs / 16 B — the Session 10 fix).

Behaviour: soloing overrides mute (stock); a non-soloed track's dry fades (note-off, does NOT
honour AMP REL) while its FX inserts ring; its trigs are silent while solo is held; releasing
solo resumes on the next trig (a held note does not come back — same trade-off as direct
soft mute, which the user is happy with).

Cave layout (build_mutemode.py): patch_softmute V7 330 B @0x400d7400; patch_mutemode moved to
0x400d7600; menu arrays 0x400d7700/60/c0; patch_trigscale 0x400d7b00.  (ALWAYS_ON build =
288 B; a fresh `build_softmute.py` would now also carry V7 solo support — the shipped V6
`OCTATRACK_OS1.40C_SOFTMUTE_PFFIX.*` on disk are untouched.)

### Verified — `tools/emu_solo.py` : ALL GOOD (25 checks)
Runs the real image bytes.  `pre`: not-solo mute path unchanged; solo+1-soloed -> silenced =
other 7, REL_STATE=0xFE, D5 bits 0..15 cleared, one note-off each; solo+none-soloed -> no-op;
solo+also-muted -> still handled; edge de-dupe; OT bail clears shadow; OT->OT+FX keeps the
first note-off.  `pre_v`: drops muted / solo-non-soloed starts, passes soloed / retrig / OT.
`tools/emu_mutemode.py` still ALL GOOD.

### NEXT — hardware test on the MKI (in addition to the Session-10 checklist)
1. Solo a track with `MUTE MODE = OT+FX`: the non-soloed tracks' FX (delay/reverb) tails ring
   out instead of cutting instantly; their dry stops; their trigs are silent while solo held.
2. Release solo -> non-soloed tracks resume (on their next trig).
3. Solo a **muted** track -> it plays (solo overrides mute), stock behaviour.
4. `MUTE MODE = OT` -> solo cuts instantly, exactly stock.
5. Re-confirm the direct-mute soft behaviour + MIDI manual-trig fix are unregressed.
Then: the 3rd mute mode (user has a design in mind).

### Session 11 tooling (uncommitted)
`tools/GhidraSolo{1,2}.java` + dumps `out/ghidra/GhidraSolo{1,2}_session11.txt`,
`tools/emu_solo.py`.  `tools/patch_softmute.s` rewritten V6->V7.  `tools/emu_mutemode.py`
updated (REL_STATE write detected via a mem-write hook, not a hard-coded address).

---

## Session 12 (2026-09-01) — "DT" MUTE MODE (3rd option; built, emu-verified, NOT flashed)

**User away from the MKI for ~2 weeks — build + emulate only this session.**

### What "DT" is (user's spec, clarified mid-session)
A third `MUTE MODE` value after `OT` and `OT+FX`.  DT = **a pure Digitakt-style trig mute**:
muting an audio track (or a track silenced by SOLO) does **nothing to the voice engine** —
the voice that is already sounding keeps playing under **its own amp envelope** exactly as if
you never muted (fades to silence, sustains, or loops forever, whatever ATK/HOLD/REL +
LOOP say).  The **only** effect of the mute is that **new sequencer/manual trigs are
suppressed** until unmute.  FX rings naturally because the whole track keeps running.
Explicitly **NOT** wanted: forcing the voice into its release phase on mute (that was the
earlier design guess — rejected by the user).

### Why this is low-risk (vs the 6 HW iterations OT+FX needed)
DT = **V4's hardware-confirmed behaviour** ("clear the D5 mute/solo bits -> FUN_40004db8
keeps every DSP-frame level word -> track stays fully audible, voice + FX untouched") **+
V6's hardware-confirmed `pre_v`** ("drop bare 'start' voice-cmds for a silenced track ->
no new trigs") **MINUS V5's note-off** (`FUN_40008f84` / `DAT_8000184a`).  Both halves are
already proven on the user's MKI; DT just runs them together with *less* intervention than
OT+FX.  No voice-struct / envelope / DSP poking at all.

### The patch (`tools/patch_softmute.s`, compile-gated behind `--defsym DT_MODE=1`)
Only difference from OT+FX, inside the existing `pre` (@0x40004dc6) + `pre_v` (@0x40005178):
| step | OT+FX (GATE==1) | DT (GATE==2) |
|---|---|---|
| compute `silenced` set, clear D5 mute/solo bits (keep frame words) | yes | yes (identical) |
| `FUN_40008f84(t)` note-off on the shadow edge | yes | **no** |
| maintain `DAT_8000184a \|= silenced` every frame | yes | **no** |
| `pre_v` drops bare-"start" voice-cmds for `silenced` tracks | yes | yes (identical) |
`pre` gate now `beq p1_active` on `#1` **or** `#2`; the DT branch at `p1_edge` does
`clr.b SHADOW` (so a live DT->OT+FX switch re-asserts every note-off) + `bra p1_done`.
`pre_v` gate widened `subq.l #1 ; cmpi.l #1 ; bhi v_stock` (accept modes 1,2).
All new code is `.ifdef DT_MODE` — a plain `build_mutemode.py` is **byte-identical** to
before (verified: md5 `6d9ff8ba…` unchanged after the source edits).

### Menu (`tools/patch_mutemode.s`, also `.ifdef DT_MODE`)
`N_MODES 2->3`, value strings `OT / OT+FX / DT`, `val_tbl` 3rd entry `vm_2`.  Getter/setter
already parametric on `NMAX` — clamp/wrap now over [0,2].

### Build — `python3 tools/build_mutemode_dt.py [VERSTR]`  (default `140C_KYOTI`)
Copy of `build_mutemode.py`; assembles patch_softmute + patch_mutemode with `--defsym
DT_MODE=1`; **separate outputs** so the Session-10/11 artifacts are untouched:
  `out/OCTATRACK_OS1.40C_MUTEMODE_DT.syx`  (MIDI DIN)
  `out/OCTATRACK_MUTEMODE_DT.bin`          (CF card, PROJECT -> OS UPGRADE)
659 B changed vs stock; 394 B differ vs `mainos_mutemode.bin`, **all confined to the
patch_softmute cave + patch_mutemode cave + relocated menu arrays + the pre_v detour word**
(build script asserts this — OT/OT+FX/solo paths bit-unchanged).  Caves: patch_softmute
368 B @0x400d7400, patch_mutemode 130 B @0x400d7600, menu arrays 0x400d7700/60/c0,
patch_trigscale 62 B @0x400d7b00 — no overlap, ends < 0x400d7c3c.  Manual-trig fix bytes
identical to `build_trigscale_only.py`.  EFT round-trip OK, version `140C_KYOTI`.

### Verified — `tools/emu_dt.py` : ALL GOOD (runs the real DT image bytes)
- menu: `get_mutemode` -> OT/OT+FX/DT for MUTE_MODE ∈ {-1,0,1,2,3,99}; `set_mutemode`
  clamps [0,2] on LEFT/RIGHT, wraps on YES.
- DT `pre` (gate 2): D5 mute/solo bits cleared, **no `FUN_40008f84`**, `DAT_8000184a`
  untouched, `SHADOW` cleared; solo+1-soloed -> D5 bits 0..15 cleared; solo+none -> no-op.
- regressions in the DT image: OT+FX `pre` (gate 1) still note-offs + maintains REL_STATE;
  OT `pre` (gate 0) bails with the mute bit left set.
- DT `pre_v` (gate 2): drops muted / solo-non-soloed bare starts; passes retrig / soloed /
  unmuted; OT+FX still drops, OT still passes.
`tools/emu_solo.py out/mainos_mutemode_dt.bin` : ALL GOOD (OT+FX + solo unregressed).
(`tools/emu_mutemode.py` stays pointed at `out/mainos_mutemode.bin` — its N_MODES=2 cases
are meant for that image; run `emu_dt.py` for the DT build.)

### NEXT — hardware test on the MKI (when the user is back with the unit)
Flash `out/OCTATRACK_MUTEMODE_DT.bin` (CF) or `.syx` (MIDI).  In addition to re-running the
Session 10/11 checklists (OT, OT+FX, solo, MIDI manual-trig fix, `140C_KYOTI` boot string):
1. PERSONALIZE -> MUTE MODE now cycles `OT` <-> `OT+FX` <-> `DT` (LEFT/RIGHT/YES).
2. `DT`, one-shot sample, medium REL: mute mid-note -> the note **finishes its own amp
   release** (not the fast OT+FX declick), FX rings; the muted track's trigs are silent;
   unmute -> silent until the next trig.
3. `DT`, LOOP sample, HOLD/REL at max: mute -> **the loop keeps sounding indefinitely**;
   new trigs suppressed; unmute -> loop still going, trigs resume.
4. `DT` + SOLO: non-soloed tracks' currently-playing voices ride out their envelopes; their
   trigs silent while solo held; release solo -> resume on next trig.
5. Switch `DT` -> `OT+FX` while a DT-muted voice is ringing -> it should get the OT+FX
   note-off on the next frame (the `clr.b SHADOW` re-assert).
If DT leaves a *plain FLEX one-shot* audible with no envelope motion at all (i.e. the voice
never advances because something about mute stalls the per-frame updater) -> unlikely
(FUN_40004db8 is downstream of the voice updater) but the fallback is to also `clr` the
`46c7ff64` output-mute bit for DT tracks.

### Session 12 tooling (uncommitted)
`tools/build_mutemode_dt.py`, `tools/emu_dt.py`.  `tools/patch_softmute.s` +
`tools/patch_mutemode.s` gained `.ifdef DT_MODE` blocks (plain builds byte-unchanged).
`build_mutemode_dt.py` links its stubs as `out/patch_*_dt.elf` (distinct intermediates -- it
never clobbers `build_mutemode.py`'s `out/patch_*.elf` that emu_mutemode / emu_solo read
back).  `tools/emu_solo.py` now picks `patch_softmute_dt.elf` when handed a `*_dt.bin` image.
Outputs `out/OCTATRACK_*MUTEMODE_DT.*`, `out/mainos_mutemode_dt.bin`, `out/elek_mutemode_dt.bin`.
Run order no longer matters: `emu_mutemode.py` on the 2-mode image, `emu_dt.py` +
`emu_solo.py out/mainos_mutemode_dt.bin` on the DT image, all ALL GOOD in any sequence.

---

## Session 13 (2026-09-01) — SCOPING ONLY: "auto-remove an emptied trigless lock" (no work done)

**User idea, feasibility-scoped this session. No RE, no build. This block is the brief for
whoever picks it up.**

### The wish (user's words, lightly tightened)
In **LIVE REC** mode, with the sequencer running (recording), the user erases parameter
locks with **`[NO]` + knob** (the live "clear as the playhead passes" erase). Today, once
every p-lock has been erased from a step that only ever held p-locks (a "trigless lock" /
dim-red lock), **the lock stays lit on the 16-step row** — pure visual noise. Wish: when an
erase pass takes a trigless lock's lock count from 1 -> 0, the trigless lock itself is
**removed from the pattern** (LED off, step inert).

Constraints from the user:
- **Only** the pure-p-lock trigless lock. Do **not** touch: trigless trigs that retrig
  LFOs / one-shot FX envelopes ("green"), sample/audio trigs, MIDI trigs, one-shot trigs,
  recorder trigs, slide trigs, anything else.
- Multi-pass semantics: 2 params locked on a step -> one erase pass clearing one param
  leaves the lock lit; the second pass clearing the last param deletes it.
- The user can still **place** an empty trigless lock by the normal methods and it must
  persist — "empty" trigless locks are legal. The deletion fires **only** as the 1->0
  transition of an erase op, never as a global sweep of empty locks.
- Gesture is specifically the `[NO]`+knob **LIVE REC** live-erase. (Earlier in the chat the
  user said GRID REC by mistake, then corrected to LIVE REC.) Whether GRID-mode erases
  (`[TRIG]`+`[NO]`, CLEAR) should also trigger the deletion is an **open user decision** —
  default to narrowest (LIVE `[NO]`+knob only).

### Verdict: FEASIBLE, but a real RE project in an untouched subsystem
Comparable in size to the soft-mute effort: **~3-5 sessions + HW iteration + exported test
banks**. Brick risk LOW (data-model read + one hook; no PERSONALIZE menu-array surgery).
The hard part is *behavioural correctness* — the "this trigless lock now holds nothing"
predicate must never fire on a trig the user wanted to keep.

### What we already have (starting material)
| Piece | State |
|---|---|
| Project DB base `_DAT_46c82456`, pattern stride `0x18b2` | solid, used throughout |
| Per-track sequenced-data region `base + pat*0x18b2 + trk*0xc` near `+0x8f385` | named "trigs/params" in the engine map (NOTES ~L197); **internal layout NOT mapped** |
| Trig->voice (`FUN_400977cc`, `FUN_40005030`) | reads "which sample" per step; does not expose the trig-type / p-lock bytes |
| Trig-LED painter family (`FUN_40083eb0/fdc`, `FUN_400132c4(id,state)` -> 2-bit LED buf `0x460ba98c`) | mapped for **scene** trig lighting only, not normal trig-type LED logic |
| Scene p-lock blocks `base + pat*0x18b2 + scene*0x100 + 0x8f3e2`, `0x20`/param-group stride | shape hint only; scenes != per-step p-locks |

`COVERAGE.md`: "Trig types / p-locks / sample locks" and "conditional locks / micro timing"
are both **untouched (unmapped)**. No RE yet on where a step's p-lock bitmap lives, how the
trig-type flags are encoded, or which handler clears a p-lock live.

### Work plan (when someone picks this up)
**Phase 0 — model the per-step trig data (1-2 sessions).** Export-and-diff on the MKI
(mandatory per START_HERE: real exported banks only). User builds targeted patterns:
a pure trigless lock w/ 2 p-locks; a trigless-trig w/ LFO retrig + 1 p-lock; a manually
placed empty trigless lock; a sample trig w/ p-locks; recorder + MIDI trig rows. Diff the
blobs to pin:
  1. trig-type flag bits (sample / trigless-trig-with-retrig / pure-lock / one-shot / slide;
     plus the separate recorder-trig and MIDI-trig layers),
  2. the "which params are locked" bitmap (audio pages + sample-slot lock + LFO p-locks +
     FX p-locks — OT locks span several param pages),
  3. anything else attachable to a lock step (trig condition, micro-timing, slide).

**Phase 1 — find the LIVE-REC `[NO]`+knob p-lock-clear handler + hook it (1 session + emu).**
One detour, *after* the clear: if `trig_type == pure trigless lock` AND locked-bitmap `== 0`
AND no sample-slot lock AND nothing else attached -> clear the step's trig-type flag. LED
painter + sequencer then ignore it for free. Predicate must be **conservative**: delete only
when the step is unambiguously a bare lock; when in doubt, keep it.

**Phase 2 — build + HW iterate (1-2 flash cycles).** Same build scaffold as the mute work
(guarded binary patch, cave, EFT round-trip, `140C_KYOTI`).

### Risks / open items
- **Predicate is the whole ballgame.** If trig-type flag and p-lock bitmap aren't
  independent bits, or we miss a lock category (LFO-designer lock, FX lock, condition),
  we could delete a wanted trig. Conservative predicate + HW verification mitigate.
- **Confirm the gesture/handler.** Rock-solid OT live-erase is `[NO]`+knob in LIVE REC;
  pin that exact routine in Phase 1.
- **User decision:** LIVE `[NO]`+knob only, or also grid `[TRIG]`+`[NO]` / CLEAR? Default
  narrowest.
- **Sample-slot-only lock:** does a trigless lock whose only remaining lock is a sample
  lock count as "empty"? Default: treat sample lock as a lock -> keep the trig.

### Cheaper fallback (offered, not chosen)
Cosmetic-only: patch the trig-LED painter to not light a trigless-lock step whose lock
bitmap is empty. Zero data risk, reflash-reversible, kills the visual-noise complaint — but
the trig still exists in the pattern (saved, occupies the step, reappears on edit). Still
needs Phase 0's data model, so not free; could ship first to de-risk. User did not pick this
— they want actual deletion.

### PERSONALIZE toggle?
Not discussed. If this ships it would likely want to be opt-in (a 4th behaviour alongside
MUTE MODE, or its own entry) — but that's menu-array surgery again (the one thing that has
bricked the MKI before). Decide later.

---

## Session 14 (2026-09-01) — RE for a 4th MUTE MODE: "instant cut + FX tails + resume at playhead"

**Branch `wip/mute-mode`. RE + emulation only — nothing built or flashed. User away from MKI ~2 wks.**

### The ask
A mode that combines the best of OT and OT+FX: mute cuts the dry **instantly**, the track's
FX-insert tails **ring out**, AND unmute **resumes the sample where the playhead would be**
(like stock OT), not "silent until the next trig" (OTFX-T / DT-T).  Menu goes 3 -> 4 values.

**Names decided by the user (2026-09-01).**  Taxonomy: no suffix = unmute resumes at the
playhead (like OT); `-T` = trig-mute (only a new trig restarts).

| MUTE MODE value | `GATE` (0x800000dc) | behaviour |
|---|---|---|
| `OT`     | 0 | stock -- instant cut, FX tails die, unmute resumes at playhead |
| `OTFX`   | 1 | **new (this session)** -- instant dry cut, FX tails ring, unmute resumes at playhead |
| `OTFX-T` | 2 | the current V6b/V7 OT+FX -- instant dry cut (note-off), FX tails ring, **trig-mute** |
| `DT-T`   | 3 | the current DT -- voice rides its own amp envelope, FX tails ring, **trig-mute** |

Renumbering vs the `wip/mute-mode` build (0=OT, 1=OT+FX, 2=DT): old 1 -> 2, old 2 -> 3.  OS
upgrade resets PERSONALIZE so no migration issue.  `OTFX-T` is 6 chars -- `OT+FX` (5) rendered
fine on the MKI (Session 10); the value column at x=0x4d should hold ~8, but verify against
`FUN_40068e00` at build time and fall back to `OTFXT` if it clips.

### Why the four behaviours differ — the mute lever, pinned down
`FUN_40004db8` (the per-frame mute/solo/cue gate, HW-confirmed as *the* gate) was fully
disassembled (`m68k-elf-objdump -m m68k:5407`, the r2/Ghidra decompiles were both wrong on the
inner branch) and re-run on real bytes (`tools/emu_otfx.py` — ALL GOOD).  Per track it writes
**4 u16 words** (8 B/track) into the buffer at `0x80003c10`, from three source arrays
`A=0x80000c60` (stride 4), `B=0x80000c80` (stride 2), `C=0x8000485a` (stride 8):

| word | value | gate |
|---|---|---|
| `frame[8t+0]` | `A[t].word1` | **CUE** bit (16+t) — CUE-send level |
| `frame[8t+2]` | `A[t].word0` | soloed(t) → keep; else **MUTE** bit (8+t) → **0**; else any-track-soloed → 0 — **MAIN mix level** |
| `frame[8t+4]` | `B[t]` | **ungated** (pan) |
| `frame[8t+6]` | `C[t].word0` | **ungated** (pan) |

So **mute's only lever in this function is zeroing the post-FX MAIN-mix word** (`frame[8t+2]`).
Stock OT does exactly that and nothing else → voice untouched (cursor + envelope keep running
→ resume works) but the FX-return dies because it shares that one post-FX bus word.  V6/V7's
`D5 &= ~(muted<<8)` keeps `frame[8t+2]` open (→ tails reach MAIN) and then kills the dry
*upstream at the voice* via `FUN_40008f84` note-off — which frees the voice (45-frame
`46c7dfba` watchdog) → no resume.  DT keeps the word open and does nothing else → a sustained
voice stays audible through the "mute".  **There is no pre-FX voice control in `FUN_40004db8`;
+4/+6 are pan and useless here.**

### The candidate mechanism for the new mode
Keep `frame[8t+2]` open (the V6 `pre` D5-trick, like DT) **and** force the **per-voice
pre-FX amp level to 0** for muted tracks — without touching the voice struct, so its sample
cursor keeps advancing and unmute just restores the level.

The per-voice amp array is **`0x46c7ff42`** (stride 4, 8 voices; sits right below
`_DAT_46c7ff64` the post-FX MAIN mute).  It is filled **every frame** by `FUN_4000d16c`'s
voice loop: `d0 = FUN_400068e4(t, DAT_800000e0, cmd&0xf, 0x10)` (returns voice-struct `+0x18`
= the current amp-envelope output) then **`0x4000d36e: move.l %d0,(%a2)+`** with `a2 = 0x46c7ff42`,
`d3 = t`.  `FUN_400068e4` is the control-rate envelope updater (voice struct base `0x80004f1c`,
stride `0x54`, +`0x2a0` double-buffer; env slopes at +0x24/28/2c, position at +0xc).

**Hook (3rd site, gated on the new mode only):** detour `0x4000d36c` (8 B: `jsr (a3)` +
`move.l d0,(a2)+` + `movea.l (160,sp),a1`) → cave: run the `jsr FUN_400068e4`, then if track
`d3` is muted (`0x80000008` bit 8+d3) or solo-silenced → `moveq #0,d0`, then the displaced
`move.l d0,(a2)+` / `movea.l (160,sp),a1`, resume `0x4000d374`.  Because `0x46c7ff42[t]` is
recomputed every frame, zeroing it is non-destructive — unmute (stop zeroing) restores it.

Companion changes for the new mode (`OTFX`, `GATE == 1`):
- `pre` (0x40004dc6): clear D5 mute/solo bits (keep MAIN word) — **no** note-off, **no**
  `REL_STATE` — identical to the `DT-T` branch.  (Gate branches renumber: `OTFX-T` = the old
  note-off path moves to `GATE == 2`; `DT-T` to `GATE == 3`.)
- `pre_v` (0x40005178): **do NOT drop** bare starts (unlike `OTFX-T`/`DT-T`) — a trig fired
  while muted should start a voice that advances silently (amp-zeroed) so unmute picks it up
  mid-sample, matching stock OT.

### Two unknowns that only a hardware flash can settle
1. **Is `0x46c7ff42` pre- or post-insert-FX?**  If post-FX, zeroing it = stock OT (tail dies)
   and the mode is pointless.  Believed pre-FX (amp envelope is classically pre-FX; the array
   is per-*voice* not per-*track*, distinct from the post-FX `46c7ff64`).
2. **Does the DSP keep advancing the voice's sample cursor while `0x46c7ff42[t] == 0` for
   many frames**, or does it treat zero-amp / non-refreshed as "free the voice"?  If it frees
   it → collapses to OT+FX-TRIG (no resume).
   Fallbacks: write a tiny non-zero amp (e.g. `1`) instead of `0`; or also keep the
   `46c7faa4[t]` refresh slot alive.
3. Minor: an abrupt 0 may click — may need a 1-frame ramp.

### Recommendation: flash DT first
**DT rests on the *same* unknown #2** (Session 12 NEXT, "does the DSP keep advancing a plain
FLEX one-shot while the frame words flow untouched").  Flashing the existing
`OCTATRACK_MUTEMODE_DT.bin` answers it:
- DT loop-sample test (item 3) shows the voice audibly keeps running → unknown #2 = **YES**,
  and this new mode becomes low-risk (build it next).
- DT shows the voice stalls → this mode needs the fallback path and both need a rethink.

So the order is: **flash DT → confirm the voice keeps advancing → then build the new mode**
as menu value 1 of `OT / OTFX / OTFX-T / DT-T`.

### Menu / naming caveat
Values (user-chosen): `OT` / `OTFX` / `OTFX-T` / `DT-T`.  Value column (renderer
`FUN_40068e00`, x=0x4d) — stock values there are ≤5 chars (`LOW/MID/MAX`), our shipped
`OT+FX` = 5 rendered fine on the MKI (Session 10).  **`OTFX-T` = 6** — the column at x=0x4d
should hold ~8 glyphs but VERIFY against `FUN_40068e00` at build time; fall back to `OTFXT`
if it clips.  Menu-array surgery itself is unchanged (still one spliced entry, still
`moveq #15→#16`); only `N_MODES 4` + the value strings + the `pre`/`pre_v` gate branches change.

### Session 14 tooling (uncommitted)
`tools/emu_otfx.py` (runs the real `FUN_40004db8` — ALL GOOD),
`tools/ghidra/attic/GhidraOTFX{1,2,3}.java`, dumps `out/ghidra/GhidraOTFX{1,2,3}_session14.txt`.
No `patch_*` / `build_*` changes yet.

---

## Session 15 (2026-09-02, `wip/mute-mode`, RE / feasibility only) — "DIRECT JUMP" pattern-change mode

### The ask (user)

An Elektron-style **Direct Jump** option for manually sequencing patterns: selecting a new
pattern switches to it **immediately** (not quantised to the pattern boundary), and playback
**continues from the step position the previous pattern was at** instead of restarting at
step 1. The new pattern's **Part loads instantly** on the switch (opposite of the shelved
LAZY PART mod — and note LAZY PART is *not* in the current `build_mutemode.py` build, so
instant Part load is already the stock behaviour here).

### Verdict: FEASIBLE, medium effort. All in already-mapped territory (sequencer engine,
### Sessions 3–6). No DSP RE, no new subsystem.

### Function / global map found this session (Ghidra headless, `tools/ghidra/attic/GhidraDirectJump{,2,3,4,5}.java`)

| symbol | role |
|---|---|
| **`FUN_400a0570(bank, pat, loopStart, loopEnd, p5)`** | **the cue-pattern primitive — single choke point for every pattern change** (manual trig, arranger, chain). If `_DAT_800065b8==1` (sequencer running): stashes the pending pattern in `DAT_800065bf/c0`, loop points in `_DAT_80006630/34`, posts a kernel event, returns — the actual switch happens later in the step engine. If stopped: writes the ACTIVE `DAT_800065bd/be` directly + tempo + Part path. Called from `FUN_4004a100` (arranger row) and `FUN_4004a654`; the manual PTN-key path funnels here too. |
| `DAT_800065bd` / `DAT_800065be` | **ACTIVE** (playing) bank / pattern |
| `DAT_800065bf` / `DAT_800065c0` | **CUED** (pending) bank / pattern |
| `DAT_800065bc` | plays-free per-track "SEQ SYNC PICKUP" pattern (`FUN_400618d8`, `FUN_4004b040`) — NOT the general cue |
| **`_DAT_800065b4`** | **master step position — reset to 0 in every pattern-reload block** |
| **`_DAT_800065b6`** | current pattern length − 1 (reloaded from the scale tables `DAT_400aba50` / `DAT_400e21e0+…+0x8e54` in every reload block) |
| `_DAT_800065b2` | secondary / loop-region counter (reset to 0 or `_DAT_8000663a`) |
| `_DAT_800065d3 .. _DAT_800065e3` | per-track step positions (8 bytes), reset in the same blocks |
| `DAT_80006687` ← `DAT_80006688` | CHAIN-AFTER countdown / its reload value |
| `_DAT_80006514` | second countdown (chained-list / arranger path) |
| `_DAT_46c8028a` | "reload now" flag — gates the **immediate** reload block in the step engine |
| **`FUN_400a1eea`** (per-step engine, 12 KB) | holds **3+ near-identical pattern-reload blocks**, gated respectively on `DAT_80006687→0`, `_DAT_80006514→0`, `_DAT_46c8028a≠0`. **Each block: `_DAT_800065b4 = 0` (step reset) + reload `_DAT_800065b6` (length) + re-init per-track note/voice scratch + load Part/scene arrays** from `DAT_400e21e0 + bank*0x9b340 + pat*0x8ed8`. |
| `FUN_400a1030(bank, pat)` | commit-pattern-to-active, called from the step engine's boundary handler `candidate_400a10d2` |
| `FUN_400a0ef8` / `FUN_400a0734` | compute `DAT_80006688` (the countdown) from the CHAIN AFTER value / arrangement row (`param_1*6` units) |
| `FUN_400866c4` | project text-state parser; has the `PATTERN_CHANGE_CHAIN_BEHAVIOR=` case (also `PATTERN_CHANGE_AUTO_SILENCE_TRACKS`) |
| menu strings (file offsets) | `0xb5b28` "PATTERN CHANGE", `0xb5b37` "CHAIN AFTER", `0xb5b43` "SILENCE TRACKS", `0xb7487` "CHAIN BEHAVIOR", `0xb74d6` "PLAYS FREE" |

### Why it's feasible

1. **One choke point.** `FUN_400a0570` is where *every* pattern cue lands — one hook covers
   manual trigs, chains, arranger.
2. **The reload machinery already exists** (3 copies in `FUN_400a1eea`). Direct Jump does not
   need new switch logic; it needs to (a) make a reload fire *now* instead of at the boundary,
   and (b) not zero `_DAT_800065b4`.
3. **Step position is a plain fast-RAM global** (`_DAT_800065b4`, + the 8-byte per-track
   array). Save/restore-with-modulo around the reload block is exactly the stub idiom already
   used by LAZY PART (`tools/patch.s`) and sticky scenes (`patch_scene2.s`).
4. **Instant Part is free** — the reload block loads the new Part as part of a normal pattern
   change, which is what the user wants.
5. **Menu surgery is proven** — the MUTE MODE PERSONALIZE entry (Session 10,
   `patch_mutemode.s` + `build_mutemode.py`: relocate label array, bump count, splice entry)
   is the template.

### DEEP TRACE (2nd pass, same session) — the real pattern-switch path

The 3 countdown-gated reload blocks in `FUN_400a1eea` (`DAT_80006687→0`, `_DAT_80006514→0`,
`_DAT_46c8028a≠0`) are the **arranger / RELOAD / chained-list** paths. A **plain manual
pattern change while running does NOT use them.** It is handled inline in the **main per-step
handler** of `FUN_400a1eea` (~`0x400a3f80`–`0x400a4bd0`):

```
per-step tick:
  DAT_800065b6 = DAT_800065b6 + 1                       ; advance master step   (0x400a3fa? )
  if (DAT_800065b6 >= DAT_400aba50[DAT_8000663d])       ; >= pattern length
      DAT_800065b6 = 0                                   ; wrap
  if (DAT_800065b6 == 2)  -> FUN_4009e884(pending)       ; 2-steps-early PART preload
  else if (DAT_800065b6 == 0) {                          ; *** pattern boundary ***
      _DAT_800065b4 = _DAT_800065b2 ; _DAT_800065b2++    ; bar counter
      DAT_8000663d  = pattern scale index (reloaded)
      iVar19 = DAT_400d80dc[ chainBehaviour ]            ; CHAIN AFTER interval  (table below)
      iVar5  = (pending pattern != active)               ; "real change pending"
      if ( switch-point reached, gated on _DAT_800065b2 % iVar19 ) {
          DAT_800065c1/c2 = old active pat/bank          ; remember outgoing (for "keep source part" test)
          DAT_800065be = DAT_800065c0                    ; *** COMMIT pending -> active pattern ***  (~0x400a43xx)
          DAT_800065bd = DAT_800065bf                    ;                       bank
          _DAT_80006628 = _DAT_80006630 (= loop start)   ; base for the per-track position math
          _DAT_8000662c = _DAT_80006634 (= loop end)
          FUN_40000c3c(0x460d17ae, &DAT_400d8167/69/6b)  ; notify UI (pattern/part LEDs)
          ... rebuild scene masks, mute masks ...
          iVar6 = _DAT_80006628 * patternLen             ; absolute tick base  (~0x400a4a??)
          for t in 0..7 (audio) then 0..7 (MIDI):        ; recompute per-track positions
              DAT_800065e4[t] (audio step-in-track), DAT_800065f4[t] (MIDI),
              companion arrays DAT_80006604/14, DAT_800065c3/cb   ; all from iVar6 % trackLen
          _DAT_800065b2 = _DAT_8000662a
          DAT_800065b6 = 0                               ; *** master step reset to 0 ***
      }
  }
```

**`DAT_400d80dc` (CHAIN AFTER lookup, u32):**
`[0]=-1(PLEN) [1]=1 [2]=2 [3]=3 [4]=4 [5]=6 [6]=8 [7]=12 [8]=16 [9]=24 [10]=32 [11]=48
[12]=64 [13]=96 [14]=128 [15]=192 [16]=256` (then a MIDI-clock variant `[17..]`). Global
setting = `DAT_8000004e`; per-pattern override = pattern blob `+0x8e56` (used if ≥ 0).
`-1`/PLEN → gate uses the pattern's own length; `N` → gate is `barCounter % N == 0`.
**No stock value switches mid-pattern** — the commit is unconditionally inside the
`DAT_800065b6 == 0` (boundary) branch.

**Corrected global roles:**
- **`DAT_800065b6`** (byte) = **master step position** (0..len-1, wraps). *This* is the one to
  preserve. (Earlier pass mislabelled `_DAT_800065b4` as the step counter.)
- `_DAT_800065b4` (word) = latched bar counter (`= _DAT_800065b2` at each boundary).
- `_DAT_800065b2` (word) = running bar counter.
- `DAT_800065d3..e2` (16 B) = per-track "step limit" (len-1), filled by the countdown blocks.
- `DAT_800065e4[8]` / `DAT_800065f4[8]` (u16) = per-track current step (audio / MIDI),
  recomputed at the boundary from `_DAT_80006628 * len`.
- `_DAT_80006628` / `_DAT_8000662c` = active loop-region start / end (in bars);
  `_DAT_80006630` / `_DAT_80006634` = the *pending* loop region (set by `FUN_400a0570`).

### Design (concrete)

**Trigger:** PERSONALIZE toggle `PTN CHG : NORM / DIR` — free battery-backed bit near
`0x800000dc` (MUTE MODE word). MUTE MODE menu surgery is the template
(`patch_mutemode.s` + `build_mutemode.py`).

**One detour, in the main per-step handler of `FUN_400a1eea`**, placed right after the
`DAT_800065b6 + 1` / wrap logic and before the `== 0` boundary test:

```
if (g_directjump_mode && sequencer running && pending pattern set && pending != active) {
    curStep = DAT_800065b6                      ; where we are right now
    DAT_800065b6 = 0                            ; force the boundary branch THIS tick
    _DAT_80006630 = <curStep expressed in the loop-start units>   ; so the per-track
    _DAT_80006628-feeding path                                    ; math resumes at curStep
    (bypass the CHAIN-AFTER gate: shadow chainBehaviour = index 1 / value 1, or
     patch the branch, for this one tick only)
}
```

Then an **exit stub** after the boundary handler: it has just set `DAT_800065b6 = 0` and the
per-track arrays for "start of pattern". Overwrite `DAT_800065b6 = curStep % newLen` and, if
the `_DAT_80006630` trick above did not already place them, fix
`DAT_800065e4[t]` / `DAT_800065f4[t]` = `curStep % trackLen[t]`. Clear the one-shot.

**Best case** (needs a build to confirm): setting `_DAT_80006630`/`_DAT_80006628` to the
current position *before* the boundary handler runs lets the firmware's own per-track math
(`iVar6 = _DAT_80006628 * len`, then `% trackLen` per track) produce the right positions —
then the exit stub only has to fix the master `DAT_800065b6`. That would make the whole
feature ≈ one detour + a ~15-instruction stub.

**Instant Part / scene:** free — the boundary handler already loads them on the commit. (And
LAZY PART is not in the `build_mutemode.py` build, so nothing to fight.)

**"Instant" vs "next step":** forcing the wrap makes the switch land on the next step tick
(≤ 1/16 at scale 16). Audibly identical to truly-instant for the sequencer (trigs fire on
step edges anyway); only a still-ringing voice from the old pattern differs — same as a
stopped→pattern-change on stock OT.

### Open items before a build

1. **Exact detour address + displaced bytes** in `FUN_400a1eea`'s per-step handler
   (`~0x400a3fa0`, just after the `DAT_800065b6++`/wrap). Needs a clean Ghidra *listing*
   (not decompile) of `0x400a3f80–0x400a4060` with bytes — the r2 disasm desyncs here
   (ColdFire), and `GhidraDJ10`'s listing-dump approach returned nothing (fix: disassemble
   the function first, iterate `getInstructions(body)`).
2. **CHAIN-AFTER gate bypass** for the forced mid-bar boundary — cleanest is a per-tick
   shadow of `DAT_8000004e`/`+0x8e56` = index 1; confirm nothing else reads it in that window.
3. **`_DAT_80006630` units** — is it bars, steps, or ticks? Determines whether the
   "feed current position as loop-start" shortcut works or the exit stub must rebuild the
   per-track arrays. Read the boundary handler's `iVar6 = _DAT_80006628 * len` site and
   `FUN_4009e884`.
4. **Per-track PLAYS-FREE / per-track-scale tracks** — those keep independent positions
   (`DAT_800065d3..e2` limits, separate advance); decide whether DIRECT JUMP preserves them
   (same modulo) or lets them re-home.
5. **Scope v1** to manual pattern selection only — do not change ARRANGER or pattern CHAIN
   (their commits share this handler but are gated differently; the `g_directjump` check must
   also require "not in arranger mode": `DAT_800065bc == -1` / arranger-active flag).
6. Emulation harness (`tools/emu_directjump.py`) before flash, per project norm — though
   `FUN_400a1eea` has Unicorn-unsupported instrs (like the frame builder), so the harness may
   only be able to exercise the stub in isolation with a hand-built state, as `emu_otfx.py` did.

### Staged build plan

- **S1 — menu toggle only.** `PTN CHG : NORM/DIR` PERSONALIZE entry, stored + read back, does
  nothing yet. Independently flashable, zero audio risk. Confirms the menu surgery (value
  column width: `DIR` = 3 chars, fine).
- **S2 — crude trigger, no position preservation.** Detour forces the immediate boundary
  when DIR + pending. Expect: pattern switches instantly but restarts at step 1 (like DIRECT
  START). HW-test that the switch is glitch-free and the Part swaps.
- **S3 — position preservation.** Add the `_DAT_80006630` pre-set + exit stub. HW-test that
  playback continues at the playhead, incl. shorter destination pattern.
- **S4 — polish.** Per-track plays-free handling, arranger/chain exclusion, edge cases.

### Session 15 continued — address-level map of the per-step switch, and S1 built

**Full listing of `FUN_400a1eea`'s per-step handler** (`out/ghidra/GhidraDJ12_session15.txt`,
via `GhidraDJ12.java` — the working listing-dump recipe: `dec.decompileFunction` first to
force disassembly, then `lst.getInstructions(body)`):

| addr | what |
|---|---|
| `0x400a3f94` | `moveq #1,D0 ; cmp.l (0x800065b8).l,D0 ; bne.w 0x400a4d36` — bail unless running |
| `0x400a3fdc` | `move.b (0x800065b6).l,D0 ; addq.l #1,D0 ; move.b D0,(0x800065b6).l` — **advance master step** |
| `0x400a3ff8` | `cmp.l (0x0,A0,D1*4),D0` (A0=`0x400aba50`, D1=`DAT_8000663d` scale idx) `; blt 0x400a4006` |
| `0x400a3ffe` | `clr.b D1 ; move.b D1,(0x800065b6).l` — **wrap step → 0** |
| `0x400a4006` | `tst.b (0x8000667e).l ; beq.w 0x400a412e` — `8000667e`≠0 = "stop after this pattern" path |
| `0x400a412e` | `move.b (0x800065b6).l,D1 ; moveq #2,D4 ; cmp.l D0,D4 ; bne.w 0x400a421a` — step==2? |
| `0x400a413e`–`0x400a421a` | **step==2**: CHAIN-AFTER gate (below); on switch-point → `FUN_4009e884(pendBank,pendPat)` @`0x400a4210` = **2-steps-early Part preload**, then `bra 0x400a4b9a` |
| `0x400a421a` | `tst.b D1 ; bne.w 0x400a4b9e` — **step==0?** (D1 = step); else it's a mid-pattern tick → `LAB_400a4ba0` |
| `0x400a4220`–`0x400a4466` | **step==0**: latch bar ctr (`800065b4=800065b2`, `800065b2++`), rebuild the same CHAIN-AFTER gate |
| `0x400a4310` | `D1 = A4[0x8e56]` (per-pattern CHAIN override, `<0` → use global `mvs.b (0x8000004e).l`) |
| `0x400a4316` | `D4 = DAT_400d80dc[D1*4]` — CHAIN interval value |
| `0x400a4358`–`0x400a439c` | gate: `divsl.l D4,D0:D1` → `(barctr+1) % interval == 0` → switch; PLEN (D4≤0) → `len <= barctr+1` |
| `0x400a4466` | `mvs.b (0x800065c0).l,D0 ; cmp #-1 ; bne 0x400a44a0` — pending pattern set? |
| **`0x400a44d0`** | `move.b D1,(0x800065be).l` / `0x400a44dc: move.b (800065bf),(800065bd)` — **THE COMMIT** (pending pat/bank → active). `800065c1/c2` first hold the *outgoing* pat/bank. |
| `0x400a44e2` | `_DAT_80006638 = _DAT_80006628 = _DAT_80006630` ; `_DAT_8000662c = _DAT_80006634` — loop region ← pending loop region (set by `FUN_400a0570`, normally start=0) |
| `0x400a4548`,`0x400a459a` | post UI msgs `0x400d8167` (pattern LED), `0x400d8169` (part LED, part idx = `DAT_400eb037[bd*0x9b340 + be*0x8ed8]`) |
| `~0x400a4700`–`0x400a4a40` | *(gap not dumped)* sets `DAT_800065b6 = 0`, computes `iVar6 = _DAT_80006628 * patternLen`, per-track loop base = stack local `0x3c(SP)` |
| `0x400a4aa2` | `move.l (0x3c,SP),D0 ; divsl.l D1,D0:D0 ; move.w D0,(A0)` — **per-track step** (`DAT_800065f4[t]` MIDI, `DAT_800065e4[t]` audio) = base / trackLen; remainder → `DAT_80006614[t]` |
| `0x400a4b9e` | `clr.l D6` |
| **`0x400a4ba0`** | `LAB_400a4ba0` — **common tail, reached from switch AND no-switch**: per-track loop that decrements `DAT_800065c3[t]`, fires `FUN_400a536c(t)` (trig) when it hits 0, refills `DAT_800064d0[t]` |

**`_DAT_80006628`/`_DAT_80006630` are in units of *loop repeats*** (`base = _DAT_80006628 *
patternLen`), so they cannot express "resume at step N" directly — the per-track math is
loop-granular; step-within-loop lives only in `DAT_800065b6` + the per-track step counters.
→ **position preservation = an exit stub** that, after the boundary handler has homed
everything to step 0, rewrites `DAT_800065b6 = savedStep % newLen` and the 16 per-track
`DAT_800065e4[]`/`DAT_800065f4[]` (+ companions `DAT_800065c3[]`, `DAT_80006604/14[]`).

**Storage word for DIRECT JUMP = `0x800000a8`** — the last free battery-backed PERSONALIZE
word (0xd4/d8/dc taken); **zero refs in the stock image** (verified). 0 = stock.

### S1 BUILT (menu only) — `tools/patch_directjump.s` + `tools/build_directjump.py`

`PERSONALIZE → DIRECT JUMP : OFF / ON`, stored in `0x800000a8`, read back — **no behaviour
change yet**. Same menu surgery as `build_mutemode.py` (3 arrays relocated to
`0x400d7600/60/c0`, one entry spliced at index 2, `moveq #15→#16` @`0x40068fb2`, 5 refs
repointed from the symbol table). Carries the Bug-1 fix (`patch_trigscale`, byte-identical).
→ `out/OCTATRACK_OS1.40C_DIRECTJUMP.{syx,bin}` (`140C_KYOTI`), 385 B changed vs stock,
round-trip checksum ok. **Not flashed.** Purpose: confirm the menu + the new storage word on
the MKI before the audio hooks go in.

### MIDI Program Change on a pattern change (RE'd) — `FUN_4009e884(bank, pat)`

Stock: the step engine calls `FUN_4009e884` from the **step == 2** branch (`0x400a4210`),
i.e. **2 steps before** the pattern boundary, only when the CHAIN-AFTER gate says a switch
is coming. `FUN_4009e884`:
- gated on `0x8000002b` bit 1 = `MIDI_PROGRAM_CHANGE_SEND`; channel from `0x8000002c`
  (`MIDI_PROGRAM_CHANGE_SEND_CH`, -1 = AUTO → derived from the sounding tracks' channels).
- absolute pattern number = `pat + bank*16`, `& 0x7f`.
- emits **Bank Select CC** (`0xB0|ch`, from `0x400d80d8`, 3 B) then **Program Change**
  (`0xC0|ch`, from `0x400d80d6`, 2 B) via `FUN_40010bc8`.
- **de-duplicates per channel** (`46c7a9b6[ch]` PC cache, `46c76100[…]` bank cache) — a
  repeated PC for the same channel is suppressed.

**DIRECT JUMP handling (S2):** forcing the switch skips the step==2 branch, so Hook A sends
the PC itself. It sends **once per distinct pending pattern**, on the first tick it sees the
cue, and forces the actual switch on the **next** tick → the PC leads the audio switch by
one step (~30–125 ms). Rapidly flipping A→B→C before the switch: each new pending pattern
gets its PC (`FUN_4009e884` dedup means an unchanged one is a no-op), and only the pattern
that is still pending when the commit fires actually engages.

### S2 + S3 BUILT — `tools/patch_directjump.s` (3 hooks) + `tools/build_directjump.py`

The `DIRECT JUMP` toggle now gates three detours into `FUN_400a1eea`. Free scratch
`0x80006a40..42` (`G_ARMED` / `G_STEP` / `G_PCPAT`). All inert when the toggle is 0, when
the arranger (`0x460d1aec`) is running, or when a pattern chain (`0x80006546`) is running.

| hook | site | displaced | what it does |
|---|---|---|---|
| **A** `dj_a` | `0x400a4006` | `tst.b (0x8000667e).l` (6 B → jsr) | `movem` all regs. If DJ on + real pending manual switch: send the Program Change once per distinct pending pattern (`jsr FUN_4009e884`), keep `G_STEP` = current `DAT_800065b6` fresh. Tick 1 → set `G_ARMED`. Tick 2 (armed) → `clr.b DAT_800065b6` so the step==0 body runs this tick. Restore regs, run the displaced `tst.b`, `rts` (Z flag intact for the caller's `beq.w`). No pending / DJ off → `clr G_ARMED`. |
| **B** `dj_b` | `0x400a42fa` | `move.l #0x8e56,d0` (6 B → jsr) | If `G_ARMED`: overwrite the jsr return address with `0x400a43a0` (the "switch confirmed" label) and set `D6 = 1` — bypasses the CHAIN-AFTER gate (incl. the per-pattern `+0x8e56` override) for this one tick. Else: run the displaced `move.l #0x8e56,d0`. |
| **C** `dj_c` | `0x400a4840` | `clr.b d0 ; move.b d0,(0x800065b6).l` (8 B → jsr+nop) | Not armed → `DAT_800065b6 = 0` (stock). Armed → `newLen = LEN_TBL[ PAT_SCALE[newBank*0x9b340 + newPat*0x8ed8] ]`; set both `D7` and `DAT_800065b6` to `G_STEP % newLen`. Because the switch body derives every per-track position from `D7` (`D5=D7-1`, `(0x3c,SP)=D7-1`, `divsl trackLen`), overriding `D7` resumes all 16 tracks at the playhead (each mod its own length) — no need to touch the per-track arrays. `clr G_ARMED`. |

**PC timing:** Hook A sends the PC on tick 1 and forces the switch on tick 2 → 1 step of
lead (~30–125 ms). Tunable (add a 2nd arm phase for a 2-step lead like stock).

**Verified — `tools/emu_directjump.py` : ALL GOOD.** Each stub run in isolation on a
hand-built state (`FUN_400a1eea` won't run under Unicorn): OFF path, arranger/chain guards,
2-tick arm→commit, PC send + dedup + resend-on-flip, register save/restore, gate-bypass
return rewrite + `D6`, playhead resume incl. shorter/longer destination pattern.
(unicorn-m68k doesn't set CCR on `tst.b (abs).l` — the caller's `beq.w` Z flag is correct by
construction: `dj_a`'s last op before `rts` is the verbatim stock `tst.b`.)

Build: `python3 tools/build_directjump.py` → `out/OCTATRACK_OS1.40C_DIRECTJUMP.{syx,bin}`
(`140C_KYOTI`), 684 B vs stock, round-trip ok, Bug-1 fix byte-identical. **NOT FLASHED.**

### HW-only unknowns for the S2/S3 flash

1. **Register liveness across Hook B's skip.** Armed path jumps `0x400a42fa → 0x400a43a0`,
   skipping `0x400a4300–0x400a439c` (pure CHAIN-gate computation — no memory writes, only
   `jsr FUN_40033968` which is a plain read). A2 (ping-pong ptr) and A3 are set *before*
   `0x400a42fa`; D0–D4 are reloaded at `0x400a43a0+`. Believed safe; confirm no glitch/hang.
2. **`FUN_4009e884` from Hook A's context** — it's already called from this same handler at
   `0x400a4210`, so the context is fine; confirm the PC actually goes out and lands on the
   right channel with `MIDI_PROGRAM_CHANGE_SEND` on.
3. **Per-track PLAYS-FREE / per-track-length patterns** — Hook C's `newLen` ignores the
   `DAT_400eb035` per-track-length flag; worst case the master step is briefly out of range
   and the next tick's wrap (`0x400a3ff8`) clamps it. Confirm no audible artefact.
4. **`G_ARMED` power-up garbage** — Hook A runs before B/C every playing tick and disarms
   when there's no valid pending, so B/C never see stale garbage. Confirm.
5. **Feel** — is 1 step of PC lead / next-tick switch the right "in time" behaviour, or does
   it want the 2-step lead?

### Session 15 tooling (uncommitted)

`tools/ghidra/attic/GhidraDirectJump{,2,3,4,5}.java`, `GhidraDJ{6..14}.java`;
`tools/patch_directjump.s`, `tools/build_directjump.py`, `tools/emu_directjump.py`. Scratch
dumps `dj{1..13}.txt` + `a1eea.txt` in the session scratchpad. Committed dumps
`out/ghidra/GhidraDirectJump{1..4}_session15.txt`, `out/ghidra/GhidraDJ12_session15.txt`.


## Session 17 (2026-09-02, `wip/mute-mode`, RE / feasibility only) — external side-chain (key-track) input for the DynamiX COMPRESSOR

### The ask (user)

Add **side-chain compression** to the stock OT compressor. The user's vision: on a track that
has COMPRESSOR in an FX slot, pick **one of the 8 audio tracks as the "key"** whose level
drives the gain reduction on the target track; integrate the UI into the compressor FX
page(s); and offer an option to let the **key track feed the detector even when the key
track is muted**.

### What side-chain compression is (for the record)

A compressor lowers gain when its **detector** (a.k.a. sidechain) input rises above THRS.
Normally detector = the signal being compressed. *Side-chain / key* compression feeds the
detector from a **different** signal, so track A ducks in response to track B's dynamics —
the classic kick-keys-the-bass/pad "pumping". Real side-chain follows the key **audio's
envelope** continuously (responds to level, not just note events), and usually offers a
key filter / "listen". That last point is what separates it from Route 1 below.

### The hard structural fact — this is a DSP feature, not a ColdFire one

The DynamiX COMPRESSOR runs **on the DSP** (DSP56721, 2 cores), not the ColdFire. Confirmed
via octabam (`refs/octabam/docs/DSP.md` @ e1dcfa9): dispatch id `0x18` → process `P:0x01ab1`,
**180 words at `P:0x01aa4`**, control-flow contained (not a record-spanner). The ColdFire OS
— everything this repo patches — **never touches PCM samples**: it assembles per-track voice
parameters and DMAs frame blocks to the DSP (`0x400031a0` frame routine; `FUN_40001d4c`
uploads the DSP program at boot). Which signal the detector listens to is chosen *inside*
those 180 DSP words, from the effect's own track buffer.

⇒ **There is no ColdFire-only lever that changes what the detector hears.** You cannot build
an envelope follower on a CPU that never sees the audio. A *true* audio side-chain requires
**DSP56300 assembly** + a DSP build/flash pipeline — exactly the scope `COVERAGE.md` fences
off as "a separate project," and which **octabam has already built out** (for a reverb/delay
send bus), MKII-flashed only. Our own `out/dsp_region.bin` is extracted but **never
disassembled**.

### Three routes, ascending cost

**Route 1 — ColdFire-only "trig-synced ducking" (NOT real side-chain).**
The ColdFire *does* know when a track fires a trig (sequencer, mapped Sessions 3–6). Add a
per-track `DUCK FROM Tn / DEPTH / RELEASE` that applies a downward volume envelope to the
target, re-triggered by the key track's trigs — automation of the existing per-track AMP VOL
in the shipped patch style (menu + detour), no DSP work.
- ✗ It is *trigger* ducking, not *signal* ducking: fixed shape regardless of the key's actual
  level/content; nothing for a THRU/external key with no trigs, or a key whose loudness
  varies p-lock to p-lock; and it doesn't "feed the compressor" — it side-steps it.
- Does **not** match what the user described, but it is the only route that is cheap
  (~2–4 sessions) and carries no brick risk.

**Route 2 — DSP side-chain, key & target in the SAME bank of four (real, big).**
Reuse octabam's proven "fake a bus in shared Y scratch" mechanism (`refs/octabam/docs/BUS.md`,
`XBUS.md`):
1. **Key tap** — publish each track's pre-FX audio block into a per-track slot in absolute Y
   scratch (≥ `0x800`, the region octabam proved safe in both payloads). Cheapest: extend the
   **passthrough stub** `P:0x007c9` (runs for every FX slot set to `NONE`, id 0) to also do
   `in → keybus[myTrackSlot]`. Then the constraint is just "key track has one FX slot = NONE"
   (usually true). Alternatives: a dedicated `SC SEND` insert (costs a real FX slot on the
   key track, octabam-`send`-style), or — bigger — hook the frame builder so every track taps
   unconditionally.
2. **Modified COMPRESSOR module** — same 180-word engine, detector reads `keybus[KEY]` instead
   of / blended with `r0` when the new `KEY` param ≠ OFF. Needs the disassembly to find the
   detector tap point and to check the ~200-word / 2,724-word-payload budget for the extra
   branch + a one-pole key HP.
3. **ColdFire menu** — add page-2 params to the COMPRESSOR descriptor (`E = 0x400d5a4a`,
   `E_0x96 = 0x400d5ae0`). Page 2 currently holds only `RMS` at slot index 6; ~5 page-2
   encoder slots are free — **pure data** per octabam `PARAM_PAGES.md` §5 (names, ranges,
   the per-parameter enable bitmap at `P+0x18a`/`P+0x18e`, `P = E + 0x38`). Add `KEY`
   (OFF / T1..T8) and optionally `SC SRC` (PRE / POST-mute).
- **Same-core constraint**: core 0 = tracks 5–8, core 1 = tracks 1–4 (octabam, measured,
  inverted from the natural guess). A pair split across that boundary needs the **cross-core
  accumulator** with octabam's 4-rotating-buffer race fix — three hardware-only race bugs,
  months of MKII bring-up (`XBUS.md`). So v1 = "key and target must both be in T1–4, or
  both in T5–8."
- **"Feed even when muted" is essentially free and is the natural behaviour.** Stock mute
  only zeroes the post-FX MAIN output word (`FUN_40004dbc`, our soft-mute finding, Session 9);
  the muted voice still renders and its pre-FX audio still exists on the DSP. Tap pre-mute-gate
  ⇒ a muted key still keys. The `POST` option (mute also kills the key) is the one that would
  need the extra gate.
- **Effort**: this is the largest single piece of work in the project's history. Prereq:
  stand up a DSP56300 toolchain — disassemble `out/dsp_region.bin` (octa-bt-pt points at
  `vendor/dsp56300/.../dsp56kDisassemble -le`; octabam has a full assembler + `tools/dsp_host`
  emulator + collision-checked build). Then module dev + emu + **DSP flash to the user's
  only MKI** — untested territory: octabam DSP images are MKII-flashed only, though the stock
  1.40C file is **byte-identical MKI/MKII** (our `ARCHITECTURE.md` + octabam `FLASHING.md`),
  so addresses should line up and the image is "plausibly compatible" — an MKI owner is the
  test pilot. Estimate **8–15+ sessions**, with brick risk that the ColdFire menu patches
  don't carry.

**Route 3 — full any-key → any-target (8×8).** Route 2 + octabam's cross-core XBUS machinery.
Much more; the cross-core races are only diagnosable on hardware.

### Recommendation

- Want it soon / low-risk → **Route 1**, sold honestly as trig ducking, not side-chain.
- Want the real thing → **Route 2, scoped to same-bank pairs.** It is a "commit to a DSP
  toolchain" decision. Best done leaning on octabam's existing toolchain rather than
  rebuilding it. **Cheap first step, worth doing regardless of route:** disassemble
  `out/dsp_region.bin` / run octa-bt-pt's `dsp_modmap.py` against
  `out/raw/section_3_MAIN_OS.bin`, locate our COMPRESSOR's 180 words, confirm octabam's
  `P:0x01aa4` lines up in our image.

### Open questions before any Route-2 build

1. Confirm MKI DSP payload addresses == octabam's MKII numbers (stock file byte-identical ⇒
   very likely; verify `P:0x01aa4` COMPRESSOR against our own extract).
2. Does a DSP effect know its own track index (to index `keybus[slot]`)? octabam derives
   dispatch *position* from `r7` (`r7 == 0x6200` = core-0 position 0); need the full
   `r7 → track` map including the core split.
3. Detector tap point inside the 180 words + is there program-space headroom for the
   source-select branch and a key filter (2,724 words/payload shared budget).
4. COMPRESSOR page class is `0x400328e4` (shared with DJ EQ / reverbs / LO-FI) — confirm a
   new page-2 slot renders + p-locks like RMS does.

### Session 17 continued — the "cheap first step" DONE: octabam's DSP map lands 1:1 on our MKI image

Ran octabam's `tools/dsp_modmap.py` (self-contained, dep-free — only needs
`out/raw/section_3_MAIN_OS.bin`) unmodified against our image. Results:

- **Our stock image SHA256 = `164f31224bf61181e3f50e7dec40df9afcae5b16dbf6e4c0d0cc5e986af0a84e`**
  — the same hash NOTES L150 recorded, and the same bytes octamax/octa-bt-pt/octabam
  analysed. MKI and MKII ship the byte-identical 1.40C file (confirmed, not just inferred).
- The load-map parser consumes **100 %** of both DSP payloads (A @ `0x400e2324`, 79 563 B,
  98 modules, 26 221 words · B @ `0x400f59ef`, 77 061 B, 91 modules, 25 408 words). Field
  order `ac` = `(addr, count)`.
- **Dispatch table `X:0x215` (64 words = 32 init @ `0x215` + 32 process @ `0x235`) decodes
  cleanly and matches octabam's `DSP.md` table entry-for-entry** in payload A:

  | id | init | process | effect | | id | init | process | effect |
  |---|---|---|---|---|---|---|---|---|
  | 0x04 | P:0x007d1 | P:0x007dd | FILTER | | 0x14 | P:0x01000 | P:0x01055 | PLATE |
  | 0x05 | P:0x00aa8 | P:0x00ab2 | SPATIALIZER | | 0x15 | P:0x01252 | P:0x012be | SPRING |
  | 0x0c | P:0x00bad | P:0x00bb2 | EQUALIZER | | 0x16 | P:0x01679 | P:0x0171b | DARK |
  | 0x0d | P:0x01d71 | P:0x01d7d | DJ EQ | | **0x18** | **P:0x01aa4** | **P:0x01ab1** | **COMPRESSOR** |
  | 0x10 | P:0x00cc7 | P:0x00cd8 | PHASER | | 0x19 | P:0x007c8 | P:0x007c9 | MULTIBCOMP → null stub |
  | 0x11 | P:0x00d96 | P:0x00da3 | FLANGER | | 0x1c | P:0x01b58 | P:0x01b75 | LO-FI |
  | 0x12 | P:0x00eb7 | P:0x00ed7 | CHORUS | | 0x08 | P:0x007c8 | P:0x007c9 | DELAY → null stub |
  | 0x13 | P:0x01eca | P:0x01edc | COMB | | 0x00 | P:0x007c8 | P:0x007c9 | (null passthrough stub) |

- **COMPRESSOR module confirmed**: `P:0x01aa4`, **180 words** (image `0x400f47d1`) in payload
  A; `P:0x01864`, 180 words (image `0x40107722`) in payload B. Both dispatch tables point at
  it for id `0x18`. Payload B's whole effect block sits `0x210` lower than A's (smaller
  prologue) but is otherwise the same layout — octabam's "B differs in address, not
  structure" holds.
- The **null passthrough stub** octabam's Route-2 key-tap idea wants to extend is confirmed
  present at `P:0x007c8` (init) / `P:0x007c9` (process) in payload A — 9 words — the target
  of ids 0x00, 0x08 (DELAY), 0x19 (MULTIBCOMP) and every other unimplemented id.

**Verdict on the cheap step: octabam's entire DSP address map transfers to our MKI image
verbatim. No re-derivation needed.** A Route-2 build can reuse octabam's `dsp_modmap.py` /
`dsp_disasm_all.py` / `dsp_host` toolchain directly.

**Not done (belongs to Route 2 proper):** instruction-level disassembly of the 180
compressor words — needs the DSP56300 disassembler (`vendor/dsp56300/.../dsp56kDisassemble`,
the Virus-emu tool; octabam's `make setup` builds it: Homebrew + cmake + clone/build the
`dsp56300` C++ emulator). That build is the real first task of Route 2, not part of the
confirmation.

### Artifacts (all gitignored under `out/dsp/`, regenerable)

- `out/dsp/payload_A.mem`, `payload_B.mem` — flat `<u8 space><u32 addr><u32 count>` + u32
  words per module, terminator `space==0xff` (octabam `--dumpmem` format; feeds `dsp_host`).
- `out/dsp/A_P01aa4_compressor.bin` (540 B), `out/dsp/B_P01aa4_compressor.bin` — the raw
  180-word COMPRESSOR module, LE 24-bit words. Disassemble once the toolchain exists:
  `dsp56kDisassemble -in out/dsp/A_P01aa4_compressor.bin -pc 1aa4 -le`.
- Regenerate: `python3 refs/octabam/tools/dsp_modmap.py [--dumpmem A out.mem | --extract A 1aa4 out.bin]`
  from the repo root (needs `refs/` synced — `python3 tools/refs/sync.py`; octabam pinned
  at `e1dcfa9`).

### Session 17 continued (2) — DSP56300 disassembler built + the COMPRESSOR fully reversed

**Toolchain:** built `vendor/dsp56300/build/source/disassemble/dsp56kDisassemble` from
`github.com/dsp56300/dsp56300` (`--depth 1`, no patches, target `dsp56kDisassemble` only —
minimal surface; the MPYRI emu-patch + `dsp_host` are only needed for *emulation*, add later
if Route 2 goes ahead). Build script: scratchpad `dsp_toolchain_setup.sh`; log
`out/dsp/toolchain_setup.log`. Ran by the user in Terminal (auto-mode blocks the clone/brew).
`vendor/` is gitignored.

**Full disassembly saved:** `out/dsp/A_P01aa4_compressor.asm` (payload A, `-pc 1aa4 -le`),
`out/dsp/B_P01864_compressor.asm` (payload B — byte-identical logic, relocated). 180 words,
one `rts`-terminated init + one process routine, ABI = octabam's stub contract
(`r0` in / `n7` samples / 2 interleaved channels; writes output back in place via `r0`).

**Null passthrough stub `P:0x007c9`** (the Route-2 key-tap host) disassembled — confirms
`move r0,r1 ; do n7 { a=x:(r0)+ ; b=x:(r0)+ ; x:(r1)+=a ; x:(r1)+=b } ; rts`. 9 words,
in-place, exactly the ABI spec.

#### COMPRESSOR process routine — the six stages (`0x1ab1`–`0x1b57`, payload A)

| # | addr | what | params read |
|---|---|---|---|
| 0 | `1ab1` | `move r0,n6` — **anchor the true input pointer** (used again in 4 & 6) | — |
| 1 | `1ab3`–`1ab9` | **detector input**: `x:(r0)+` stream → square (`mpy x0,x0`) → running pair-max (`maxm`) → write n7 power samples to **Y:0x61+** | — (reads `r0` audio) |
| 2 | `1aba`–`1ac2` | read page-2 param, `asr #$10` → 0..127, index one-pole coeff `x:(param+$7811)` | **`r6+$c`** = RMS (detector time-constant) |
| 3 | `1ac3`–`1aca` | leaky-integrator RMS smoother `a += k·(det−a)` over Y:0x61 → smoothed 48-bit env to **Y:0x62+**; state in `r7+$15/$19` | — |
| 4 | `1acb`–`1afb` | **gain curve**, per sample: `clb/normf` + `LOG[$6c00+m]` → log(env); `− THRS·k`; `× RAT-slope` (`maci #$c04000`); `clr ifmi` (knee floor); `EXP[$7400+frac]` → linear; store gain → Y:0x62+ | **`r6+$2`=THRS, `r6+$3`=RAT** |
| 5 | `1afb`–`1b1c` | attack/release ballistics on the gain: coeff `ATK=X[$7811+p·$80]` (`r6+$0`), `REL=X[$7891+p·$80]` (`r6+$1`); `cmp`→pick ATK if gain rising else REL; one-pole; state `r7+$11/$12` | **`r6+$0`=ATK, `r6+$1`=REL** |
| 6 | `1b1d`–`1b57` | `move n6,r0` (**re-anchor**); wet = in·gain → Y:0x40+; makeup `r6+$4`²; dry/wet from `r6+$5`; per-block coeff ramp (Y:0xc8+, `r7+$1a/$1b`, first-block gate `r7+$f` bit0); mix wet+dry → **write back to `r0` in place** | **`r6+$4`=GAIN(makeup), `r6+$5`=MIX** |

**Parameter map (confirms octa-bt-pt registry):** `r6+$0` ATK · `+$1` REL · `+$2` THRS ·
`+$3` RAT · `+$4` GAIN · `+$5` MIX (page 1) · `+$c` RMS (page 2, slot 6).
Coeff tables (payload-A X): `0x7811` attack/RMS, `0x7891` release, `0x6c00` log, `0x7400` exp.
State block: `r7+$f` flags (bit0 = first-block), `+$10` const `0x20c5`, `+$11/$12` gain
ballistics, `+$13` unused?, `+$15/$19` detector env (48-bit), `+$1a/$1b` mix ramp.

#### ⇒ The Route-2 sidechain tap point is now known

Stage 1's detector reads **`x:(r0)+`** (the effect's own input). The dry/wet path in stages
4 & 6 does **not** use r0's post-loop value — it re-loads `move n6,r0` — so **detection and
gain-application are independent passes over the buffer.** Redirecting *only* stage 1's read
(`0x1ab3` + the `x:(r0)+` in the `0x1ab6/0x1ab7` loop) to a shared-Y `keybus[KEY]` buffer
keys the compressor off another track **with zero effect on its dry signal** — the cleanest
possible tap. DSP-side delta ≈ a handful of words: a source-select branch gated on a new
`KEY` param (free page-2 slot `r6+$d`), plus optionally a 1-pole HP on the key. Fits the
180-word module or a small cave (payload budget ~2,724 words).

**Still open (unchanged):** (a) the *publish* side — who fills `keybus[t]` with track t's
pre-FX audio. Extending the null stub `P:0x007c9` is trivial (`a,y:(r_kb)+`) but only fires
for FX slots = NONE; unconditional tap = frame-builder hook (bigger, unmapped). (b) same-DSP-
core constraint (key+target both T1–4 or both T5–8). (c) `r7 → track index` map for
`keybus[slot]`. (d) MKI DSP flash is untested territory.

### Session 17 continued (3) — user design constraints for the side-chain build

**1. MKI DSP flash de-risked.** User saw a MKI owner flash octabam (a DSP-patched image)
with no problems. Open item (d) "MKI DSP flash is untested territory" downgraded from a real
risk to "very likely fine" — the stock 1.40C file is byte-identical MKI/MKII and now there's
a field data point. Still our own first DSP flash, so treat with the usual care, but not a
blocker.

**2. Key filter — LOW-pass, not the reflexive high-pass.** My earlier "1-pole HP on the
key" was the *internal* side-chain convention (comp keys off its own full-range signal →
HPF the detector ~80–150 Hz so bass energy doesn't dominate). This feature is an **external
key**, and the user's instinct is right: keying off a full-spectrum drum loop and wanting
*only the kick* to drive the ducking calls for a **LPF / band-pass around the kick band**
(~50–120 Hz), rejecting hats/snare. Decision: **one bipolar `KEY FLT` knob** — `64` = off,
turn down = LPF sweeping ~2 kHz→40 Hz (isolate the thump), turn up = HPF sweeping
40 Hz→2 kHz (the classic detector HPF, still available for whoever wants it). Covers both
in one page-2 slot. DSP: 2-pole (12 dB/oct) state-variable on the key stream ≈ 20–30 words
(1-pole is too gentle to pull a kick out of a loop with hats). Formatter shows
`LP 120` / `OFF` / `HP 200`.

**3. Dynamic KEY chooser — same-core tracks only, and that's all it can reach.** FEASIBLE.
Mechanism (octabam `PARAM_PAGES.md` §7 + `FUN_40031da4`):
- `KEY` param `E+0xd2` count = **5** (`OFF` + 4). Value stored = **core-relative index 0–4**.
- Custom A-formatter (`E+0x11a`, sig `fmt(char *buf, int value)`, *may read globals*): read
  the current edit-track number; value 0 → `"OFF"`; values 1–4 → `"T1".."T4"` when track ∈
  1–4, `"T5".."T8"` when track ∈ 5–8. `B`-widget = 0 (plain dial prints the A text).
  ~20-byte code cave (proven pattern; cave region `0x400d7000–0x400d7c3c`, and our build
  already ships/pins a ColdFire cave).
- DSP resolves `keybus[coreBaseTrack + (value − 1)]` where coreBase = 1 or 5.
- Result: a compressor on T3 can only ever select `OFF/T1/T2/T3/T4` — **disconnected tracks
  are not merely hidden, they are unreachable.** Exactly the user's ask.
- Own-track *is* offered (harmless — `KEY=own` + `KEY FLT` = the internal-side-chain-filter
  case, a bonus).
- Future-proof: if the cross-core bus (octabam XBUS) is ever adopted, bump count to 9 and
  the formatter shows all 8 — the design doesn't fight that later.
- **To find at build time:** the "current edit-track" global (a caller of `FUN_40031da4`
  passes it; candidates near `0x46c7dd26` — the word the page-class handler already checks).

**Proposed page-2 layout** (packed from `r6+$c`; octabam warns page-2 r6 offsets are
"less certain — verify"): `RMS`(existing, `r6+$c`) · `KEY`(`+$d`) · `KEY FLT`(`+$e`) ·
`KEY GAIN`(`+$f`, key drive into the detector — a loop's kick may be quiet) ·
`SC LISTEN`(`+$10`, OFF/ON monitor the filtered key) · one spare.

**4. Self-key (`KEY = own track`) — analysed.** Not a bug, not a feedback loop: the
detector always reads an input-side signal, the compressed output is only written back at
the very end (`0x1b53`), and `keybus[t]` is never fed from the compressor's output. Worst
case a gain-bounded 1-frame wobble, never instability. `KEY = own` is **"internal
sidechain"**: `KEY FLT` centred → identical to `KEY = OFF`; `KEY FLT` engaged → detector
hears a filtered copy of the track's own signal while the full signal is compressed (the
classic de-ess / stop-the-bass-pumping move). Keep it selectable.
- **But it exposes the tap-placement trap.** If `keybus[t]` is filled by extending the NONE
  passthrough stub, the tap sits wherever the NONE slot is: compressor in **FX1** + FX2 =
  NONE → the FX2 stub runs *after* the compressor and would publish the **compressed**
  output, so self-key (and any same-track dependency) keys off a 1-frame-delayed feedback of
  the comp's own output. Messy, not dangerous.
- ⇒ **Prefer the frame-builder / dispatcher tap** (pre-FX, unconditional) so `keybus[t]` is
  always the clean track input regardless of FX-slot layout. Belt-and-braces: DSP
  short-circuits `KEY == ownTrack` to read `r0` directly (still filtered). This firms open
  item (a) toward "map the per-track pre-FX tap point," away from the stub hack.

### Session 17 continued (4) — DSP per-track dispatcher mapped; publish injection point found

Disassembled the DSP frame engine's per-track FX loop (payload A): modules `P:0x002bf` +
`P:0x003a1` (setup) + `P:0x0041e` (dispatcher, 429 w) + `P:0x005cb` (per-track
filter/AMP/env). Key structure:

**Per-track loop** `func_000385` → `0x53c bne func_000385`, **4 iterations** (`x:0x418`
counter `0x20 → 0x80`, `+0x20`/track) = **4 tracks per DSP core** (confirms octabam).
Per iteration:
- `0x385`–`0x39f`: pick this track's param descriptors (`x:0x415`/`x:0x416` + track offset
  → `x:0x208` a-side, `x:0x419` b-side); decode split point `a = x:(r2+$1e)>>8 & 0xf` →
  `x:0x20c`=split, `x:0x20d`=`0x10-split`, `x:0x20e`=`split*2` (buffer offset for the 2nd
  segment).
- `0x3a1`–`0x41d`: unpack the compact per-track FX param block (`x:0x209`, stride **0xA8**)
  into the working param area (`X:0x40+` and the `r6` blocks).
- `0x426`–`0x4a6`: frame-context setup + **crossfader/scene param morph**; a 16-tap input
  filter at `0x498` writes the track's audio into **`X:0x0000`** (crossfade path). Tracks
  the crossfader doesn't touch `beq func_0004a7` — skip straight to dispatch, `X:0` already
  holding their input.
- **`func_0004a7`**–`0x50d`: **the dispatch.** FX1 (id `x:(r6+$1b)`) then FX2 (id
  `x:(r6+$1c)`), each: optional `INIT_TABLE[id]` (`x:(r1+$215)`) call on id-change, then
  `PROCESS_TABLE[id]` (`x:(r1+$235)`) — called **twice** for a split block (a=0 seg with
  `r0=0`, then a=1 seg with `r0=x:0x20e`), once (`r0=0`) otherwise. `r6` advances `+6`
  (`n6`), state ptr `x:0x20a` advances `+0x100` per effect.
- `0x50e`–`0x53c`: mix `X:0` working buffer → per-track output slot `x:0x206` (stride
  **0x40**); advance `x:0x420` (**per-track counter**, +1), `x:0x209 += 0xA8`,
  `x:0x206 += 0x40`, `x:0x418 += 0x20`; loop.

**⇒ Publish injection point for `keybus[t]`: `func_0004a7` (`P:0x004a7`).** At that PC
`X:0x0000` is guaranteed to hold the track's FX-chain input (FX1's process reads it on the
very next call; both the crossfade path — 16-tap filter at `0x498` — and the skip path have
finalised it by `0x4a7`). Inject ~10 words: `copy X:0 (2·n7 words) → keybus[coreBase +
idx]`, `idx` from `x:0x420`. **No dependence on how `X:0` was filled upstream** — by
definition it is the chain input at that instant. Sidesteps the octabam "stock buffer
convention is hard to fully reconstruct" problem (`refs/octabam/docs/DSP.md` §6b).

**`keybus`**: absolute Y at `0x800+` (octabam's proven-safe-in-both-payloads region), e.g.
`Y:0x800`, 8 slots × 0x20 w. Each core writes its own 4 slots, the compressor reads any of
its **same-core** 4 → **no cross-core traffic, none of octabam's XBUS race machinery
needed.**

**Build-time unknowns still to nail (all small):**
1. `x:0x420` exact semantics — 0-based? per-core (0–3) or absolute (0–7 / 4–7)? where reset
   each block? (Determines `coreBase` arithmetic.)
2. **Which 4 tracks each payload serves** — octabam measured A=T5–8 / B=T1–4 but flags it
   "settle empirically" (octa-bt-pt disagrees). Confirm on our image / HW before indexing.
3. Free page-2 `r6` offset for `KEY` (octabam: page-2 offsets "less certain — verify").
4. ColdFire: the "current edit-track" global for the dynamic KEY formatter (caller of
   `FUN_40031da4`).
5. Program-space budget: payload ~2,724 w shared; compressor 180→~230, dispatcher +~10,
   `KEY FLT` filter +~30 → need a cave / reclaim (octabam reclaims the 3 FX2 reverbs; we
   only need ~70 w, much less drastic).

### Proposed build order (each a checkpoint, emulate-then-flash)

1. **Menu-only, no DSP:** add `KEY`/`KEY FLT` page-2 params to the COMPRESSOR descriptor +
   the dynamic formatter; DSP ignores them. Proves the ColdFire side + the formatter on HW.
2. **keybus plumbing:** dispatcher tap at `0x4a7` + compressor detector redirect, `KEY`
   only (no filter). Emulate with `dsp_host`, then HW: kick on T1 keying a pad's comp on T2.
3. **`KEY FLT`** (2-pole SVF on the key stream) + `KEY GAIN`.
4. **`SC LISTEN`** monitor + polish.

### Session 17 continued (5) — BUILD STEP 1 DONE: KEY parameter on the COMPRESSOR page (menu only, emu-clean, NOT flashed)

`tools/patch_sidechain.s` + `tools/build_sidechain.py` + `tools/emu_sidechain.py`.
Output: `out/OCTATRACK_OS1.40C_SIDECHAIN.{syx,bin}` (`140C_KYOTI`), 154 B vs stock.
Base = stock 1.40C + `patch_trigscale` (Bug-1 fix, byte-identical to
`build_trigscale_only.py`). **The DSP is untouched — KEY does nothing audible yet.**

**COMPRESSOR descriptor RE'd in full** (`E = 0x400d5a4a`, entry size `0x192`, 31-entry
table `0x400d2e52…0x400d5f00`). Layout (E-relative, confirms octabam PARAM_PAGES §2 +
corrects §7's P-offset confusion — there is **no separate enable bitmap**, a slot is
visible iff its name is non-blank):

| off | field |
|---|---|
| `E+0x3b` | u8 effect id (`0x18`) |
| `E+0x3c` / `E+0x41` | abbr / full name, NUL-term |
| `E+0x4e` | 12 × 6 B param names (6 pg1 + 6 pg2), blank = hidden encoder |
| `E+0x96` | 12 × u8 default |
| `E+0xa2` | 12 × u32 min |
| `E+0xd2` | 12 × u32 **value count** (128 = 0–127 continuous, N = an N-way select) |
| `E+0x102` | 12 × u32 **A** = per-slot text formatter `void fmt(char *buf,int val)` |
| `E+0x132` | 12 × u32 **B** = per-slot widget drawer (`0` = plain dial, prints A's text) |
| `E+0x162` | 12 × u32 **C** = per-slot page-class handler (`0` = default; else `0x40032814` / `0x400328e4`) |

Stock COMPRESSOR params: `[0]ATK [1]REL [2]THRS [3]RAT [4]GAIN [5]MIX` (pg 1) ·
`[6]RMS` (pg 2) · `[7..11]` blank-named, vestigial (counts 2/128/2/128/128).

**Step-1 edits — all data pokes on slot 7 + one formatter cave:**
| addr | was → now |
|---|---|
| `0x400d5ac2` name[7] | `000000000000` → `"KEY\0\0\0"` |
| `0x400d5b38` count[7] | `2` → `5` (OFF + 4) |
| `0x400d5ae7` default[7] | `1` → `0` (OFF) |
| `0x400d5b68` A[7] | `0` → `key_fmt` (`0x400d7000`) |
| `0x400d5b98` B[7] | `0x400475f8` → `0` (plain dial) |
| (`0x400d5b08` min[7] asserted `0`; C[7] left `0`) |

**`key_fmt`** (80 B cave @ `0x400d7000`): `fmt(buf,val)` — `val 0` → rewrite stack args
+ tail-`jmp` `sprintf`(`0x40013a08`)`(buf,"OFF")` (mimics stock `FUN_4003c14c`); `val 1..4`
→ `sprintf(buf,"T%d", coreBase + val - 1)` where `coreBase = 1` if `*(u8)0x100b14cc` (current
edit-track) `< 4` else `5`. So a compressor on T3 shows only `OFF/T1/T2/T3/T4`, one on T6
only `OFF/T5/T6/T7/T8` — **disconnected tracks are unreachable, per the design ask.**

**Validation:** build round-trips (aPLib + ELEK checksum OK); manual-trig fix byte-identical;
adjacent descriptor entry (MBC) intact. `emu_sidechain.py` (real cave under Unicorn, sprintf
stubbed) — **ALL GOOD**: 8 tracks × values 0–4 all format correctly, both "chooser set"
checks pass.

**HW test (`NOTES` build-order step 1):** flash `OCTATRACK_OS1.40C_SIDECHAIN.syx`. Put
COMPRESSOR in an FX slot → FX SETUP **page 2** → the encoder after `RMS` is `KEY`. Confirm:
(a) shows `OFF` by default, scrolls `OFF→T1→T2→T3→T4` on tracks 1–4 and `OFF→T5..T8` on
5–8; (b) p-locks + survives PART save / project reload; (c) nothing else on the compressor
page changed; (d) no crash/glitch entering the page or turning the knob. Revert = flash
stock `downloads/extracted/OCTATRACK_OS1.40C.syx`. **Then → step 2 (keybus plumbing).**

### Session 17 continued (7) — STEP 2 DSP code written + assembles clean (38 words/payload); dsp_asm+dsp_host built

**Toolchain complete:** `vendor/dsp56300/build/.../dsp_asm` + `dsp_host` built (octabam's,
staged from `refs/octabam/tools/dsp_host/`). `dsp_asm` constraints found the hard way:
**no directives / no constants / no `jmp` / no `jcc`** — only relative b-forms, labels
substituted textually (as `$disp` for branches). So the code uses literals, a build-time
`@KADJ@` token, and **ends each routine with `rts`** (the build hand-encodes `jsr <cave>`
at the detour sites; control returns via `rts`, no branch-back).

**`tools/patch_sc_dsp.asm`** — assembled at `-org 1da0` for the payload-B variant,
**38 words**, round-trips clean through the disassembler:
- `sctap` (12 w): `x:$420 → idx*$80`; `r1 = Y:$800 + that`; `do #$20 { X:0 → Y:(r1)+ }`;
  displaced `move x:>$208,r6` + `move #$6,n6`; `rts`.
- `scdet` (26 w): displaced `move r0,n6`; `b = x:(r6+$d)` (KEY) `>>16`; `tst b; beq` → if 0,
  `move #$61,r4`; `rts` (stock self-detect). Else `@KADJ@` (`sub #1,a` B / `add #3,a` A →
  abs track); `r1 = Y:$800 + abs*$80`; `do #$20 { Y:(r1)+ → X:$40 }`; `move #$40,r0`;
  `move #$61,r4`; `rts`.
- Both payloads = 38 words (`@KADJ@` is one instruction either way).

**Detour encoding (build, hand-written bytes):**
- dispatcher `func_0004a7` (A) / `func_00029c` (B) — **byte-identical** stock (`move x:>$208,r6`
  `66f000 000208` + `move #$6,n6` `3e0600`): `jsr sctap` (2 w) + `nop` (1 w) over the 3-word
  span... wait, that span is `move x:>$208,r6`(2w) + `move #$6,n6`(1w) = 3 w. `jsr` long = 2 w
  + 1 `nop`. Cave reproduces both moves then `rts` → lands at `func_..+3`.
- COMPRESSOR proc+0 `0x1ab1` (A) / `0x1871` (B) — byte-identical (`move r0,n6` `221e00` +
  `move #$61,r4` `346100`): `jsr scdet` (2 w) exactly over the 2-word span. Cave `rts` →
  `proc+2`.

**Placement — donor still required.** 38 > payload A's 33 free words. A `bsr kbslot` refactor
saves only ~2 w (36). Confirmed there is no quick win:
- payload B free space (~600 w) is **not loader-record-backed** → needs payload-stream surgery.
- the dead-bootstrap tail (`P:0x30048+`) is a HW-only safety question (shared-window), not a
  desktop probe.
So a flashable step 2 needs a donor module in **both** payloads (overwrite its words in
place + point its dispatch-table init/proc entries at the null stub). Recommend SPATIALIZER
(`0x05`, 261 w). This can't be deferred to step 3 after all.

**Step-2 hooks VALIDATED in isolation** — `tools/emu_sc_dsp.py`, dsp56kEmu, **ALL GOOD**:
- `sctap`: for track idx 0/3/7, `keybus[idx]` (Y:`0x800 + idx*0x80`) == the 32 words of `X:0`
  after the run; the rest of the ring untouched.
- `scdet`: `KEY=0` → `X:$40` untouched (stock self-detect); `KEY=1` → `X:$40` == `keybus[0]`
  (CORE_BASE 0, abs = KEY−1); `KEY=4` → `X:$40` == `keybus[3]`.
- The `jsr scdet` detour byte-patch (`0bf080 <org>` over `move r0,n6` + `move #$61,r4`) is
  asserted against the real payload-B COMPRESSOR module inside the harness.

**dsp_host can't run the stock COMPRESSOR end-to-end** (it sets `r7=0x200`, targets octabam's
own effects) — so the full "detector's gain reduction tracks keybus" chain is a **hardware**
test, not a desktop one. The isolation harness covers the hook mechanics; the audio outcome
is HW.

**Page-2 param packing found (octabam `PARAM_PAGES` / dsp_host, "cost months"):** each page-2
descriptor word holds **two** controls — slot 6→`r6+$c` bits16-23, slot 7→`r6+$c` bits8-15,
slot 8→`r6+$d` bits16-23, slot 9→`r6+$d` bits8-15, … So **step 1's KEY moved from descriptor
slot 7 → slot 8** (`r6+$d` knob = what `scdet`'s `asr #$10` reads). RMS stays slot 6; slot 7
blank → a stock-normal page-2 gap (CHORUS/EQ do the same). `build_sidechain.py` updated,
`emu_sidechain.py` re-passes, image re-built (150 B vs stock).

**Still to do for step 2:** (1) donor pick (37 w > payload A's 33 free — a `bsr` refactor
saves ~2 w, not enough); (2) `build_sidechain2.py` — DSP-payload patcher: assemble
`patch_sc_dsp.asm` per payload (`@KADJ@` = `add #3,a` A / `sub #1,a` B), place at the donor
`-org` (`jsr` short-form-reachable, ≤ `$fff`), hand-encode the 2 detours (`jsr` short =
`0d0<addr>` 1 w; long = `0bf080 <addr>` 2 w), retarget the donor's `X:0x215`/`X:0x235`
dispatch entries to the null stub; (3) `keybus` Y-region runtime-safety (inherit octabam
§11, watch on HW); (4) HW validation.

Doc note for users: `X:0` at the tap point is **post-AMP-VOL** — mute keeps a key track
keying (mute is downstream), but AMP VOL 0 kills the key. Silence a key track with mute.

### Session 17 continued (8) — STEP 2 BUILT (SPATIALIZER donor, both payloads); emu-clean; NOT flashed

`tools/build_sidechain2.py` → **`out/OCTATRACK_OS1.40C_SIDECHAIN2.{syx,bin}`** (`140C_KYOTI`,
**380 B vs stock**). = stock 1.40C + Bug-1 fix + step-1 KEY menu (slot 8) + the step-2 DSP
hooks. User picked **SPATIALIZER (`0x05`) as the donor.**

**DSP patch, per payload (A / B):**
| target | file A / B | change |
|---|---|---|
| SPATIALIZER P region (`0xaa8` / `0x868`, 261 w) | `0xf1371` / `0x1042c2` | first 37 w overwritten with the `sctap`+`scdet` cave (assembled per payload, `@KADJ@` = `add #3,a` / `sub #1,a`); the other 224 w become dead code |
| dispatcher FX1 entry `func_0004a7` / `func_00029c` | `0xf008d` / `0x10307d` | `move x:>$208,r6` (2 w) → `jsr <sctap>` (`0d0aa8` / `0d0868`, 1 w) + `nop`; the following `move #$6,n6` left in stock |
| COMPRESSOR proc+0 `0x1ab1` / `0x1871` | `0xf43f8` / `0x107349` | `move r0,n6` + `move #$61,r4` (2 w) → `jsr <scdet>` (`0d0ab7` / `0d0877`) + `nop` |
| dispatch table `X:0x215[5]` init / `X:0x235[5]` proc | `0xe1f45`+ / `0xf5610`+ | SPATIALIZER's id-`0x05` entries → null stub (`0x7c8`/`0x7c9` A, `0x588`/`0x589` B) — SPATIALIZER now passes audio through |

**Verified:**
- byte-diff vs stock: **exactly 2 words** changed at each detour site, all surrounding stock
  code byte-identical; SPATIALIZER word 37+ untouched (harmless dead).
- both patched payloads still parse **100%** (`dsp_modmap`); image round-trips (aPLib+ELEK
  checksum OK); Bug-1 fix byte-identical to `build_trigscale_only.py`.
- **`emu_sc_dsp.py --patched`** (real cave at SPATIALIZER `0x868`, real detour in the
  compressor module, regenerated payload-B `.mem`) — **ALL GOOD**: `sctap` copies `X:0` →
  `keybus[idx]`; `scdet` KEY=0 leaves the detector, KEY=1→`keybus[0]`, KEY=4→`keybus[3]`.

**NOT verified — hardware only** (dsp_host can't run the stock compressor):
1. the compressor's detector actually producing gain reduction *driven by* the keybus signal;
2. the `do #<$20` copy count vs the real frame's `n7` (isolation copied a fixed 32 correctly);
3. `Y:0x800–0xBA0` keybus region runtime-safe in both payloads (octabam §11 says ≥`0x800`
   is safe — watch for hash/instability);
4. muted-key behaviour (design says free — mute is downstream of the `X:0` tap);
5. SPATIALIZER→passthrough is graceful on a project that still selects it;
6. no glitch on the first FX1 dispatch of a split block.

### Session 17 continued (8) — HW test plan (do this after flashing SIDECHAIN2)

1. **No-regression:** every existing check (OT/OT+FX mute — wait, MUTEMODE is NOT in this
   build; it's stock+trigscale+sidechain — so: Bug-1 manual-trig, boot string `140C_KYOTI`,
   all stock FX except SPATIALIZER unchanged, SPATIALIZER now = clean passthrough).
2. **KEY menu** (step-1 checklist): `COMPRESSOR` → FX page 2 → `KEY` after `RMS`, `OFF` +
   `T1..T4` / `T5..T8` per bank, p-locks, survives save/reload.
3. **The feature:** kick loop on T1, pad on T2 with `COMPRESSOR` (THRS low, RAT high,
   ATK fast, REL ~med), `KEY = T1`. Expect the pad to duck on every kick. Sweep the
   target across T2/T3/T4 and the key across T1/T2/T3/T4.
4. **Muted key:** mute T1 — ducking should continue. Then set T1 `AMP VOL` 0 — ducking
   should stop (tap is post-AMP-VOL; documented).
5. **Cross-bank is inert:** `COMPRESSOR` on T2, `KEY = T3` works; there is no way to pick
   T5–T8 from T2 (formatter). A compressor on T5 keyed by T5–T8 also works (payload A).
6. **Stress:** compressors on several tracks all keyed; FX2 slot also in use; split trigs;
   listen for clicks / hash / runaway on the ducking envelope.

### Session 17 continued (6) — STEP 2 DESIGN: keybus plumbing, quad-buffered from day one

Design for the DSP side: publish each track's pre-FX audio to a shared Y ring, and
redirect the COMPRESSOR's detector to read a chosen track's ring instead of its own input.
**Cross-core assessed (`XBUS.md`): octabam hardware-confirms it works.** Our case drops 2 of
octabam's 3 race classes (no accumulate ⇒ no clear ⇒ no clear-vs-read / clear-vs-write); only
the torn-read race remains, fixed by quad-buffer + read-2-back + a per-core rotation counter
seeded at init. **Step 2 ships same-core only; the ring is laid out quad-buffered now so v2
(any-core) is additive, not a rewrite.**

#### Confirmed this session

- **`x:0x420` = the absolute 0-based track index (0..7)** during the per-track dispatch,
  valid at `func_0004a7`, `+1` per track. Payload A inits it to **4** (`P:0x000381`
  `move #$4,x0; move x0,x:>$420` → tracks 5–8); **payload B inits it to 0** (`P:0x00017a`
  → tracks 1–4). This is an independent, instruction-level confirmation of octabam's
  marker-flash result: **payload A serves T5–8, payload B serves T1–4.**
- Effects' audio buffer is **`X:0x0000`** (`r0 = 0` for a normal block; `r0 = x:0x20e` =
  `2·splitpoint` for a split block's 2nd segment). Detector in COMPRESSOR reads `x:(r0)+`.
- **P-space is the wall.** Payload A P code ends at `0x01fdf` — **33 free words** (octabam
  `CHIP.md`). Payload B ends at `0x01d97` — ~617 free. No general free pool.

#### `keybus` — the ring (Y memory, absolute)

```
KB_BASE   = Y:0x800                     (octabam: abs-Y >= 0x800 is the region proven
                                         safe across both payloads' module maps, DSP.md §11)
per track : 4 buffers x 32 words (16 stereo samples = one max block, interleaved L/R)
layout    : slot(track, gen) = KB_BASE + track*0x80 + (gen & 3)*0x20
extent    : 8 tracks * 0x80 = 0x400 words  ->  Y:0x800 .. Y:0xC00
rotation  : KB_GEN_A = Y:0xC00 (byte), KB_GEN_B = Y:0xC01   (v2 only; step 2 leaves them 0)
```
Y:0x800–0xC00 is unclaimed in both payload module maps (largest stock Y module is
`Y:0x290`, 1024 w, ending `0x690`; then `Y:0x715`). Build asserts it.

#### Donor for the code — **DECISION NEEDED**

~32 w (step 2) → ~130 w (through v2) of P-space code. Payload A has only 33 free words, so
reclaim one stock effect's P region and point its dispatch entries at the null stub (graceful
passthrough, octabam's pattern for absent effects). **Proposed: SPATIALIZER** (id `0x05`,
`P:0x00aa8` A / `P:0x00868` B, **261 words**, self-contained — octabam verified max CF target
`0x00baa` is inside). It is the least-used OT stock effect and 261 w covers all four steps
plus v2. Alternatives: COMB (`0x13`, 277 w) or LO-FI (`0x1c`, 537 w). Reclaimed region =
`SC_CAVE`. (Payload B could instead use its 617 free words and keep SPATIALIZER on T1–4, but
the asymmetry isn't worth it.)

#### Hook 1 — the publish tap (dispatcher, both payloads)

Detour at **`func_0004a7`** — replace `move x:>$208,r6` + `move #$6,n6` (payload A; the
equivalent 2 instrs in B) with `jmp SC_CAVE:tap`. At that PC `X:0x0000` holds this track's
FX-chain input and `x:0x420` = its absolute index. `tap`:
```
  r1 = KB_BASE + x:0x420 * 0x80 + (KB_GEN[core] & 3) * 0x20     ; step 2: gen = 0
  r0 = 0                                                        ; X:0 source
  do #16 { move x:(r0)+,a  x:(r0)+,b ; move a,y:(r1)+  b,y:(r1)+ }   ; 32 words, mono-safe
  move x:>$208,r6 ; move #$6,n6                                 ; displaced originals
  jmp func_0004a7 + <len of displaced>
```
~12 words. Runs once per track per block, for every track regardless of its FX assignment —
so any track can be a key.

#### Hook 2 — the detector redirect (COMPRESSOR process, both payloads)

Detour at **`0x1ab1`** — replace `move r0,n6` + `move #$61,r4` (0x1ab1–0x1ab2) with
`jmp SC_CAVE:detect`. `detect`:
```
  move r0,n6                       ; (0x1ab1) dry path anchor -- UNCHANGED, keeps dry clean
  move x:(r6+$d),a ; asr #$10,a,a  ; a = KEY param 0..4        (page-2 slot 7)
  beq  d_own                       ; KEY 0 -> stock: detector reads X:0 (own track)
  add  #CORE_BASE-1,a              ; CORE_BASE = 0 (payload B) / 4 (payload A)  -> abs track
  r1 = KB_BASE + a*0x80 + (readgen & 3)*0x20    ; step 2: readgen = 0 ; v2: KB_GEN[core]-2
  move #$40,r0                     ; stage the key block into X:0x40..0x60 (free during
  do #16 { move y:(r1)+,x0 ; move x0,x:(r0)+   (x2 for L/R) }   ;  detection; wet uses it later)
  move #$40,r0                     ; detector now streams from the key copy
d_own:
  move #$61,r4                     ; (0x1ab2) displaced
  jmp  0x1ab3
```
~20 words. **The dry/wet path is untouched** — it re-anchors via `n6` at `0x1b1f`/`0x1b46`,
so redirecting only the detector has zero effect on the compressed signal. `X:0x40..0x60` is
free during detector stages 1–5 (COMPRESSOR first writes `X:0x40` in stage 6, `0x1b1e`).

**`CORE_BASE`** is a per-payload build constant (`--defsym CORE_BASE=4` for A, `=0` for B),
because the KEY param stores a core-relative `1..4` and the ColdFire formatter already renders
it as the right absolute track. Same-core is therefore enforced structurally — a `1..4` value
can only ever resolve to one of this core's own 4 ring slots.

#### Step 2 vs v2 (any-core)

| | step 2 (same-core) | v2 (any-core) |
|---|---|---|
| ring layout | quad-buffered (built now) | unchanged |
| `gen` in both hooks | constant 0 | `KB_GEN[core]`, read side `- 2` |
| rotation counter | — | `KB_GEN_A/B`, `+1` once per block per core at the first dispatch (housekeeping hook, seeded at init — octabam: "NOT self-healing") |
| KEY param count | 5 (`OFF`+4) | 9 (`OFF`+8), `CORE_BASE` drops out, DSP reads `keybus[value-1]` |
| formatter | core-aware `T1..T4`/`T5..T8` | shows all 8 |
| validation | `dsp_host` (single-core, exact) + HW | HW **track×track sweep** (octabam: races relocate) |

#### Word count — step 2 does NOT fit payload A's 33 free words

Hand-estimate: `tap` ≈ 17 w, `detect` ≈ 17 w → **~34 words inlined** (no shared helper) in
payload A, which has **33 free**. Payload B (617 free) is fine. So step 2 is **right on the
edge** — 1–7 words over depending on how tight the golf is. Not the clean "single-core needs
no donor" it looked like; it needs either a couple of words shaved or a few spare words from
outside the 33. Step 3 (the `KEY FLT` 2-pole SVF, +~30 w) is where a donor becomes
unavoidable. Options:
- **(a) reclaim the donor now** (SPATIALIZER 261 w etc.) — clean, unblocks steps 2–4 + v2.
- **(b) dead bootstrap region** — `P:0x30048+` (payload A) / `P:0x38000+` (B) hold stock
  bootstrap code that is dead after boot (octabam: "`0x31000`/`0x32000` bootstraps … dead
  after boot"; but `0x30000–0x30047` is live per-frame staging — off-limits). ~100 dead
  words if it verifies. Costs no effect. Risk: in the shared window; needs a probe.
- **(c) hand-golf to ≤33 w** — inline, drop the helper, copy `n7·2` not a fixed 32; fragile.

Recommend (a). User picked "decide later" before this count was known — revisit.

**(d) — read an existing per-track buffer instead of tapping — RE'd, DOES NOT PAN OUT.**
Traced the per-track audio flow:
- **`X:0` is a single reused working buffer.** Per track: raw playback audio arrives in `X:0`
  → `func_0005d0`→`func_0006b7`→`func_0006f7` filters + amp-envelopes it **in place**
  (`x:(r0)+` → `x:(r1)+`, both `r0=r1=0`) → optional crossfade/scene pass (also in place) →
  FX1/FX2 (in place) → `func_00055a` mixes `X:0` into the per-track out slot. `X:0` is
  overwritten by the next track. **No per-track-persistent pre-FX copy exists.**
- The crossfade path (`0x468`–`0x472`) does read a raw per-track input via
  `r0 = x:0x202 − 0xc0 (+0x240 wrap)`, but `x:0x202/203/205/207` are **rolling** frame-DMA
  pointers set by the ISR/frame-context routine, not per-block-stable per-track arrays.
- `x:0x206` (per-track out, stride `0x40`) IS persistent but is **post-FX** and its block
  base is a rolling pointer (advanced `0x100`/block in the prologue + `0x40`×4 in the loop) —
  usable only after more RE, and post-FX isn't the wanted signal.

⇒ **The tap (hook 1) is unavoidable.** Step 2 = ~34 w in payload A, 33 free. Since **step 3
(the `KEY FLT` filter) needs a donor no matter what**, the clean call is **take the donor
now** (option a — SPATIALIZER) rather than golf step 2 into 33 w and then donor step 3
anyway. Golf (c) was a bad suggestion — retracted. Dead-bootstrap (b) stays a fallback if
the user wants to keep every stock effect: `P:0x30048+` (~99 w after the live `0x30000–47`
staging), needs a runtime probe, shared-window aliasing risk.

#### Build-time still-open

1. Payload B's `func_0004a7` equivalent PC + the exact 2 instrs to displace (disasm
   `B_P00221.asm` around the 6 `jsr (r2)` block).
2. Confirm `Y:0x800–0xC00` untouched at runtime in both payloads (octabam's §11 proof
   inherited; watch in the HW test).
3. `dsp_host` harness for step 2: seed `X:0`, run the tap + a COMPRESSOR instance with
   `KEY` set, assert its detector consumed the seeded key block (adapt octabam
   `tools/dsp_host` `-pokey`/`-peeky`).
4. Donor decision (SPATIALIZER / COMB / LO-FI / dead-bootstrap-region probe).

### Session 17 tooling

`out/dsp/` (gitignored): `payload_{A,B}.mem`, `{A,B}_P01aa4/01864_compressor.{bin,asm}`,
`A_P007c8_stub.bin`, `A_P0041e_dispatch.asm`, `A_P00{282,2bf,3a1,5cb,6f4}.asm`,
`B_P00{167,1a4,221}.asm`, `toolchain_setup.log`. `vendor/dsp56300/` (gitignored) — the built
disassembler. Scratchpad: `dsp_toolchain_setup.sh`. **Committed:** `tools/patch_sidechain.s`,
`tools/build_sidechain.py`, `tools/emu_sidechain.py` (step 1). No Ghidra runs.
Sources: `refs/octabam/docs/{DSP,BUS,XBUS,PARAM_PAGES,CHIP,MODULES,FLASHING}.md` +
`tools/dsp_modmap.py` @ e1dcfa9, `refs/octa-bt-pt/patch_tool/registry.json` +
`addresses.json` @ e970dd0, `reference/kb/{dsp56300,memory-map}.md` (on `main`), our
`COVERAGE.md` / `ARCHITECTURE.md`.
