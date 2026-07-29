    .cpu 5407
    .text
| =====================================================================
|  BANK PAGING — Stage 3 (PAGE key UX; hardcoded page "_2")
|
|  In the SELECT BANK screen, [PAGE] pops "LOAD BANKS?" YES/NO. YES loads
|  the sibling "<current>_2"'s 15 non-playing banks (redirected, audio-safe,
|  reusing the S1 mechanism) and re-enters SELECT BANK; NO aborts.
|
|  Reuses S1 infra: gate FUN_40025230 (g_redirect), redirect gating, and a
|  now-CONDITIONAL done hook (skip re-sync only for a paging load, so a
|  normal RELOAD still re-syncs). The crude FUN_40063bf8 trigger is dropped.
|
|  Hardcoded to page "_2"; cycling / existence-check / page LED come in S3b.
| =====================================================================

| ---- PAGE hook: FUN_4004ffc4 @entry (args: keycode 4(SP), edge 8(SP)) ----
page_cave:
    move.l  8(%sp), %d0            | edge
    subq.l  #1, %d0
    bne.w   pc_stock               | not a press -> stock PAGE
    move.l  0x460d1e5c, %d0        | select-window handle
    beq.w   pc_stock               | no select window -> stock
    move.l  0x460d1e60, %d0        | select callback
    cmp.l   #0x4007b408, %d0       | == the BANK-select callback?
    bne.w   pc_stock               | some other select window -> stock
    move.l  0x460e5cd0, %d0        | a popup already open?
    bne.b   pc_swallow             | yes -> just swallow, no double popup
    | show "LOAD BANKS?" YES/NO
    pea     confirm_handler
    move.l  #3, -(%sp)             | param4
    pea     lines_arr
    move.l  #1, -(%sp)             | nLines
    pea     title_load
    jsr     0x4006d57c
    lea     0x14(%sp), %sp
pc_swallow:
    rts                            | swallow PAGE (dispatcher cleans args)
pc_stock:
    lea     -0x10(%sp), %sp        | replicate displaced entry (lea + movem)
    movem.l %d2-%d4/%a2, (%sp)
    jmp     0x4004ffcc             | resume FUN_4004ffc4 after the movem

| ---- confirm handler: p at 4(SP); p==0 YES, p!=0 NO ----
confirm_handler:
    move.l  4(%sp), %d0
    bne.b   ch_done                | NO -> nothing
    | build "<name>_2"
    pea     0x100f8378
    pea     fmt_s2
    pea     sib_name
    jsr     0x40013a08             | sprintf(sib_name,"%s_2",name)
    lea     12(%sp), %sp
    lea     sib_name, %a0
    move.l  %a0, g_redirect        | arm redirect
    | mask = all banks except the playing one
    mvz.b   0x100b14ce, %d1
    moveq   #1, %d0
    lsl.l   %d1, %d0
    not.l   %d0
    move.l  %d0, -(%sp)            | mask arg for the poster
    jsr     0x40022778             | post the masked reload job
    addq.l  #4, %sp
    | re-enter SELECT BANK (as a fresh [BANK] press: keycode 0x2f, edge 1)
    move.l  #1, -(%sp)
    move.l  #0x2f, -(%sp)
    jsr     0x4007af80
    addq.l  #8, %sp
ch_done:
    rts

| ---- redirect gate: FUN_40025230 @0x40025244 (projname==0 default) ----
gate_cave:
    move.l  g_redirect, %a0
    tst.l   %a0
    bne.b   gc_use
    lea     0x100f8378, %a0
gc_use:
    jmp     0x4002524a

| ---- load done: FUN_40023998 @0x400239a2 (conditional re-sync + disarm) ----
done_cave:
    move.l  g_redirect, %d0
    beq.b   dc_resync              | normal reload -> do the stock re-sync
    clr.l   g_redirect             | paging load -> disarm + SKIP re-sync
    move.l  #1, -(%sp)             | replicate displaced `pea (0x1).w`
    jmp     0x400239aa
dc_resync:
    jsr     0x400238a4             | stock re-sync
    move.l  #1, -(%sp)
    jmp     0x400239aa

| ---- data ----
    .balign 4
g_redirect: .long 0
fmt_s2:     .asciz "%s_2"
title_load: .asciz "LOAD BANKS?"
line_pg:    .asciz "FROM PAGE 2"
    .balign 4
lines_arr:  .long line_pg
    .balign 4
sib_name:   .space 288
