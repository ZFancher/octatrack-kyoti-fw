| SPDX-License-Identifier: MIT
| SPDX-FileCopyrightText: 2026 Zachary Fancher ("Kyoti")
|
| patch_directjump -- "DIRECT JUMP" PERSONALIZE entry + the sequencer hooks.
|
|   DIRECT JUMP   0x800000a8   0 = "OFF"  -> stock: a cued pattern switches at the
|                                            CHAIN AFTER point (PLEN by default),
|                                            restarting at step 1.
|                              1 = "ON"   -> a manually cued pattern switches on the
|                                            NEXT step tick (stays in time -- NOT the
|                                            instant the key is pressed) and playback
|                                            CONTINUES from the current step position;
|                                            the new pattern's Part loads at once; the
|                                            MIDI Program Change goes out ~1 step ahead.
|                                            Arranger and pattern chains are untouched.
|
| 0x800000a8 is the last free battery-backed PERSONALIZE word (0xd4/d8/dc are taken).
| Zero refs in the stock image -> a freshly flashed unit reads 0 and is exactly stock.
|
| ---- how the stock per-step switch works (FUN_400a1eea, see NOTES "Session 15") ----
|   0x400a3fdc  DAT_800065b6 (master step, byte) ++ ; wraps to 0 at pattern length
|   0x400a4006  branch: 8000667e!=0 -> "stop after pattern"; else -> step dispatch
|   step==2     FUN_4009e884(pendBank,pendPat) = send Bank Select CC + Program Change
|   step==0     0x400a4220..: bar ctr, ping-pong, CHAIN-AFTER gate, then on a
|               switch-point THE COMMIT @0x400a44d0
|                 DAT_800065be = DAT_800065c0   (pending pattern -> active)
|                 DAT_800065bd = DAT_800065bf   (pending bank    -> active)
|               then per-track step recompute from D7 = patternLen * DAT_80006628,
|               DAT_800065b6 = 0 @0x400a4842, common tail LAB_400a4ba0 (fires trigs)
|
| ---- what DIRECT JUMP does (3 hooks, all gated on DJ_MODE, all inert when 0) ----
|   Hook A @0x400a4006  every step tick while running:
|     * arranger active (0x460d1aec) or chain active (0x80006546) -> disarm, bail
|     * a real pending manual switch (0x800065c0 != -1, != active):
|         - send the Program Change once per distinct pending pattern (FUN_4009e884)
|         - save the current step; tick 1 -> just arm; tick 2 -> force the switch:
|           clr DAT_800065b6 so the step==0 body runs THIS tick
|   Hook B @0x400a42fa  inside the step==0 body: if armed, skip the CHAIN-AFTER gate
|     (overwrite the jsr return address with 0x400a43a0, the "switch confirmed" label)
|     and set D6=1 ("real change") the way the gate would have
|   Hook C @0x400a4840  replaces `DAT_800065b6 = 0`: if armed, instead set D7 and
|     DAT_800065b6 to (savedStep % newPatternLen) so every per-track position that the
|     switch body derives from D7 resumes at the playhead; clear the arm flag
|
| Menu ABI: renderer FUN_40068e00 jsr's the getter (pushes D0 as the column text);
| input FUN_40068fd0 calls (*setter)(delta @ 4(sp), wrap @ 8(sp)).

    .equ DJ_MODE,   0x800000a8          | PERSONALIZE word (0 = OFF/stock, 1 = ON)
    .equ NMAX,      1

|   free battery-backed scratch (this build has no lazypart/scene stubs to collide with)
    .equ G_ARMED,   0x80006a40          | 0 = idle, !=0 = a direct jump is armed
    .equ G_STEP,    0x80006a41          | master step to resume at
    .equ G_PCPAT,   0x80006a42          | pending pattern the PC was last sent for

|   stock symbols
    .equ ACT_PAT,   0x800065be
    .equ ACT_BANK,  0x800065bd
    .equ PEND_PAT,  0x800065c0
    .equ PEND_BANK, 0x800065bf
    .equ STEP,      0x800065b6
    .equ SCALE_IX,  0x8000663d
    .equ STOPFLAG,  0x8000667e
    .equ RUNNING,   0x800065b8
    .equ ARR_ACT,   0x460d1aec          | FUN_40033968 return (arranger active)
    .equ CHAIN_ACT, 0x80006546
    .equ LEN_TBL,   0x400aba50          | [scaleIdx] -> pattern length (long)
    .equ PAT_SCALE, 0x400eb034          | [bank*0x9b340 + pat*0x8ed8] -> scale idx byte
    .equ PC_SEND,   0x4009e884          | FUN_4009e884(bank, pat) -> Bank Sel CC + PC
    .equ SW_LABEL,  0x400a43a0          | "switch confirmed" label inside the step==0 body

    .text

| ================= PERSONALIZE menu =================

    .global lbl_directjump
lbl_directjump:
    .asciz "DIRECT JUMP"
    .align 2
vd_0:
    .asciz "OFF"
    .align 2
vd_1:
    .asciz "ON"
    .align 2
vd_tbl:
    .long vd_0
    .long vd_1

    .global get_directjump
get_directjump:
    move.l  DJ_MODE,%d0
    bpl.b   gd_hi
    moveq   #0,%d0
gd_hi:
    cmpi.l  #NMAX,%d0
    ble.b   gd_ok
    moveq   #NMAX,%d0
gd_ok:
    lsl.l   #2,%d0
    lea     vd_tbl,%a0
    move.l  (%a0,%d0.l),%d0
    rts

    .global set_directjump
set_directjump:
    move.l  DJ_MODE,%d0
    add.l   4(%sp),%d0
    tst.l   8(%sp)
    bne.b   sd_wrap
    tst.l   %d0
    bpl.b   sd_clhi
    moveq   #0,%d0
    bra.b   sd_store
sd_clhi:
    cmpi.l  #NMAX,%d0
    ble.b   sd_store
    moveq   #NMAX,%d0
    bra.b   sd_store
sd_wrap:
    cmpi.l  #NMAX,%d0
    ble.b   sd_wlo
    moveq   #0,%d0
    bra.b   sd_store
sd_wlo:
    tst.l   %d0
    bpl.b   sd_store
    moveq   #NMAX,%d0
sd_store:
    move.l  %d0,DJ_MODE
    rts

| ================= Hook A @ 0x400a4006 =================
| detour replaces `tst.b (0x8000667e).l` (6 B).  Runs every step tick.  The step engine
| keeps live values in D5-D7 / A3-A6 (pattern-blob ptr etc.) and FUN_4009e884 only saves
| D2-D4/A2, so the stub save/restores ALL regs.  The restore does not touch flags, so the
| trailing `tst.b (0x8000667e).l` still sets Z for the caller's `beq.w 0x400a412e`.
|   ColdFire has no `movem -(An)` -> lea a frame, movem into it.

    .global dj_a
dj_a:
    lea     -60(%sp),%sp
    movem.l %d0-%d7/%a0-%a6,(%sp)
    move.l  DJ_MODE,%d0
    beq.b   dja_disarm                 | OFF -> also clear any stale arm
    tst.l   ARR_ACT
    bne.b   dja_disarm                 | arranger running -> leave it alone
    tst.l   CHAIN_ACT
    bne.b   dja_disarm                 | pattern chain running -> leave it alone
    move.b  PEND_PAT,%d0
    cmpi.b  #-1,%d0
    beq.b   dja_disarm                 | nothing cued
    move.b  ACT_PAT,%d1
    cmp.b   %d1,%d0
    bne.b   dja_real
    move.b  PEND_BANK,%d0
    move.b  ACT_BANK,%d1
    cmp.b   %d1,%d0
    bne.b   dja_real
dja_disarm:
    clr.b   G_ARMED                    | 0 = idle
    moveq   #-1,%d1
    move.b  %d1,G_PCPAT                | 0xff = "no PC sent yet"
    bra.b   dja_ret
dja_real:
|   send the Program Change once per distinct pending pattern
    move.b  PEND_PAT,%d0
    cmp.b   G_PCPAT,%d0
    beq.b   dja_armstep
    move.b  %d0,G_PCPAT
    moveq   #0,%d0
    move.b  PEND_PAT,%d0
    move.l  %d0,-(%sp)                 | arg1: pending pattern
    moveq   #0,%d0
    move.b  PEND_BANK,%d0
    move.l  %d0,-(%sp)                 | arg0: pending bank
    jsr     PC_SEND
    addq.l  #8,%sp
dja_armstep:
    move.b  STEP,%d0
    move.b  %d0,G_STEP                 | always keep the resume step fresh
    tst.b   G_ARMED
    bne.b   dja_commit
    moveq   #-1,%d0
    move.b  %d0,G_ARMED                | tick 1: arm only (1 step of PC lead)
    bra.b   dja_ret
dja_commit:
    clr.b   STEP                       | tick 2: force the step==0 body this tick
dja_ret:
    movem.l (%sp),%d0-%d7/%a0-%a6
    lea     60(%sp),%sp
    tst.b   STOPFLAG                   | displaced original (sets Z for the beq.w)
    rts

| ================= Hook B @ 0x400a42fa =================
| detour replaces `move.l #0x8e56,%d0` (6 B), inside the step==0 body, just before the
| CHAIN-AFTER gate.  If armed: bypass the gate straight to the "switch confirmed" label.

    .global dj_b
dj_b:
    tst.b   G_ARMED
    beq.b   djb_orig
    move.l  #SW_LABEL,(%sp)            | return into 0x400a43a0 instead of 0x400a4300
    moveq   #1,%d6                     | D6 = "pending is a real change" (gate would set it)
    rts
djb_orig:
    move.l  #0x8e56,%d0               | displaced original
    rts

| ================= Hook C @ 0x400a4840 =================
| detour replaces `clr.b %d0 ; move.b %d0,(0x800065b6).l` (8 B) -> jsr dj_c + nop.
| Not armed: just do DAT_800065b6 = 0.  Armed: set D7 and DAT_800065b6 to
| (savedStep % newPatternLen) so the per-track recompute (all a function of D7) and the
| master step both resume at the playhead.  D6/D7 are live here; D0-D2/A0 are free.

    .global dj_c
dj_c:
    tst.b   G_ARMED
    bne.b   djc_fix
    clr.b   STEP
    rts
djc_fix:
    clr.b   G_ARMED
|   newLen = LEN_TBL[ PAT_SCALE[ newBank*0x9b340 + newPat*0x8ed8 ] ]
    moveq   #0,%d0
    move.b  ACT_PAT,%d0
    move.l  #0x8ed8,%d1
    muls.l  %d1,%d0                    | d0 = pat * 0x8ed8
    moveq   #0,%d1
    move.b  ACT_BANK,%d1
    move.l  #0x9b340,%d2
    muls.l  %d2,%d1                    | d1 = bank * 0x9b340
    add.l   %d1,%d0
    lea     PAT_SCALE,%a0
    moveq   #0,%d1
    move.b  (%a0,%d0.l),%d1            | d1 = scale index
    lea     LEN_TBL,%a0
    move.l  (%a0,%d1.l*4),%d1         | d1 = newLen
    moveq   #0,%d0
    move.b  G_STEP,%d0                 | saved master step
    tst.l   %d1
    ble.b   djc_store                 | guard: bad length -> just use savedStep
djc_mod:
    cmp.l   %d1,%d0
    blt.b   djc_store
    sub.l   %d1,%d0
    bra.b   djc_mod
djc_store:
    move.l  %d0,%d7                    | per-track positions derive from D7
    move.b  %d0,STEP                   | master step
    rts
