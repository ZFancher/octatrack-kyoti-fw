| SPDX-License-Identifier: MIT
| SPDX-FileCopyrightText: 2026 Zac-Kyoti
|
| patch_qlrec -- surface QUANTIZE LIVE REC (PERSONALIZE) to the front panel.
|
|   [REC] held + [PLAY] pressed twice   toggles QUANTIZE LIVE REC  0 <-> 1
|
|   This is the OT's ALL-OR-NOTHING live-record quantize (the PERSONALIZE row),
|   NOT the per-track 50% TRIG QUANT tweak in the TRACK TRIG MENU.
|
|   A "QUANT LIVE REC ON" / "QUANT LIVE REC OFF" toast shows while [REC] stays
|   held and is dismissed the moment [REC] is released -- no timer.
|
|   The FIRST [REC]+[PLAY] press is left entirely to stock (it starts LIVE
|   RECORDING, exactly as on stock OS -- Digitone parity).  Only later presses
|   within the SAME [REC] hold are intercepted: every 2nd one (2,4,6..) flips
|   the setting and is swallowed so the transport is untouched; the odd ones
|   in between (3,5..) are swallowed too.  Releasing [REC] resets the count.
|
| STATE  0x800000ac  -- the stock PERSONALIZE runtime word (getter 0x40068ce0 /
|   setter 0x40068ca0, menu index 0).  Plain boolean, 0 = OFF, glyph via the
|   getter.  It already lives inside the stock 0x64 'ANDY'-restore span
|   (0x80000070..0xd3), so it persists across a power cycle with NO build
|   change.  We only mirror what the stock setter does: write the battery-SRAM
|   shadow 0x100fff3c (= 0x100fff00 + 0xac - 0x70) and -- because we bypass the
|   PERSONALIZE dispatcher's `jmp 0x4001f23c @ 0x40069074` -- re-checksum the
|   'ANDY' block ourselves (jsr FUN_4001f23c).
|
| ---- the combo (RE: NOTES "Session 46") ----
| REC handler 0x40048774(keycode@4, event@8): keymap code 0x29, handles press
|   AND release.  Every press ends by setting 0x460d1726 = 1 ("REC held"); the
|   release path 0x4004883a clears it.  No other handler distinguishes it -- a
|   clean held-modifier flag.  (Also readable via is_key_held: FUN_4003171c,
|   *(u32*)(0x46c7d8ee + code*24).)
| PLAY handler 0x40061778(keycode@4, event@8): keymap code 0x28, press only
|   (flags word = 0 -> no hold/repeat events).  Stock already branches on
|   0x460d1726 -- REC held -> start LIVE REC (0x460d172a = 1); not held -> plain
|   transport toggle.  We detour its first instruction (jsr 0x4009b5c0, the
|   "project ready?" gate) so our check runs before anything else and resume at
|   0x4006177e for the stock path.
|
| Detours (applied by build_qlrec.py):
|   0x40061778  6 B  `jsr 0x4009b5c0`      -> jmp qlr_play
|   0x4004883a  6 B  `clr.l 0x460d1726`    -> jmp qlr_recrel

    .equ REC_HELD,     0x460d1726        | longword, 1 while [REC] is physically held
    .equ QLR,          0x800000ac        | PERSONALIZE: QUANTIZE LIVE REC (bool)
    .equ QLR_SH,       0x100fff3c        | its 'ANDY' battery-SRAM shadow
    .equ CKSUM,        0x4001f23c        | FUN_4001f23c -- recompute+store 'ANDY' checksum
    .equ NOTIFY,       0x4005a2b8        | FUN_4005a2b8(text, dur); dur <= 0 => no timeout
    .equ NOTIFY_CLOSE, 0x40056bec        | tears down the FUN_4005a2b8 toast (no-op if none)
    .equ PROJ_GATE,    0x4009b5c0        | displaced from 0x40061778
    .equ PLAY_RESUME,  0x4006177e        | 0x40061778 + 6  (tst.l %d0 ; bne.s ...)

|   free scratch RAM -- above the 0x80004000 boot re-image window, disjoint from
|   MUTE MODE 0x80006c66 / DIRECT JUMP 0x80006a40-44 / RELOAD2 0x80006a50-53
|   (see reference/MERGE.md).  Nothing here needs to persist.
    .equ G_CNT,        0x80006a5c        | [REC]+[PLAY] press counter this hold (long)
    .equ G_OURS,       0x80006a60        | byte: 1 => the visible toast is ours

    .text

| ================= [PLAY] press -- detour @ 0x40061778 =================
| Entry stack (exactly what stock 0x40061778 sees): 0(sp)=ret, 4(sp)=keycode,
| 8(sp)=event (always 1 here).  Only d0/d1/a0 touched.
    .global qlr_play
qlr_play:
    tst.l   REC_HELD
    beq.b   qp_stock                    | REC not held -> stock PLAY

    addq.l  #1,G_CNT
    move.l  G_CNT,%d0
    moveq   #1,%d1
    cmp.l   %d0,%d1
    beq.b   qp_stock                    | 1st press of this hold -> stock (start live rec)
    btst    #0,%d0
    bne.b   qp_swallow                  | odd (3,5,..) -> swallow, leave transport alone

|   --- even press (2,4,..): flip QUANTIZE LIVE REC ---
    move.l  QLR,%d0
    eori.l  #1,%d0
    andi.l  #1,%d0
    move.l  %d0,QLR
    move.l  %d0,QLR_SH                   | battery-SRAM shadow
    jsr     CKSUM                        | re-checksum the 'ANDY' block

    move.l  QLR,%d0
    lea     qlr_msg_off,%a0
    tst.l   %d0
    beq.b   qp_show
    lea     qlr_msg_on,%a0
qp_show:
    pea     0                           | dur = 0 -> persistent (we close it on [REC] release)
    move.l  %a0,-(%sp)
    jsr     NOTIFY                       | FUN_4005a2b8(text, 0)
    addq.l  #8,%sp
    moveq   #1,%d0
    move.b  %d0,G_OURS
qp_swallow:
    rts                                 | swallow this [PLAY]

qp_stock:
    jsr     PROJ_GATE                    | displaced: jsr 0x4009b5c0
    jmp     PLAY_RESUME                  | -> 0x4006177e

| ================= [REC] release -- detour @ 0x4004883a =================
| Reached only for event 0 (release) of keycode 0x29.
    .global qlr_recrel
qlr_recrel:
    clr.l   REC_HELD                     | displaced: clr.l 0x460d1726
    clr.l   G_CNT
    tst.b   G_OURS
    beq.b   qr_done
    clr.b   G_OURS
    jsr     NOTIFY_CLOSE                 | close our toast
qr_done:
    rts

|   "QUANT LIVE REC ON/OFF" (17/18 ch) -- chosen to fit the 128 px screen;
|   FUN_4005a2b8 sizes the window to textpx+15, and the full "QUANTIZE LIVE
|   REC OFF" (21 ch) overruns it.
qlr_msg_on:
    .asciz "QUANT LIVE REC ON"
    .align 2
qlr_msg_off:
    .asciz "QUANT LIVE REC OFF"
    .align 2
