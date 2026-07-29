    .cpu 5407
    .text
| =====================================================================
|  BANK PAGING (release) — PAGE key cycles sibling-project bank pages.
|
|  In the SELECT BANK screen, [PAGE] cycles page 1(base)->2->3->4->1 and
|  pops "LOAD BANKS?" with the target project name. YES loads that page's
|  15 non-playing banks (redirected to "<base>" / "<base>_N", audio-safe),
|  then re-enters SELECT BANK; NO aborts. Outside SELECT BANK, [PAGE] is
|  stock. A normal RELOAD BANK still re-syncs (done hook is conditional).
|
|  Naming: base project = "<name>" (loaded); pages 2..4 = "<name>_2/_3/_4".
|  Page 1 reloads the base itself. Hardware-proven mechanism (S1/S3a);
|  the cycling + dynamic name are the additions here.
|
|  Not yet in this build: existence-aware skip of missing pages (a missing
|  page's load just errors via the stock dialog), the page LED, and the
|  16th-bank catch-up on bank change (see DESIGN_BANKPAGE.md S4).
| =====================================================================

| ---- PAGE hook: FUN_4004ffc4 @entry (keycode 4(SP), edge 8(SP)) ----
page_cave:
    move.l  8(%sp), %d0            | edge
    subq.l  #1, %d0
    bne.w   pc_stock               | not a press -> stock PAGE
    move.l  0x460d1e5c, %d0        | select-window handle
    beq.w   pc_stock               | none open -> stock
    move.l  0x460d1e60, %d0        | select callback
    cmp.l   #0x4007b408, %d0       | == BANK select?
    bne.w   pc_stock
    move.l  0x460e5cd0, %d0        | popup already open?
    bne.w   pc_swallow
    | advance page: (page & 3) + 1  -> 1,2,3,4,1,...
    move.l  g_page, %d0
    moveq   #3, %d1
    and.l   %d1, %d0
    addq.l  #1, %d0
    move.l  %d0, g_page
    | build target project name into sib_name
    cmp.l   #1, %d0
    beq.b   pc_base
    move.l  %d0, -(%sp)            | page number
    pea     0x100f8378            | base project name
    pea     fmt_sd                | "%s_%d"
    pea     sib_name
    jsr     0x40013a08            | sprintf(sib_name,"%s_%d",name,page)
    lea     16(%sp), %sp
    bra.b   pc_popup
pc_base:
    pea     0x100f8378
    pea     fmt_s                 | "%s"
    pea     sib_name
    jsr     0x40013a08            | sprintf(sib_name,"%s",name)  (page 1 = base)
    lea     12(%sp), %sp
pc_popup:
    pea     confirm_handler
    move.l  #3, -(%sp)
    pea     lines_arr             | { &sib_name }  -> shows the target name
    move.l  #1, -(%sp)
    pea     title_load
    jsr     0x4006d57c
    lea     0x14(%sp), %sp
pc_swallow:
    rts
pc_stock:
    lea     -0x10(%sp), %sp        | replicate displaced entry (lea + movem)
    movem.l %d2-%d4/%a2, (%sp)
    jmp     0x4004ffcc

| ---- confirm handler: p at 4(SP); p==0 YES, p!=0 NO ----
confirm_handler:
    move.l  4(%sp), %d0
    bne.b   ch_done
    lea     sib_name, %a0
    move.l  %a0, g_redirect        | arm redirect to the target page
    mvz.b   0x100b14ce, %d1        | playing bank
    moveq   #1, %d0
    lsl.l   %d1, %d0
    not.l   %d0                    | mask = all banks except the playing one
    move.l  %d0, -(%sp)
    jsr     0x40022778             | post the masked reload job
    addq.l  #4, %sp
    move.l  #1, -(%sp)             | re-enter SELECT BANK (as [BANK]=0x2f press)
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
    beq.b   dc_resync              | normal reload -> stock re-sync
    clr.l   g_redirect             | paging load -> disarm + SKIP re-sync
    move.l  #1, -(%sp)             | replicate displaced `pea (0x1).w`
    jmp     0x400239aa
dc_resync:
    jsr     0x400238a4
    move.l  #1, -(%sp)
    jmp     0x400239aa

| ---- data ----
    .balign 4
g_page:     .long 1                | 1 = base; 2..4 = _2/_3/_4
g_redirect: .long 0
fmt_sd:     .asciz "%s_%d"
fmt_s:      .asciz "%s"
title_load: .asciz "LOAD BANKS?"
    .balign 4
lines_arr:  .long sib_name
    .balign 4
sib_name:   .space 288
