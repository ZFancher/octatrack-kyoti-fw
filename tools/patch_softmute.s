| SPDX-License-Identifier: MIT
| SPDX-FileCopyrightText: 2026 Zachary Fancher ("Kyoti")
| patch_softmute V7 -- audio-track mute (and, V7, SOLO silencing) behave like a single STOP:
| the sample audio cuts (fast clean fade), the track's FX inserts ring their delay/reverb
| tails out, and a silenced track's sequencer trigs make no sound.
|
| Session 9 (V1-V6): the mute case.  Session 11 (V7): the SOLO case, same technique.
|
| Session 12 (--defsym DT_MODE=1): a third MUTE MODE, "DT".  DT mute is a pure *sequencer*
| mute -- exactly like a Digitakt trig mute: the voice that is already sounding keeps playing
| under its OWN amp envelope (fades, sustains, or loops forever, whatever the AMP page says),
| its FX ring, and only NEW trigs are suppressed while the track is silenced.  Mechanism:
| the same D5-bit clearing as OT+FX (so FUN_40004db8 keeps every frame level word -> the
| voice + FX still reach the mix untouched) and the same `pre_v` new-trig drop, but WITHOUT
| the FUN_40008f84 note-off / DAT_8000184a hold that OT+FX uses to fade the dry signal.
| GATE (0x800000dc) == 2 selects it.  DT_MODE is compile-gated so a plain build is unchanged.
|
| Mechanism (confirmed on hardware, MKI): FUN_40004dbc (entry FUN_40004db8) is the per-frame
| DSP-frame builder.  It branches on the SOLO flag 0x80000037:
|   - not solo:  per track, if _DAT_80000008 bit 8+t (muted) -> `clr.w` the frame level word.
|   - solo:      per track, if _DAT_80000008 bit t (0..7) set (SOLOED) -> keep; else the level
|                words get AND-ed with 0 (d1, "any track soloed?") -> silenced.  A non-soloed
|                track that is ALSO muted -> `clr.l` instead.
| Either way the silencing is a post-FX cut that also kills the FX return.
|
| _DAT_80000008 layout:  bits 0..7 = per-track SOLO   bits 8..15 = MUTE   bits 16..23 = CUE.
|
| Two hooks, one cave:
|
|  1. `pre`   @ 0x40004dc6 (the displaced `move.l 0x80000008,D5`).  With MUTE MODE == OT+FX,
|     compute the "silenced" audio-track set for this frame:
|         not solo   -> silenced = mute mask (bits 8..15 -> 0..7)
|         solo + >=1 track soloed -> silenced = every non-soloed audio track
|         solo + none soloed      -> silenced = 0  (stock: nothing is cut yet)
|     Then:
|       - keep the frame level words for the silenced tracks by clearing the bits FUN_40004dbc
|         tests in D5 (not solo: clear the mute bits; solo: clear bits 0..15 so every track is
|         kept and the "any soloed?" AND-mask D1 becomes -1) -> FX inserts still reach the mix.
|       - every frame: DAT_8000184a |= silenced   (hold the note-off; the DSP runs the release)
|       - 0->1 edge vs the patch-RAM shadow: FUN_40008f84(t) once per newly-silenced track.
|     MUTE MODE == OT clears the shadow and bails -> byte-for-byte stock.
|
|  2. `pre_v` @ 0x40005178 (the voice-command queue).  Drops "start" commands (bit 0x80 set,
|     bit 0x10 clear) for a silenced audio track -> no 1-frame attack blip.
|
| _DAT_80000008 itself is never written, so the MUTE/SOLO LEDs and pattern-stored state work.
|
| Assemble:  m68k-elf-as -mcpu=5407 [--defsym ALWAYS_ON=1] ; ld -Ttext=<at> ; objcopy -O binary

    .equ GATE,        0x800000dc     | MUTE MODE word (0 = OT/stock, 1 = OT+FX).  Ignored when ALWAYS_ON.
    .equ MUTE_STATE,  0x80000008     | bits 0..7 SOLO   bits 8..15 MUTE   bits 16..23 CUE
    .equ SOLO_FLAG,   0x80000037     | byte, non-zero while SOLO mode is engaged
    .equ REL_STATE,   0x8000184a     | byte: voice t in RELEASE when bit t set
    .equ SHADOW,      0x80006c66     | patch RAM: last frame's "silenced" set (8 bits)
    .equ F_NOTEOFF,   0x40008f84     | FUN_40008f84(t) -- per-track note-off
    .equ BACK,        0x40004dcc     | FUN_40004dbc, after the displaced `move.l 0x80000008,D5`
    .equ BACK_V,      0x40005180     | FUN_40005178, after the displaced prologue

    .text

| ============================ hook 1: FUN_40004dbc =============================
    .global pre
pre:
    move.l  MUTE_STATE,%d5              | displaced: `move.l 0x80000008,D5`
    lea     (-0x10,%sp),%sp
    movem.l %d0-%d3,(%sp)               | 4 longs == the 0x10 reserved

    .ifndef ALWAYS_ON
    move.l  GATE,%d0
    cmpi.l  #1,%d0                      | MUTE MODE == OT+FX ?
    beq     p1_active
    .ifdef DT_MODE
    cmpi.l  #2,%d0                      | MUTE MODE == DT ?  (same D5 handling, no note-off)
    beq     p1_active
    .endif
    clr.b   SHADOW                      | OT (or unknown): stock; keep the shadow clean for later
    bra     p1_done
p1_active:
    .endif

| ---- silenced set -> D2 (bits 0..7) ----
    tst.b   SOLO_FLAG
    bne     p1_solo

    | not solo: silenced = mute mask
    move.l  %d5,%d2
    lsr.l   #8,%d2
    andi.l  #0xff,%d2
    | keep the muted tracks' frame level words: D5 &= ~(silenced << 8)
    move.l  %d2,%d0
    lsl.l   #8,%d0
    not.l   %d0
    and.l   %d0,%d5
    bra     p1_edge

p1_solo:
    | solo: soloed mask = D5 bits 0..7
    move.l  %d5,%d2
    andi.l  #0xff,%d2
    beq     p1_zero                     | solo engaged but nothing soloed -> nothing silenced
    eori.l  #0xff,%d2                   | silenced = ~soloed & 0xff  (the 8 audio tracks)
    | keep EVERY track's frame level words: clear D5 bits 0..15
    |  -> every track: solo bit clear + mute bit clear -> the "& D1" keep path
    |  -> D1 = (D5.b == 0) ? -1 : 0  becomes -1 -> words pass through unchanged
    andi.l  #0xffff0000,%d5
    bra     p1_edge

p1_zero:
    moveq   #0,%d2

p1_edge:
    .ifdef DT_MODE
| ---- DT: the D5 mute/solo bits are already cleared above (voice + FX keep flowing to the
|      mix untouched); the voice rides its own amp envelope.  No note-off, no REL_STATE. ----
    move.l  GATE,%d0
    cmpi.l  #2,%d0
    bne     p1_edge_ot
    clr.b   SHADOW                      | so a live DT -> OT+FX switch re-asserts every note-off
    bra     p1_done
p1_edge_ot:
    .endif
| ---- shadow edge (always update the shadow) ----
    moveq   #0,%d1
    move.b  SHADOW,%d1
    move.b  %d2,SHADOW
    not.l   %d1
    and.l   %d2,%d1                     | D1 = newly-silenced (0->1 edge)

| ---- maintain REL_STATE |= silenced ----
    tst.l   %d2
    beq     p1_done
    moveq   #0,%d0
    move.b  REL_STATE,%d0
    or.l    %d2,%d0
    move.b  %d0,REL_STATE

| ---- note-off the newly-silenced tracks, once ----
    tst.l   %d1
    beq     p1_done
    moveq   #0,%d3
p1_loop:
    btst    %d3,%d1
    beq     p1_next
    move.l  %d3,-(%sp)
    jsr     F_NOTEOFF
    addq.l  #4,%sp
p1_next:
    addq.l  #1,%d3
    moveq   #8,%d0
    cmp.l   %d3,%d0
    bne     p1_loop

p1_done:
    movem.l (%sp),%d0-%d3
    lea     (0x10,%sp),%sp
    jmp     BACK

| ============================ hook 2: FUN_40005178 ============================
| Entry (jmp detour, nothing pushed): (0,SP)=ret, (4,SP)=track, (8,SP)=cmd, (0xc,SP)=flag.
| Displaced: `lea (-0xc,SP),SP` + `movem.l {D2,D3,D4},(SP)` (8 B), then resume at 0x40005180.
    .global pre_v
pre_v:
    lea     (-0x10,%sp),%sp             | ColdFire: movem needs (An), not -(An)
    movem.l %d0-%d2,(%sp)               | caller args now at (0x14/0x18/0x1c, sp)

    .ifndef ALWAYS_ON
    move.l  GATE,%d0
    .ifdef DT_MODE
    subq.l  #1,%d0                      | mode 1 -> 0, mode 2 -> 1
    cmpi.l  #1,%d0
    bhi     v_stock                     | MUTE MODE not in { OT+FX, DT }
    .else
    cmpi.l  #1,%d0
    bne     v_stock
    .endif
    .endif

    move.l  (0x14,%sp),%d0              | d0 = track
    move.l  (0x18,%sp),%d1              | d1 = cmd
    cmpi.l  #8,%d0
    bcc     v_stock                     | track >= 8 -> not an audio track
    btst    #7,%d1
    beq     v_stock                     | not a "start" (bit 0x80 clear)
    btst    #4,%d1
    bne     v_stock                     | has the stop bit (0x10) -> let it through

| ---- drop iff this track is "silenced" ----
    move.l  MUTE_STATE,%d1
    move.l  %d0,%d2
    addi.l  #8,%d2
    btst    %d2,%d1                     | muted (bit 8+t) ?
    bne     v_drop
    tst.b   SOLO_FLAG
    beq     v_stock                     | not solo, not muted -> let it through
    move.l  %d1,%d2
    andi.l  #0xff,%d2
    beq     v_stock                     | solo engaged, nothing soloed -> let it through
    btst    %d0,%d1                     | this track soloed (bit t) ?
    bne     v_stock                     | soloed -> let it through
| fallthrough: solo active + this track not soloed -> drop
v_drop:
    movem.l (%sp),%d0-%d2
    lea     (0x10,%sp),%sp
    moveq   #1,%d0
    rts

v_stock:
    movem.l (%sp),%d0-%d2
    lea     (0x10,%sp),%sp
    | ---- displaced prologue of FUN_40005178 ----
    lea     (-0xc,%sp),%sp
    movem.l %d2-%d4,(%sp)
    jmp     BACK_V
