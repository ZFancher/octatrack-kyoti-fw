| SPDX-License-Identifier: MIT
| SPDX-FileCopyrightText: 2026 Zac-Kyoti
|
| patch_reload -- "RELOAD FROM PROJECT" (NOTES.md "Session 42"), MVP = SEQ DATA.
|
|   [PTN] + [NO]  (while playing)  reloads the ACTIVE pattern's SEQUENCE DATA
|                 (trigs, p-locks, length, scale, trig conditions, microtiming,
|                 the pattern->part link) from the CF card's last SAVE BANK
|                 snapshot (bankNN.strd) -- WITHOUT stopping the sequencer.
|
| Stock 1.40C only reloads from the card at whole-BANK granularity, and doing so
| cuts audio (the confirm pre-step FUN_400a10c8 + the end re-sync FUN_400238a4).
| This is per-pattern and seamless: the file work rides the async storage task,
| and the active pattern re-homes through FUN_400a1eea's own no-stop reload block
| (the 0x46c8028a "reload now" flag).
|
| MVP scope: SEQ DATA only, active pattern only, transport running.  ALL PARTS
| (= FUN_4004aab4(0..3), pure RAM), WHOLE PATTERN, the 3-way picker, and stopped
| transport are phase 2.
|
| ---- the combo ----
| PTN handler FUN_5a044 press sets 0x460d1742 = 1 ("PTN held"); nothing else
| reads it -> [PTN]+X is a free chord (DIRECT JUMP's [PTN]+[YES] is the sibling).
| Hook the NO handler 0x4005e25c (keycode 0x32).  NO PRESS + [PTN] held + no
| arranger + no modal popup + transport running -> arm + post the storage job +
| swallow.  Otherwise replay the displaced prologue and let stock NO resume.
|
| ---- the worker (runs on the storage task FUN_4008445c -- may block on I/O) ----
| Hook the type-0x14 storage case entry 0x40085864.  When G_KIND != 0:
|   1  FUN_40016864 open "<proj>/bankNN.strd" "r"  (28 KB of the loader's own
|      64 KB buffer 0x460a8f60; the storage task holds the CPU so borrowing it
|      is safe).  d0 < 0  ->  d0 = -12  ->  rejoin the case's exit with that
|      result, so the done-dance (FUN_40023bf4) shows the STOCK "THIS BANK HAS
|      NEVER BEEN SAVED! NOTHING TO RELOAD!" dialog for free.
|   2  read the 22-byte bank-file header; version word = header[0x14..0x15].
|   3  parse patterns 0..P sequentially with the firmware's OWN per-pattern
|      chunk parser FUN_4008cebc(fh, scratch, verWord) -- 0..P-1 discarded into
|      scratch (0x460a8f60 + 0x7000, 0x8ed8 B), pattern P kept there.  No stride
|      math, no seek: the sequential parse keeps the rolling checksum consistent
|      (FUN_4008cebc does not self-validate it -- only FUN_4008ded0's whole-file
|      tail does, which we never run).  0x460fab5c saved/restored around it.
|   4  FUN_4001677c close.
|   5  FUN_40020898  copy scratch -> the live slab
|      (0x400e21e0 + curbank*0x9b340 + P*0x8ed8, 0x8ed8 B).  One memcpy; the
|      inconsistent window is < 1 step and 0x46c8028a re-homes the pattern on
|      the next tick anyway.  Nothing else's data is touched.
|   6  if P == active pattern (0x800065be):  move.l #1, 0x46c8028a
|   7  rejoin the 0x14 case's clean exit (0x400858a8) -> overlay dismisses,
|      storage task returns to its dequeue loop.
| FUN_400a10c8 (pre-step) and FUN_400238a4 (re-sync) are NEVER reached -> no cut.

    .equ G_KIND,    0x80006a50          | 0 = idle, 1 = SEQ-DATA reload requested (byte)
    .equ G_PAT,     0x80006a51          | pattern index to reload (byte)

|   ---- stock symbols ----
    .equ PTN_HELD,  0x460d1742
    .equ PTN_USED,  0x460d173e
    .equ POPUP,     0x460e5cd0
    .equ ARR_ACT,   0x460d1aec
    .equ RUNNING,   0x800065b8          | transport state -- LONGWORD (=1 playing)
    .equ ACT_PAT,   0x800065be          | sequencer's active pattern (byte)
    .equ CUR_BANK,  0x80000002          | current bank (byte)
    .equ RELOAD_NOW,0x46c8028a          | step engine polls this at 0x400a2530
    .equ TOAST,     0x4005a2b8          | FUN_4005a2b8(dur, text) -- the "PART %d RELOADED" toast
    .equ NO_REL,    0x4005e276          | NO handler: release-cleanup entry
    .equ NO_PRESS,  0x4005e262          | NO handler: press path after the displaced 2 insns
    .equ JOB_POST,  0x40022778          | FUN_40022778(mask) -> post the type-0x14 storage job
    .equ JOB14_EXIT,0x400858a8          | 0x14 case: tst.l d0 ; ... ; done-dance ; -> dequeue loop
    .equ JOB14_ORIG,0x4008586c          | 0x14 case: resume after the displaced 2 insns

    .equ PROJDIR,   0x40025230          | (0,0) -> char* "<set>/<project>"
    .equ SPRINTF,   0x40013a08
    .equ FOPEN,     0x40016864          | (fh, path, mode, buf, size) buffered open, d0<0 = fail
    .equ FREAD,     0x40016564          | (fh, buf, count) buffered read, d0<=0 = eof/err
    .equ FCLOSE,    0x4001677c          | (fh)
    .equ PARSEPAT,  0x4008cebc          | (fh, destSlab, verWord) parse one PTRN chunk, d0<0 = fail
    .equ FWMEMCPY,  0x40020898          | (dst, src, len)
    .equ FMT_STRD,  0x400b86d8          | "%s/bank%02d.strd"
    .equ MODE_R,    0x400b3289          | "r"
    .equ OPEN_BUF,  0x460a8f60          | the loader's 64 KB buffer (idle while we hold the task)
    .equ SCRATCH,   0x460aff60          | = OPEN_BUF + 0x7000 ; 0x8ed8 B pattern scratch (fits in 64 KB)
    .equ CKSUM,     0x460fab5c          | the deserialiser's rolling checksum (word)
    .equ BLOB,      0x400e21e0
    .equ BANKSTRIDE,0x9b340
    .equ PATSTRIDE, 0x8ed8

    .text

| ================= combo: [PTN] + [NO]  (hook @ 0x4005e25c) =================
| Detour replaces 6 bytes:
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
    beq.w   rlc_stock                  | [PTN] not held -> stock
    tst.l   POPUP
    bne.w   rlc_stock                  | a modal dialog is up -> stock
    tst.l   ARR_ACT
    bne.w   rlc_stock                  | arranger -> stock
    tst.l   RUNNING
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

    pea     0x44                       | duration -- same as stock "PART %d RELOADED"
    pea     rl_msg
    jsr     TOAST                      | FUN_4005a2b8(dur, text) -- non-blocking op-toast
    addq.l  #8,%sp

rlc_swallow:
    moveq   #1,%d0
    move.l  %d0,PTN_USED               | suppress the PTN chooser on release
    rts                                | swallow the NO key

rlc_stock:
    jmp     NO_PRESS                   | resume at 0x4005e262 (d0 = event, unused there)

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
    move.b  G_PAT,%d5                  | d5 = target pattern P (latched)
    clr.b   G_KIND                     | consume now -- a re-entrant 0x14 (real RELOAD BANK)
                                       | must NOT see it set
    move.w  CKSUM,%d0                  | stash the deserialiser's rolling checksum in the cave
    move.w  %d0,rl_cksum               | (memory-authoritative -- survives every firmware call)

|   --- 1: open <proj>/bank(curbank+1).strd ---
    jsr     rl_openstrd                | -> d0 = open result (fh in rl_fh, path built)
    tst.l   %d0
    bpl.b   rlj_opened
    moveq   #-12,%d0                   | ENOENT -> the done-dance shows the stock
    bra.w   rlj_exit                   | "THIS BANK HAS NEVER BEEN SAVED!" dialog

rlj_opened:
|   --- 2: 22-byte header -> version word in d6 ---
    move.l  #22,-(%sp)
    pea     rl_hdrbuf
    pea     rl_fh
    jsr     FREAD
    lea     12(%sp),%sp
    tst.l   %d0
    ble.w   rlj_readfail
    moveq   #0,%d6
    move.b  rl_hdrbuf+0x14,%d6
    lsl.l   #8,%d6
    moveq   #0,%d0
    move.b  rl_hdrbuf+0x15,%d0
    or.l    %d0,%d6                    | d6 = version word

|   --- 3: parse patterns 0..P (0..P-1 discarded), all into SCRATCH ---
    moveq   #0,%d4                     | i
rlj_ploop:
    move.l  %d6,-(%sp)                 | verWord
    pea     SCRATCH
    pea     rl_fh
    jsr     PARSEPAT                   | FUN_4008cebc(fh, SCRATCH, verWord)
    lea     12(%sp),%sp
    tst.l   %d0
    bmi.w   rlj_readfail
    addq.l  #1,%d4
    cmp.l   %d5,%d4
    ble.b   rlj_ploop                  | while i <= P

|   --- 4: close ---
    pea     rl_fh
    jsr     FCLOSE
    addq.l  #4,%sp

|   --- 5: SCRATCH -> live slab (blob + curbank*stride + P*0x8ed8) ---
    moveq   #0,%d0
    move.b  CUR_BANK,%d0
    move.l  #BANKSTRIDE,%d1
    muls.l  %d1,%d0
    move.l  #BLOB,%a4
    add.l   %d0,%a4
    move.l  %d5,%d0
    move.l  #PATSTRIDE,%d1
    muls.l  %d1,%d0
    add.l   %d0,%a4                    | a4 = live slab for P
    move.l  #PATSTRIDE,-(%sp)
    pea     SCRATCH
    move.l  %a4,-(%sp)
    jsr     FWMEMCPY                   | memcpy(liveslab, SCRATCH, 0x8ed8)
    lea     12(%sp),%sp

|   --- 6: seamless reload iff P is the active pattern ---
    move.l  %d5,%d0
    move.b  ACT_PAT,%d1
    cmp.b   %d1,%d0
    bne.b   rlj_ok
    moveq   #1,%d0
    move.l  %d0,RELOAD_NOW

rlj_ok:
    moveq   #1,%d0                     | result ok
rlj_exit:
    move.w  rl_cksum,%d1               | restore the deserialiser's rolling checksum
    move.w  %d1,CKSUM
    movem.l (%sp),%d2-%d7/%a2-%a5
    lea     40(%sp),%sp
    jmp     JOB14_EXIT                 | rejoin the 0x14 case's done-dance -> loop
                                       | (d0 >= 0 -> "done", d0 = -12 -> stock warning)

rlj_readfail:
    pea     rl_fh
    jsr     FCLOSE                     | live slab untouched (parse writes SCRATCH only)
    addq.l  #4,%sp
    moveq   #-1,%d0
    bra.b   rlj_exit

| ---- rl_openstrd:  d0 = open result; uses CUR_BANK, builds rl_pathbuf, fills rl_fh ----
|   preserves d2-d7/a2-a5.
    .global rl_openstrd
rl_openstrd:
    lea     -40(%sp),%sp
    movem.l %d2-%d7/%a2-%a5,(%sp)

    clr.l   -(%sp)
    clr.l   -(%sp)
    jsr     PROJDIR                    | d0 = char* project dir
    addq.l  #8,%sp

    moveq   #0,%d1
    move.b  CUR_BANK,%d1
    addq.l  #1,%d1                     | bank number (1-based)
    move.l  %d1,-(%sp)
    move.l  %d0,-(%sp)                 | project dir
    move.l  #FMT_STRD,-(%sp)
    pea     rl_pathbuf
    jsr     SPRINTF                    | sprintf(rl_pathbuf, "%s/bank%02d.strd", dir, bank)
    lea     16(%sp),%sp

    lea     rl_fh,%a0                  | zero the 64-byte handle struct
    moveq   #16,%d0
rlo_zero:
    clr.l   (%a0)+
    subq.l  #1,%d0
    bne.b   rlo_zero

    move.l  #0x7000,-(%sp)             | open buffer size (28 KB of OPEN_BUF)
    pea     OPEN_BUF
    pea     MODE_R
    pea     rl_pathbuf
    pea     rl_fh
    jsr     FOPEN                      | FUN_40016864(fh, path, "r", buf, size)
    lea     20(%sp),%sp

    movem.l (%sp),%d2-%d7/%a2-%a5
    lea     40(%sp),%sp
    rts

    .align 2
rl_cksum:
    .space 4
rl_pathbuf:
    .space 128
rl_fh:
    .space 64
rl_hdrbuf:
    .space 32
