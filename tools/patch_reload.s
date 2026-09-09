| SPDX-License-Identifier: MIT
| SPDX-FileCopyrightText: 2026 Zac-Kyoti
|
| patch_reload -- "RELOAD FROM PROJECT" (NOTES.md "Session 42"), MVP.
|
|   [PTN] + [NO]   reloads the ACTIVE pattern's SEQUENCE DATA (trigs, p-locks,
|                  length, scale, trig conditions, microtiming, the pattern->part
|                  link) from the CF card's last SAVE BANK snapshot (bankNN.strd)
|                  -- WITHOUT stopping the sequencer.  Flashes "RELOAD SEQ".
|
| Stock 1.40C only reloads from the card at whole-BANK granularity, and doing so
| cuts audio (the confirm pre-step FUN_400a10c8 + the end re-sync FUN_400238a4).
| This is per-pattern and seamless: the file work rides the async storage task,
| and the active pattern re-homes through FUN_400a1eea's own no-stop reload block
| (the 0x46c8028a "reload now" flag).
|
| MVP scope: SEQ DATA only, active pattern only, transport running only.  The
| 3-way picker (WHOLE PATTERN / ALL PARTS / SEQ DATA) + stopped transport + a
| non-active target pattern are phase 2.
|
| ---- the combo ----
| PTN handler FUN_5a044 press sets 0x460d1742 = 1 ("PTN held"); nothing else
| reads it -> [PTN]+X is a free chord (DIRECT JUMP's [PTN]+[YES] is the sibling).
| We hook the NO handler 0x4005e25c (keycode 0x32).  On a NO PRESS with [PTN]
| held (no arranger / no modal popup / YES-NO not disabled / transport running)
| we arm + post the job and swallow the key; else the displaced prologue is
| replayed and the stock handler resumes.
|
| ---- the worker (runs on the storage task FUN_4008445c -- may block on I/O) ----
| Hook the type-0x14 case entry 0x40085864.  When G_KIND != 0:
|   1..4  load <proj>/bankNN.strd (NN = curbank+1) into a SCRATCH bank region
|         scratch = 0x400e21e0 + ((curbank+8)&15) * 0x9b340   (a non-current bank)
|   5     memcpy one pattern slab:  scratch + P*0x8ed8 -> live blob + P*0x8ed8
|         (0x8ed8 B).  Other patterns' unsaved edits live in their own slabs -> safe.
|   6     restore the scratch bank: re-load <proj>/bank(S+1).work into it.
|   7     if P == active pattern (0x800065be):  move.l #1, 0x46c8028a
|         -> FUN_400a1eea adopts the patched slab on the next step tick, no stop.
|   8     rejoin the 0x14 case's clean exit (0x400858a8) so the overlay dismisses
|         and the storage task returns to its dequeue loop.
| FUN_400a10c8 (pre-step) and FUN_400238a4 (re-sync) are NEVER reached -> no cut.

    .equ G_KIND,    0x80006a50          | 0 = idle, 1 = SEQ-DATA reload requested (byte)
    .equ G_PAT,     0x80006a51          | pattern index to reload (byte)

|   ---- stock symbols ----
    .equ PTN_HELD,  0x460d1742
    .equ PTN_USED,  0x460d173e
    .equ NO_DISABLE,0x800000b8
    .equ POPUP,     0x460e5cd0
    .equ ARR_ACT,   0x460d1aec
    .equ RUNNING,   0x800065b8
    .equ ACT_PAT,   0x800065be
    .equ CUR_BANK,  0x80000002
    .equ RELOAD_NOW,0x46c8028a
    .equ SHOW_MSG,  0x40059f8c          | FUN_40059f8c(text, ticks, enable, on_timeout)
    .equ NO_REL,    0x4005e276          | NO handler: release-cleanup entry
    .equ NO_PRESS,  0x4005e262          | NO handler: press path after the displaced 2 insns
    .equ JOB_POST,  0x40022778          | FUN_40022778(mask) -> post the type-0x14 storage job
    .equ JOB14_EXIT,0x400858a8          | 0x14 case: tst.l d0 ; ... ; done-dance ; -> dequeue loop
    .equ JOB14_ORIG,0x4008586c          | 0x14 case: resume after the displaced 2 insns

    .equ PROJDIR,   0x40025230          | (0,0) -> char* "<set>/<project>"
    .equ SPRINTF,   0x40013a08
    .equ FOPEN,     0x40016864          | (fh, path, mode, buf, size) buffered open, d0<0 = fail
    .equ FCLOSE,    0x4001677c          | (fh)
    .equ DESER,     0x4008ded0          | (fh, destRegion, 0) deserialise a bank file, d0<0 = fail
    .equ FWMEMCPY,  0x40020898          | (dst, src, len)
    .equ FMT_STRD,  0x400b86d8          | "%s/bank%02d.strd"
    .equ FMT_WORK,  0x400b86c7          | "%s/bank%02d.work"
    .equ MODE_R,    0x400b3289          | "r"
    .equ OPEN_BUF,  0x460a8f60          | the 64 KB buffer the stock loader uses
    .equ BLOB,      0x400e21e0
    .equ BANKSTRIDE,0x9b340
    .equ PATSTRIDE, 0x8ed8

    .text

| ================= combo: [PTN] + [NO]  (hook @ 0x4005e25c) =================
| Detour replaces the first 6 bytes of the NO handler:
|     0x4005e25c  202f 0008   move.l 8(%sp),%d0     ; event (1 press / 0 release / 2 hold)
|     0x4005e260  6714        beq.s  0x4005e276      ; release -> cleanup
| with `jmp rl_combo`.  Stack on entry: 0(sp)=ret, 4(sp)=keycode, 8(sp)=event.

    .global rl_combo
rl_combo:
    move.l  8(%sp),%d0                 | event
    bne.b   rlc_notrel
    jmp     NO_REL                     | event 0 -> stock release cleanup
rlc_notrel:
    moveq   #1,%d1
    cmp.l   %d0,%d1
    bne.w   rlc_stock                  | hold (2) or other -> stock press path
    tst.l   PTN_HELD
    beq.w   rlc_stock
    tst.l   NO_DISABLE
    bne.w   rlc_stock
    tst.l   POPUP
    bne.w   rlc_stock
    tst.l   ARR_ACT
    bne.w   rlc_stock
    tst.b   RUNNING
    beq.w   rlc_stock                  | MVP: only while playing
    tst.b   G_KIND
    bne.b   rlc_swallow                | a reload already queued -> just swallow

    move.b  ACT_PAT,%d0
    move.b  %d0,G_PAT
    moveq   #1,%d0
    move.b  %d0,G_KIND                 | 1 = SEQ DATA

    moveq   #0,%d0
    move.b  CUR_BANK,%d0
    moveq   #1,%d1
    lsl.l   %d0,%d1                    | d1 = 1 << curbank
    move.l  %d1,-(%sp)
    jsr     JOB_POST                   | FUN_40022778(mask)
    addq.l  #4,%sp

    clr.l   -(%sp)                     | on_timeout = 0
    pea     1                          | enable = 1
    pea     0x2c                       | ticks (~0.7 s)
    pea     rl_msg
    jsr     SHOW_MSG
    lea     16(%sp),%sp

rlc_swallow:
    moveq   #1,%d0
    move.l  %d0,PTN_USED               | suppress the PTN chooser on release
    rts                                | swallow the NO key

rlc_stock:
    jmp     NO_PRESS                   | resume at 0x4005e262 (d0 still = event, unused there)

rl_msg:
    .asciz "RELOAD SEQ"
    .align 2

| ================= worker: hook @ the type-0x14 case 0x40085864 =================
| Detour replaces 8 bytes:
|     0x40085864  2d4a fd76   move.l %a2,%fp@(-650)   ; msg -> frame slot (used by the exit)
|     0x40085868  2f2a 0004   move.l %a2@(4),%sp@-    ; arg for jsr 0x40084094
| with `jmp rl_job` + nop.  a2 = the dequeued msg, fp = FUN_4008445c's frame.

    .global rl_job
rl_job:
    move.l  %a2,-650(%fp)              | displaced #1 -- stash msg for both exit paths
    tst.b   G_KIND
    bne.b   rlj_ours
    move.l  %a2@(4),-(%sp)            | displaced #2
    jmp     JOB14_ORIG                | not ours -> stock: jsr 0x40084094

rlj_ours:
    lea     -40(%sp),%sp
    movem.l %d2-%d7/%a2-%a5,(%sp)

    moveq   #0,%d5
    move.b  G_PAT,%d5                  | d5 = target pattern (latched)
    clr.b   G_KIND                     | consume the request now -- a re-entrant 0x14 job
                                       | (e.g. a real RELOAD BANK) must NOT see it set

    moveq   #0,%d7
    move.b  CUR_BANK,%d7               | d7 = curbank
    move.l  %d7,%d6
    addq.l  #8,%d6
    andi.l  #15,%d6                    | d6 = S = (curbank+8)&15

    move.l  #BANKSTRIDE,%d0
    move.l  %d6,%d1
    muls.l  %d0,%d1
    move.l  #BLOB,%a5
    add.l   %d1,%a5                    | a5 = scratch region
    move.l  %d7,%d1
    muls.l  %d0,%d1
    move.l  #BLOB,%a4
    add.l   %d1,%a4                    | a4 = live blob base (current bank)

|   --- 1..4: bank(curbank+1).strd -> scratch ---
    move.l  %d7,%d0
    addq.l  #1,%d0
    move.l  #FMT_STRD,%d1
    move.l  %a5,%a0
    jsr     rl_loadbank
    tst.l   %d0
    bmi.b   rlj_fail

|   --- 5: memcpy the slab  scratch + P*stride -> live + P*stride ---
    move.l  %d5,%d0
    move.l  #PATSTRIDE,%d1
    muls.l  %d1,%d0                    | d0 = P * 0x8ed8
    move.l  %a4,%d2
    add.l   %d0,%d2                    | dst = live + P*stride
    move.l  %a5,%d3
    add.l   %d0,%d3                    | src = scratch + P*stride
    move.l  #PATSTRIDE,-(%sp)
    move.l  %d3,-(%sp)
    move.l  %d2,-(%sp)
    jsr     FWMEMCPY                   | FUN_40020898(dst, src, len)
    lea     12(%sp),%sp

|   --- 6: restore the scratch bank from bank(S+1).work (best-effort) ---
    move.l  %d6,%d0
    addq.l  #1,%d0
    move.l  #FMT_WORK,%d1
    move.l  %a5,%a0
    jsr     rl_loadbank

|   --- 7: seamless reload iff P is the active pattern ---
    move.l  %d5,%d0
    move.b  ACT_PAT,%d1
    cmp.b   %d1,%d0
    bne.b   rlj_done
    moveq   #1,%d0
    move.l  %d0,RELOAD_NOW

rlj_done:
rlj_fail:
    movem.l (%sp),%d2-%d7/%a2-%a5
    lea     40(%sp),%sp
    moveq   #1,%d0                     | result ok (rlj_fail: nothing was half-written to live)
    jmp     JOB14_EXIT

| ---- rl_loadbank(d0 = bank number 1-based, d1 = fmt string, a0 = dest region) ----
|   opens "<proj>/bankNN.<work|strd>" "r" and deserialises the bank into (a0).
|   returns d0 = deser result (<0 on any failure).  Preserves d2-d7/a2-a5.
    .global rl_loadbank
rl_loadbank:
    lea     -40(%sp),%sp
    movem.l %d2-%d7/%a2-%a5,(%sp)
    move.l  %a0,%a2                    | a2 = dest
    move.l  %d0,%a4                    | a4 = bank number (int in an addr reg -- just stored/pushed)
    move.l  %d1,%a3                    | a3 = fmt

    clr.l   -(%sp)
    clr.l   -(%sp)
    jsr     PROJDIR                    | FUN_40025230(0,0) -> d0 = char* project dir
    addq.l  #8,%sp

    move.l  %a4,-(%sp)                 | bank number
    move.l  %d0,-(%sp)                 | project dir
    move.l  %a3,-(%sp)                 | fmt
    pea     rl_pathbuf
    jsr     SPRINTF                    | sprintf(rl_pathbuf, fmt, projdir, banknum)
    lea     16(%sp),%sp

    lea     rl_fh,%a0                  | zero the handle struct (64 B)
    moveq   #16,%d0
rlb_zero:
    clr.l   (%a0)+
    subq.l  #1,%d0
    bne.b   rlb_zero

    move.l  #0x10000,-(%sp)
    pea     OPEN_BUF
    pea     MODE_R
    pea     rl_pathbuf
    pea     rl_fh
    jsr     FOPEN                      | FUN_40016864(fh, path, "r", buf, size)
    lea     20(%sp),%sp
    tst.l   %d0
    bmi.b   rlb_fail

    clr.l   -(%sp)                     | arg2 = 0
    move.l  %a2,-(%sp)                 | arg1 = dest
    pea     rl_fh                      | arg0 = fh
    jsr     DESER                      | FUN_4008ded0(fh, dest, 0)
    lea     12(%sp),%sp
    move.l  %d0,%d2                    | d2 = deser result (preserved by our movem)

    pea     rl_fh
    jsr     FCLOSE
    addq.l  #4,%sp

    move.l  %d2,%d0
    movem.l (%sp),%d2-%d7/%a2-%a5
    lea     40(%sp),%sp
    rts

rlb_fail:
    moveq   #-1,%d0
    movem.l (%sp),%d2-%d7/%a2-%a5
    lea     40(%sp),%sp
    rts

    .align 2
rl_pathbuf:
    .space 128
rl_fh:
    .space 64
