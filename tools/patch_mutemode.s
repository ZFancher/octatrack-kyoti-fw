| SPDX-License-Identifier: MIT
| SPDX-FileCopyrightText: 2026 Zac-Kyoti
| patch_mutemode -- the "MUTE MODE" PERSONALIZE entry (a multi-value text option, not a
| checkbox).  Modeled on the stock LED BRIGHTNESS item (FUN_40068c80 getter / FUN_4006907c
| setter): a getter returns a char* shown in the right-hand column, a setter takes
| (delta, wrap) on the stack and clamps/wraps the value word.
|
|   MUTE MODE   0x800000dc   0 = "OT"     -> stock instant post-FX cut
|                            1 = "OT+FX"  -> patch_softmute: dry cuts, FX inserts ring tails
|
| (A third mode, "DT", exists on the wip/mute-mode branch -- emulator-verified only, not
| shipped here.)
|
| 0x800000dc is the same free PERSONALIZE word patch_softmute already reads as GATE, so
| 0 = a freshly-flashed unit behaves exactly like stock.
|
| PERSISTENCE.  The 0x800000xx words are VOLATILE -- boot re-images 0x80000000.. from ROM,
| so a raw `move.l %d0,MUTE_MODE` setting is lost on the next power cycle.  The durable
| store is the checksummed 'ANDY' block in battery SRAM at 0x100fff00; boot restores
| runtime 0x80000070 <- shadow 0x100fff00, memcpy length 0x64 (ends 0x800000d3 -- one byte
| short of MUTE MODE).  build_mutemode.py patches that length 0x64 -> 0x70 at all three
| restore sites (boot / validate / defaults), so 0x800000d4..df ride along; set_mutemode
| writes its shadow at 0x100fff6c (= 0x100fff00 + 0x800000dc - 0x80000070) and the
| PERSONALIZE key handler re-checksums the block for free (jmp 0x4001f23c @ 0x40069074).
| Mechanism lifted from octamax c78ff70 (hardware-confirmed there; not yet on our MKI).
|
| The renderer (FUN_40068e00) calls the getter with jsr and pushes D0 as the column text;
| D0/D1/A0/A1 are scratch.  The input handler (FUN_40068fd0) calls the setter as
| (*setter)(delta, wrap) -- delta at 4(sp), wrap at 8(sp) -- same ABI as set_notimer.
|   [YES]   -> (+1, wrap=1)   cycle with wraparound
|   [RIGHT] -> (+1, wrap=0)   clamp
|   [LEFT]  -> (-1, wrap=0)   clamp

    .equ MUTE_MODE,    0x800000dc
    .equ SH_MUTE_MODE, 0x100fff6c    | battery-SRAM shadow = 0x100fff00 + (MUTE_MODE - 0x80000070)
    .equ N_MODES,   2                | OT / OT+FX
    .equ NMAX,      N_MODES - 1

    .text

| ---- label ----
    .global lbl_mutemode
lbl_mutemode:
    .asciz "MUTE MODE"
    .align 2

| ---- value strings + table ----
vm_0:
    .asciz "OT"
    .align 2
vm_1:
    .asciz "OT+FX"
    .align 2
val_tbl:
    .long vm_0
    .long vm_1

| ---- getter: return &val_tbl[clamp(MUTE_MODE, 0, NMAX)] ----
    .global get_mutemode
get_mutemode:
    move.l  MUTE_MODE,%d0
    bpl.b   gm_hi
    moveq   #0,%d0
gm_hi:
    cmpi.l  #NMAX,%d0
    ble.b   gm_ok
    moveq   #NMAX,%d0
gm_ok:
    lsl.l   #2,%d0
    lea     val_tbl,%a0
    move.l  (%a0,%d0.l),%d0
    rts

| ---- setter: (delta @ 4(sp), wrap @ 8(sp)) ----
    .global set_mutemode
set_mutemode:
    move.l  MUTE_MODE,%d0
    add.l   4(%sp),%d0
    tst.l   8(%sp)                     | wrap flag  (clobbers N/Z -> re-test d0 below)
    bne.b   sm_wrap
| ---- clamp to [0, NMAX] ----
    tst.l   %d0
    bpl.b   sm_clhi
    moveq   #0,%d0
    bra.b   sm_store
sm_clhi:
    cmpi.l  #NMAX,%d0
    ble.b   sm_store
    moveq   #NMAX,%d0
    bra.b   sm_store
| ---- wrap around [0, NMAX] ----
sm_wrap:
    cmpi.l  #NMAX,%d0
    ble.b   sm_wlo
    moveq   #0,%d0
    bra.b   sm_store
sm_wlo:
    tst.l   %d0
    bpl.b   sm_store
    moveq   #NMAX,%d0
sm_store:
    move.l  %d0,MUTE_MODE       | volatile runtime word (read by the getter + patch_softmute)
    move.l  %d0,SH_MUTE_MODE    | battery-SRAM shadow -- the key handler re-checksums on return
    rts
