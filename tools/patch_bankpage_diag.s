    .cpu 5407
    .text
| =====================================================================
|  DIAGNOSTIC build: why does the sibling existence check fail?
|  On [PAGE] in SELECT BANK, pop a popup showing, for page "_2":
|    title: "EX=<n>"          <- FUN_40025650(path) result (1=valid project)
|    line1: "<name>_2"        <- the name built via sprintf("%s_2")
|    line2: "<full path>"     <- FUN_40025230(0,name) output (0x460bf112)
|  So we can see if the name, the path, or the predicate is wrong.
|  (Reuses the working redirect gate + done + confirm from patch_bankpage.s.)
| =====================================================================

page_cave:
    move.l  8(%sp), %d0            | edge
    subq.l  #1, %d0
    bne.w   pc_stock
    move.l  0x460d1e5c, %d0        | in SELECT BANK?
    beq.w   pc_stock
    move.l  0x460d1e60, %d0
    cmp.l   #0x4007b408, %d0
    bne.w   pc_stock
    move.l  0x460e5cd0, %d0        | popup already open?
    bne.w   pc_swallow

    | build sibling dir "<name>_2" -> 0x460bf112 (stays valid; nothing below clobbers it)
    pea     0x100f8378
    pea     fmt_sd                | "%s_2"
    pea     sib_name
    jsr     0x40013a08
    lea     12(%sp), %sp
    pea     sib_name
    clr.l   -(%sp)
    jsr     0x40025230            | -> D0 = sibling dir (0x460bf112)
    addq.l  #8, %sp

    | --- W: try to OPEN "<sib>/bank01.work" ---
    move.l  %d0, -(%sp)
    pea     fmt_work
    pea     path_cur
    jsr     0x40013a08            | path_cur = "<sib>/bank01.work"
    lea     12(%sp), %sp
    move.l  #0x10000, -(%sp)
    pea     0x460a8f60
    pea     0x400b3289            | "r"
    pea     path_cur
    pea     fh
    jsr     0x40016864
    lea     0x14(%sp), %sp
    move.l  %d0, d_cur
    tst.l   %d0
    bmi.b   1f
    pea     fh
    jsr     0x4001677c
    addq.l  #4, %sp
1:
    | --- S: try to OPEN "<sib>/bank01.strd" (0x460bf112 still = sibling dir) ---
    move.l  #0x460bf112, -(%sp)
    pea     fmt_bank
    pea     path_buf
    jsr     0x40013a08            | path_buf = "<sib>/bank01.strd"
    lea     12(%sp), %sp
    move.l  #0x10000, -(%sp)
    pea     0x460a8f60
    pea     0x400b3289
    pea     path_buf
    pea     fh
    jsr     0x40016864
    lea     0x14(%sp), %sp
    move.l  %d0, d_sib
    tst.l   %d0
    bmi.b   2f
    pea     fh
    jsr     0x4001677c
    addq.l  #4, %sp
2:
    | title = sprintf("work=%d strd=%d", W, S)
    move.l  d_sib, -(%sp)
    move.l  d_cur, -(%sp)
    pea     fmt_cs
    pea     title_buf
    jsr     0x40013a08
    lea     16(%sp), %sp
    pea     confirm_handler
    move.l  #3, -(%sp)
    pea     lines_arr
    move.l  #2, -(%sp)
    pea     title_buf
    jsr     0x4006d57c
    lea     0x14(%sp), %sp
pc_swallow:
    rts
pc_stock:
    lea     -0x10(%sp), %sp
    movem.l %d2-%d4/%a2, (%sp)
    jmp     0x4004ffcc

| ---- confirm handler: YES loads "<name>_2"; NO aborts ----
confirm_handler:
    move.l  4(%sp), %d0
    bne.b   ch_done
    lea     sib_name, %a0
    move.l  %a0, g_redirect
    mvz.b   0x100b14ce, %d1
    moveq   #1, %d0
    lsl.l   %d1, %d0
    not.l   %d0
    move.l  %d0, -(%sp)
    jsr     0x40022778
    addq.l  #4, %sp
    move.l  #1, -(%sp)
    move.l  #0x2f, -(%sp)
    jsr     0x4007af80
    addq.l  #8, %sp
ch_done:
    rts

gate_cave:
    move.l  g_redirect, %a0
    tst.l   %a0
    bne.b   gc_use
    lea     0x100f8378, %a0
gc_use:
    jmp     0x4002524a

done_cave:
    move.l  g_redirect, %d0
    beq.b   dc_resync
    clr.l   g_redirect
    move.l  #1, -(%sp)
    jmp     0x400239aa
dc_resync:
    jsr     0x400238a4
    move.l  #1, -(%sp)
    jmp     0x400239aa

    .balign 4
g_redirect: .long 0
d_cur:      .long 0
d_sib:      .long 0
fmt_sd:     .asciz "%s_2"
fmt_s:      .asciz "%s"
fmt_cs:     .asciz "wrk=%d std=%d"
fmt_work:   .asciz "%s/bank01.work"
fmt_bank:   .asciz "%s/bank01.strd"
    .balign 4
fh:         .space 32
    .balign 4
lines_arr:  .long path_cur
            .long path_buf
    .balign 4
title_buf:  .space 32
    .balign 4
path_cur:   .space 288
    .balign 4
path_buf:   .space 288
    .balign 4
sib_name:   .space 288
