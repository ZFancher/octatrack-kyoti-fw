| SPDX-License-Identifier: MIT
| SPDX-FileCopyrightText: 2026 Zac-Kyoti
|
| patch_reload -- "RELOAD FROM PROJECT" (NOTES.md "Session 42").
|
|   [PTN] + [NO]   (while the sequencer is playing) opens a 3-item picker:
|                    RLD SEQ  /  RLD PARTS  /  RLD WHOLE
|                  each further [PTN]+[NO] tap cycles the selection.
|   [PTN] + [YES]  executes the highlighted option and closes the picker.
|                  The picker also auto-closes after ~1.5 s of no input.
|
|   SEQ    -- reload the ACTIVE pattern's SEQUENCE DATA (trigs, p-locks, length,
|             scale, trig conditions, microtiming, the pattern->part link) from
|             the CF card's last SAVE BANK snapshot (bankNN.strd), seamlessly.
|   PARTS  -- reload all 4 Parts to their last saved state (= stock RELOAD PART
|             x4: FUN_4004aab4).  Pure RAM.
|   WHOLE  -- PARTS + SEQ.
|
| Stock 1.40C only reloads from the card at whole-BANK granularity, and doing so
| stops audio (FUN_400a10c8 pre-step + FUN_400238a4 re-sync).  This is per-pattern
| and seamless: the SEQ file work rides the async storage task and the active
| pattern re-homes through FUN_400a1eea's own no-stop reload block (0x46c8028a);
| PARTS is the stock per-part reload, which already runs live.
|
| ---- state (volatile scratch, no persistence needed -- one-shot actions) ----
    .equ G_KIND,    0x80006a50          | worker request: 0 idle, 1 = SEQ (set by rl_yes)
    .equ G_PAT,     0x80006a51          | pattern to reload (byte)
    .equ G_MENU,    0x80006a52          | 1 = the picker overlay is open
    .equ G_SEL,     0x80006a53          | highlighted item: 0 SEQ / 1 PARTS / 2 WHOLE
    .equ G_TICKS,   0x80006a54          | picker auto-close frame countdown (long)

|   ---- stock symbols ----
    .equ PTN_HELD,  0x460d1742
    .equ PTN_USED,  0x460d173e
    .equ POPUP,     0x460e5cd0
    .equ ARR_ACT,   0x460d1aec
    .equ RUNNING,   0x800065b8          | transport state -- LONGWORD (=1 playing)
    .equ ACT_PAT,   0x800065be          | sequencer's active pattern (byte)
    .equ CUR_BANK,  0x80000002
    .equ RELOAD_NOW,0x46c8028a          | step engine polls this at 0x400a2530
    .equ POPUP2,    0x4005a0e0          | FUN_4005a0e0(text) -- bare text box, no timeout
    .equ CLOSE_CB,  0x40056bc0          | FUN_40056bc0 -- close the 0x460d1e64 popup
    .equ TOAST,     0x4005a2b8          | FUN_4005a2b8(text, dur) -- the "PART %d RELOADED" toast
    .equ NO_REL,    0x4005e276          | NO handler: release-cleanup entry
    .equ NO_PRESS,  0x4005e262          | NO handler: press path after the displaced 2 insns
    .equ YES_RESUME,0x4005e4d0          | YES handler: after the displaced 2 moves
    .equ JOB_POST,  0x40022778          | FUN_40022778(mask) -> post the type-0x14 storage job
    .equ JOB14_EXIT,0x400858a8          | 0x14 case: tst.l d0 ; ... ; done-dance ; -> dequeue loop
    .equ JOB14_ORIG,0x4008586c          | 0x14 case: resume after the displaced 2 insns
    .equ TICK_ORIG, 0x46c7dfba          | the SOFT-MUTE release watchdog var (displaced `lea`)

    .equ PARTRELD,  0x4004aab4          | FUN_4004aab4(part) -- reload one Part, d0!=0 = ok
    .equ SCN_RFRSH, 0x4004d948          | (-1) scene refresh   } stock RELOAD PART's
    .equ RFRSH1,    0x40032208          |                      } UI refresh sequence
    .equ RFRSH2,    0x4004d640          |                      } (0x4005e0aa..0x4005e0d8)
    .equ RFRSH3,    0x400486cc
    .equ RFRSH4,    0x4006dbe8
    .equ RFRSH5,    0x40077b00
    .equ RFRSH6,    0x4002f2f8
    .equ RDRAW,     0x46c7c72c          | redraw dirty flag (set to 1)

    .equ PROJDIR,   0x40025230          | (0,0) -> char* "<set>/<project>"
    .equ SPRINTF,   0x40013a08
    .equ FOPEN,     0x40016864          | (fh, path, mode, buf, size) buffered open, d0<0 = fail
    .equ FREAD,     0x40016564          | (fh, buf, count) buffered read
    .equ FCLOSE,    0x4001677c          | (fh)
    .equ PARSEPAT,  0x4008cebc          | (fh, destSlab, verWord) parse one PTRN chunk
    .equ FWMEMCPY,  0x40020898          | (dst, src, len)
    .equ FMT_STRD,  0x400b86d8          | "%s/bank%02d.strd"
    .equ MODE_R,    0x400b3289          | "r"
    .equ OPEN_BUF,  0x460a8f60          | the loader's 64 KB buffer (idle while we hold the task)
    .equ SCRATCH,   0x460aff60          | = OPEN_BUF + 0x7000 ; 0x8ed8 B pattern scratch
    .equ CKSUM,     0x460fab5c          | the deserialiser's rolling checksum (word)
    .equ BLOB,      0x400e21e0
    .equ BANKSTRIDE,0x9b340
    .equ PATSTRIDE, 0x8ed8

    .equ MENU_FRAMES, 0x1e0             | ~1.5 s at the 0x40052200 frame rate

    .text

| ================= [PTN] + [NO]  -- open / cycle the picker (@ 0x4005e25c) =================
| Detour replaces 6 bytes: move.l 8(%sp),%d0 ; beq.s 0x4005e276

    .global rl_combo
rl_combo:
    move.l  8(%sp),%d0                 | event
    bne.b   rlc_notrel
    jmp     NO_REL                     | event 0 -> stock release cleanup
rlc_notrel:
    moveq   #1,%d1
    cmp.l   %d0,%d1
    bne.w   rlc_stock                  | hold/other -> stock
    tst.l   PTN_HELD
    beq.w   rlc_stock                  | [PTN] not held -> stock
    tst.l   POPUP
    bne.w   rlc_stock                  | a modal dialog is up -> stock
    tst.l   ARR_ACT
    bne.w   rlc_stock                  | arranger -> stock
    tst.l   RUNNING
    beq.w   rlc_stock                  | only while playing
    tst.b   G_KIND
    bne.b   rlc_swallow                | a reload already queued -> swallow

    tst.b   G_MENU
    bne.b   rlc_cycle
    moveq   #1,%d0
    move.b  %d0,G_MENU                 | open
    clr.b   G_SEL                      | default = SEQ
    bra.b   rlc_show
rlc_cycle:
    moveq   #0,%d0
    move.b  G_SEL,%d0
    addq.l  #1,%d0
    cmpi.l  #3,%d0
    bcs.b   rlc_sel
    moveq   #0,%d0
rlc_sel:
    move.b  %d0,G_SEL
rlc_show:
    moveq   #0,%d0
    move.b  G_SEL,%d0
    lea     rl_menu_tbl,%a0
    move.l  (%a0,%d0.l*4),%a0          | a0 = text
    move.l  %a0,-(%sp)
    jsr     POPUP2                     | FUN_4005a0e0(text)
    addq.l  #4,%sp
    move.l  #MENU_FRAMES,%d0
    move.l  %d0,G_TICKS                | (re)arm the auto-close countdown
rlc_swallow:
    moveq   #1,%d0
    move.l  %d0,PTN_USED               | suppress the PTN chooser on release
    rts

rlc_stock:
    jmp     NO_PRESS

| ================= [PTN] + [YES]  -- execute + close (@ 0x4005e4c8) =================
| Detour replaces 8 bytes: move.l 4(%sp),%d1 ; move.l 8(%sp),%d0

    .global rl_yes
rl_yes:
    move.l  8(%sp),%d0                 | event
    moveq   #1,%d1
    cmp.l   %d0,%d1
    bne.w   rly_stock
    tst.l   PTN_HELD
    beq.w   rly_stock
    tst.b   G_MENU
    beq.w   rly_stock                  | picker not open -> stock YES
    tst.l   POPUP
    bne.w   rly_stock                  | a modal dialog came up -> stock YES

|   --- close the picker ---
    clr.b   G_MENU
    clr.l   G_TICKS
    lea     -16(%sp),%sp
    movem.l %d0-%d1/%a0-%a1,(%sp)
    jsr     CLOSE_CB                   | dismiss the 0x460d1e64 popup
    movem.l (%sp),%d0-%d1/%a0-%a1
    lea     16(%sp),%sp

    moveq   #0,%d2
    move.b  G_SEL,%d2                  | 0 SEQ / 1 PARTS / 2 WHOLE

|   --- PARTS or WHOLE: reload all 4 Parts now (key context, like stock) ---
    tst.l   %d2
    beq.b   rly_seq
    jsr     rl_parts
    moveq   #1,%d0
    cmp.l   %d2,%d0
    beq.b   rly_toast                  | PARTS only -> done

rly_seq:
|   --- SEQ or WHOLE: arm + post the async SEQ job ---
    move.b  ACT_PAT,%d0
    move.b  %d0,G_PAT
    moveq   #1,%d0
    move.b  %d0,G_KIND
    moveq   #0,%d0
    move.b  CUR_BANK,%d0
    moveq   #1,%d1
    lsl.l   %d0,%d1
    move.l  %d1,-(%sp)
    jsr     JOB_POST                   | FUN_40022778(1<<curbank)
    addq.l  #4,%sp

rly_toast:
    lea     rl_msg_seq,%a0
    tst.l   %d2
    beq.b   rly_t1
    lea     rl_msg_parts,%a0
    moveq   #1,%d0
    cmp.l   %d2,%d0
    beq.b   rly_t1
    lea     rl_msg_whole,%a0
rly_t1:
    pea     0x44
    move.l  %a0,-(%sp)
    jsr     TOAST                      | FUN_4005a2b8(text, dur)
    addq.l  #8,%sp
    moveq   #1,%d0
    move.l  %d0,PTN_USED
    rts                                | swallow YES

rly_stock:
    move.l  4(%sp),%d1                 | displaced
    move.l  8(%sp),%d0                 | displaced
    jmp     YES_RESUME

| ---- rl_parts: FUN_4004aab4(0..3) + the stock RELOAD PART UI refresh ----
    .global rl_parts
rl_parts:
    lea     -8(%sp),%sp
    movem.l %d2-%d3,(%sp)
    moveq   #0,%d2
rlp_loop:
    move.l  %d2,-(%sp)
    jsr     PARTRELD                   | FUN_4004aab4(part) -- ignore d0 (0 = never saved)
    addq.l  #4,%sp
    addq.l  #1,%d2
    moveq   #4,%d3
    cmp.l   %d3,%d2
    blt.b   rlp_loop

    moveq   #1,%d0
    move.l  %d0,RDRAW
    pea     0xffffffff
    jsr     SCN_RFRSH
    jsr     RFRSH1
    jsr     RFRSH2
    jsr     RFRSH3
    jsr     RFRSH4
    jsr     RFRSH5
    jsr     RFRSH6
    addq.l  #4,%sp

    movem.l (%sp),%d2-%d3
    lea     8(%sp),%sp
    rts

rl_menu_tbl:
    .long   rl_msg_seq
    .long   rl_msg_parts
    .long   rl_msg_whole
rl_msg_seq:
    .asciz "RLD SEQ"
    .align 2
rl_msg_parts:
    .asciz "RLD PARTS"
    .align 2
rl_msg_whole:
    .asciz "RLD WHOLE"
    .align 2

| ================= picker auto-close tick -- jsr detour @ 0x400522ca =================
| replaces `lea 0x46c7dfba,%a2` (6 B) in the engine per-control-frame handler
| (0x40052200).  D0 is dead here (reloaded at 0x400522de).

    .global rl_tick
rl_tick:
    tst.b   G_MENU
    beq.b   rlt_orig
    move.l  G_TICKS,%d0
    ble.b   rlt_expire
    subq.l  #1,%d0
    move.l  %d0,G_TICKS
    bne.b   rlt_orig
rlt_expire:
    clr.b   G_MENU
    lea     -16(%sp),%sp
    movem.l %d0-%d1/%a0-%a1,(%sp)
    jsr     CLOSE_CB
    movem.l (%sp),%d0-%d1/%a0-%a1
    lea     16(%sp),%sp
rlt_orig:
    lea     TICK_ORIG,%a2              | displaced original
    rts

| ================= the SEQ worker -- jmp detour @ the type-0x14 case 0x40085864 =================
| replaces 8 bytes: move.l %a2,%fp@(-650) ; move.l %a2@(4),%sp@-

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
    move.b  G_PAT,%d5                  | d5 = target pattern P
    clr.b   G_KIND                     | consume now -- a re-entrant real RELOAD BANK
                                       | must NOT see it set
    move.w  CKSUM,%d0
    move.w  %d0,rl_cksum

    jsr     rl_openstrd                | -> d0 = open result (fh in rl_fh)
    tst.l   %d0
    bpl.b   rlj_opened
    moveq   #-12,%d0                   | ENOENT -> stock "THIS BANK HAS NEVER BEEN SAVED!"
    bra.w   rlj_exit

rlj_opened:
    move.l  #22,-(%sp)                 | 22-byte header
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

    moveq   #0,%d4                     | i
rlj_ploop:
    move.l  %d6,-(%sp)
    pea     SCRATCH
    pea     rl_fh
    jsr     PARSEPAT                   | FUN_4008cebc(fh, SCRATCH, verWord)
    lea     12(%sp),%sp
    tst.l   %d0
    bmi.w   rlj_readfail
    addq.l  #1,%d4
    cmp.l   %d5,%d4
    ble.b   rlj_ploop                  | parse patterns 0..P (0..P-1 discarded)

    pea     rl_fh
    jsr     FCLOSE
    addq.l  #4,%sp

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

    move.l  %d5,%d0
    move.b  ACT_PAT,%d1
    cmp.b   %d1,%d0
    bne.b   rlj_ok
    moveq   #1,%d0
    move.l  %d0,RELOAD_NOW

rlj_ok:
    moveq   #1,%d0
rlj_exit:
    move.w  rl_cksum,%d1
    move.w  %d1,CKSUM
    movem.l (%sp),%d2-%d7/%a2-%a5
    lea     40(%sp),%sp
    jmp     JOB14_EXIT

rlj_readfail:
    pea     rl_fh
    jsr     FCLOSE
    addq.l  #4,%sp
    moveq   #-1,%d0
    bra.b   rlj_exit

| ---- rl_openstrd: d0 = open result; builds rl_pathbuf, fills rl_fh ----
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
    addq.l  #1,%d1
    move.l  %d1,-(%sp)                 | bank number (1-based)
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
