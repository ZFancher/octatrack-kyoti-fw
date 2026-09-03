| SPDX-License-Identifier: MIT
| SPDX-FileCopyrightText: 2026 Zachary Fancher ("Kyoti")
    .cpu 5407
    .text
| =====================================================================
|  SIDE-CHAIN COMPRESSOR  --  step 1 of 4: menu only (the DSP still
|  ignores every new parameter; this proves the ColdFire control-surface
|  side + the dynamic KEY formatter on real hardware).
|
|  Adds  KEY  to the COMPRESSOR effect page 2 (parameter-descriptor slot
|  7, right after RMS).  Value semantics:
|      0        = OFF  (stock behaviour: the compressor keys off its own
|                       track, unchanged)
|      1 .. 4   = one of the four audio tracks that share this track's
|                 DSP core -- rendered "T1".."T4" while the edited track
|                 (0x100b14cc) is 1..4, "T5".."T8" while it is 5..8, so
|                 the chooser can never point at a track the compressor
|                 is not wired to.
|
|  Everything except this formatter is a data poke done by
|  build_sidechain.py (name / value-count 2->5 / default 1->0 / the
|  A-array formatter pointer / zero the stale B-array widget pointer).
| =====================================================================

    .equ CUR_TRACK, 0x100b14cc      | u8, 0..7  -- current / edited audio track
    .equ SPRINTF,   0x40013a08      | int sprintf(char *buf, const char *fmt, ...)
    .equ S_OFF,     0x400b4e78      | stock "OFF" string literal

| ---- KEY formatter  --  A-array callback: void fmt(char *buf, int value) ----
|  No link frame (matches the stock per-slot formatters, e.g. FUN_4003c14c
|  ON/OFF and FUN_4003c718 "%d").  Stack on entry:
|      0(%sp) = return addr   4(%sp) = buf   8(%sp) = value
|  Convention: %d0/%d1/%a0/%a1 are scratch; %d2+ must be preserved
|  (FUN_4003c7a0 saves %d2), so this routine touches only %d0/%d1/%a1.
    .global key_fmt
key_fmt:
    move.l  4(%sp),%a1             | a1 = buf
    move.l  8(%sp),%d0             | d0 = value (0..4)
    bne.b   kf_track

|  value 0 -> "OFF": rewrite the two stack args in place and tail-jump to
|  sprintf, exactly as FUN_4003c14c does.
    move.l  #S_OFF,%d1
    move.l  %d1,8(%sp)             | arg2 := "OFF"
    move.l  %a1,4(%sp)            | arg1 := buf  (unchanged)
    jmp     SPRINTF                | tail: sprintf(buf, "OFF")

|  value 1..4 -> "T<n>", n = coreBase + value - 1
|      coreBase = 1  when CUR_TRACK in 0..3
|      coreBase = 5  when CUR_TRACK in 4..7
kf_track:
    clr.l   %d1
    move.b  CUR_TRACK,%d1           | d1 = current track 0..7
    cmpi.l  #4,%d1
    bcc.b   kf_hi                   | unsigned >= 4  -> high core (T5..T8)
    moveq   #1,%d1
    bra.b   kf_num
kf_hi:
    moveq   #5,%d1
kf_num:
    add.l   %d0,%d1                 | d1 = coreBase + value
    subq.l  #1,%d1                  | d1 = track number 1..8
    move.l  %d1,-(%sp)             | sprintf arg: n
    pea     kf_fmt                  | sprintf arg: "T%d"
    move.l  %a1,-(%sp)            | sprintf arg: buf
    jsr     SPRINTF
    lea     12(%sp),%sp
    rts

    .balign 2
kf_fmt:
    .asciz  "T%d"

| =====================================================================
|  KEY FLT formatter  --  step 3 scaffolding (DSP filter not built yet).
|  Bipolar key-filter select on COMPRESSOR page 2:
|      < 64  ->  "LP"   (low-pass -- isolate a kick from a full loop)
|      = 64  ->  "OFF"
|      > 64  ->  "HP"   (classic detector high-pass)
|  Type only for now; the cutoff number is added once the DSP filter's
|  value->Hz mapping is fixed.  A-array callback: void fmt(buf, value).
| =====================================================================
    .balign 2
    .global kfilt_fmt
kfilt_fmt:
    move.l  4(%sp),%a1             | a1 = buf
    move.l  8(%sp),%d0             | d0 = value 0..127
    cmpi.l  #64,%d0
    blt.b   kfl_lp
    beq.b   kfl_off
    move.l  #kfl_hp_s,%d1
    bra.b   kfl_go
kfl_lp:
    move.l  #kfl_lp_s,%d1
    bra.b   kfl_go
kfl_off:
    move.l  #S_OFF,%d1
kfl_go:
    move.l  %d1,8(%sp)            | arg2 := "LP" / "HP" / "OFF"
    move.l  %a1,4(%sp)           | arg1 := buf
    jmp     SPRINTF

    .balign 2
kfl_lp_s:
    .asciz  "LP"
kfl_hp_s:
    .asciz  "HP"
