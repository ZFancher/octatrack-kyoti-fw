# RE Log — Octatrack

Record of findings. Each run of `analyze.sh` leaves evidence in `out/`.

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
- **Implementation** (`tools/patch_gui.s`, m68k-elf-as+ld @0x400d6600): wrapper with **return-hook**.
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

### Sticky scenes — IMPLEMENTED (`tools/patch_scene.s`)

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
  of valid index to an in-range address). Possible cosmetic: scene LEDs might show the destination's
  selection if some display reads the mirror `0x100a4ede/edf` (not updated); the audio uses `0x8ed90/91`.
