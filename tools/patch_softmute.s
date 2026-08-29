| patch_softmute V6 -- audio-track mute behaves like a single STOP: the sample audio cuts
| (fast clean fade), the track's FX inserts ring their delay/reverb tails out, and a muted
| track's sequencer trigs make no sound.
|
| Session 9.  Mechanism (confirmed on hardware): FUN_40004dbc is the per-frame gate -- it
| reads _DAT_80000008 (bit 8+t = track t muted) and writes `clr.w` into that track's level
| words in the DSP frame double-buffer (a post-FX cut that also kills the FX return).
|
| Two hooks, one cave:
|
|  1. `pre`   @ FUN_40004dbc 0x40004dc6 (the `move.l 0x80000008,D5`).  Unless SOLO is active,
|     for every muted track:
|       - 0->1 edge (vs the shadow in patch RAM): FUN_40008f84(t) once  (per-track note-off)
|       - every frame: DAT_8000184a |= muted   (maintain the note-off, DSP holds the release)
|       - D5 &= ~(muted<<8)   -> FUN_40004dbc keeps the frame level words -> FX inserts still
|         reach the mix -> tails ring
|
|  2. `pre_v` @ FUN_40005178 0x40005178 (the voice-command queue).  Drops "start" commands
|     (bit 0x80 set, bit 0x10 clear) for a muted audio track -> a muted track's trigs never
|     start a voice, so there is no 1-frame attack blip.
|
| _DAT_80000008 itself is untouched, so the MUTE LED and pattern-stored mute state still work.
|
| Assemble:  m68k-elf-as -mcpu=5407 [--defsym ALWAYS_ON=1] ; ld -Ttext=<at> ; objcopy -O binary

    .equ GATE,        0x800000dc     | SOFT MUTE PERSONALIZE flag (0 = stock).  Ignored when ALWAYS_ON.
    .equ MUTE_STATE,  0x80000008     | bit 8+t: track t muted   bit 16+t: cued
    .equ SOLO_FLAG,   0x80000037     | non-zero while SOLO is engaged
    .equ REL_STATE,   0x8000184a     | byte: voice t in RELEASE when bit t set
    .equ SHADOW,      0x80006c66     | patch RAM: last-seen muted mask (8 bits)
    .equ F_NOTEOFF,   0x40008f84     | FUN_40008f84(t) -- per-track note-off
    .equ BACK,        0x40004dcc     | FUN_40004dbc, after the displaced `move.l 0x80000008,D5`
    .equ BACK_V,      0x40005180     | FUN_40005178, after the displaced prologue

    .text

| ============================ hook 1: FUN_40004dbc =============================
    .global pre
pre:
    move.l  MUTE_STATE,%d5              | displaced: `move.l 0x80000008,D5`
    lea     (-0x10,%sp),%sp
    movem.l %d0-%d3/%a0,(%sp)

    .ifndef ALWAYS_ON
    move.b  GATE,%d0
    beq     p1_done
    .endif

    tst.b   SOLO_FLAG
    bne     p1_done                    | SOLO -> stock hard cut

    move.l  %d5,%d0
    lsr.l   #8,%d0
    andi.l  #0xff,%d0                  | d0 = currently-muted mask (bits 0..7)

    moveq   #0,%d1
    move.b  SHADOW,%d1
    move.b  %d0,SHADOW
    move.l  %d1,%d2
    not.l   %d2
    and.l   %d0,%d2                    | d2 = newly-muted (0->1 edge)

    tst.l   %d0
    beq     p1_done

    moveq   #0,%d1
    move.b  REL_STATE,%d1
    or.l    %d0,%d1
    move.b  %d1,REL_STATE              | DAT_8000184a |= muted

    move.l  %d0,%d1
    lsl.l   #8,%d1
    not.l   %d1
    and.l   %d1,%d5                    | keep the frame level words for muted tracks

    tst.l   %d2
    beq     p1_done
    moveq   #0,%d3
p1_loop:
    btst    %d3,%d2
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
    movem.l (%sp),%d0-%d3/%a0
    lea     (0x10,%sp),%sp
    jmp     BACK

| ============================ hook 2: FUN_40005178 ============================
| Entry (jmp detour, nothing pushed): (0,SP)=ret, (4,SP)=track, (8,SP)=cmd, (0xc,SP)=flag.
| Displaced: `lea (-0xc,SP),SP` + `movem.l {D2,D3,D4},(SP)` (8 B), then resume at 0x40005180.
    .global pre_v
pre_v:
    lea     (-0x10,%sp),%sp            | ColdFire: movem needs (An), not -(An)
    movem.l %d0-%d2,(%sp)              | caller args now at (0x14/0x18/0x1c, sp)

    .ifndef ALWAYS_ON
    move.b  GATE,%d0
    beq     v_stock
    .endif

    move.l  (0x14,%sp),%d0             | d0 = track
    move.l  (0x18,%sp),%d1             | d1 = cmd
    cmpi.l  #8,%d0
    bcc     v_stock                    | track >= 8 -> not an audio track
    btst    #7,%d1
    beq     v_stock                    | not a "start" (bit 0x80 clear)
    btst    #4,%d1
    bne     v_stock                    | has the stop bit (0x10) -> let it through
    move.l  %d0,%d2
    addi.l  #8,%d2
    move.l  MUTE_STATE,%d1
    btst    %d2,%d1
    beq     v_stock                    | this track not muted
    | ---- drop the start: return to the caller with D0 = 1 ----
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
