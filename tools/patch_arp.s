    .cpu 5407
    .text
| =====================================================================
|  ARP KEY-SCALE EXTENSION  (educational)
|  Adds Greek modes + blues + phrygian-dominant + melodic-minor +
|  octatonic + hirajoshi to the arpeggiator's F-knob "key scale".
|
|  Encoding: value 0 = OFF; 1..144 = root(0..11) * 12 + quality(0..11).
|    root    = (v-1) / 12          quality = (v-1) % 12
|  Quality order (index 0..11): MAJ MIN DOR PHR LYD MIX LOC BLU PHD MEL OCT HIR
|
|  Runtime quantizer FUN_4009f794 keeps its own `local_44` channel; we
|  set local_44 = 12*quality + (12-root) so:
|    * the stock mod-12 site yields idx = (note-root) mod 12  (12*q vanishes)
|    * quality is recovered at the lookup as (local_44-1)/12   (exact for 1..144)
|  so NO extra stack slot / frame change is needed.
|  Snap deltas live in one unified table UT[quality*12 + idx] (signed bytes).
| =====================================================================

| ---- DECODE: replaces the 46-byte block at 0x4009fad2 (jmp'd in) ----
| in : A0 = per-track struct base (scale byte at 0x31(A0)); A6 = frame
| out: local_44 (-0x40,A6) = 12*quality + (12-root), or 0 for OFF
| clobbers D0-D3 (reloaded by the original at 0x4009fb00); preserves D7,A0
decode_cave:
    clr.l %d0
    move.b 0x31(%a0),%d0        | scale value 0..255
    beq.w dc_off                | 0 -> OFF
    cmpi.l #144,%d0
    bhi.w dc_off                | >144 (never, defensive) -> OFF
    subq.l #1,%d0               | v1 = scale-1  (0..143)
    move.l #171,%d1
    mulu.l %d0,%d1              | v1 * 171
    lsr.l #8,%d1
    lsr.l #3,%d1               | D1 = root = v1/12  (root is the slow/outer field)
    move.l %d1,%d2
    add.l %d2,%d2
    add.l %d1,%d2              | 3*root
    lsl.l #2,%d2              | D2 = 12*root
    sub.l %d2,%d0            | D0 = v1 - 12*root = quality  (inner/fast field)
    | local_44 = 12*quality + (12 - root)  [D0=quality, D1=root]
    move.l %d0,%d2
    add.l %d2,%d2
    add.l %d0,%d2             | 3*quality
    lsl.l #2,%d2            | D2 = 12*quality
    moveq #12,%d3
    sub.l %d1,%d3          | 12 - root
    add.l %d2,%d3         | 12*quality + (12-root)
    move.l %d3,-0x40(%a6)
    jmp 0x4009fb00
dc_off:
    clr.l %d0
    move.l %d0,-0x40(%a6)
    jmp 0x4009fb00

| ---- LOOKUP: replaces `lea 0x400d80a0,A0` at 0x4009fb74 (jmp'd in) ----
| in : D1 = idx (0..11), D0 = note, A2 = note buffer, A6 = frame
| does: D1 += quality*12 ; D0 = note + UT[D1] ; store (A2) ; -> 0x4009fb80
| preserves D2,D3 (live in the caller's per-note loop)
lookup_cave:
    move.l %d2,-(%sp)
    move.l %d3,-(%sp)
    move.l -0x40(%a6),%d2       | local_44 = 12*quality + (12-root)
    subq.l #1,%d2               | (0..143)
    move.l #171,%d3
    mulu.l %d2,%d3              | *171
    lsr.l #8,%d3
    lsr.l #3,%d3               | D3 = quality = (local_44-1)/12
    move.l %d3,%d2
    add.l %d2,%d2
    add.l %d3,%d2              | 3*quality
    lsl.l #2,%d2             | 12*quality
    add.l %d2,%d1           | D1 = quality*12 + idx
    lea UT,%a0
    adda.l %d1,%a0
    mvs.b (%a0),%d1        | sign-extend snap delta
    add.l %d1,%d0         | note + delta
    move.b %d0,(%a2)      | store snapped note
    move.l (%sp)+,%d3
    move.l (%sp)+,%d2
    jmp 0x4009fb80

| ---- FORMATTER: replaces FUN_4003b790 (jmp'd in at its entry) ----
| callback contract: arg1 pos = 8(A6), arg2 v = 0xc(A6).
| draws suffix at pos via 0x40013a08(pos,str), then tail-draws root at pos+5.
fmt_cave:
    link.w %a6,#-8
    movem.l %d2-%d3,(%sp)
    move.l 0xc(%a6),%d2         | v
    move.l 0x8(%a6),%d3         | pos
    tst.l %d2
    beq.w fc_off
    cmpi.l #144,%d2
    bhi.w fc_off
    subq.l #1,%d2              | v1
    move.l #171,%d0
    mulu.l %d2,%d0             | v1*171
    lsr.l #8,%d0
    lsr.l #3,%d0             | D0 = root = v1/12
    move.l %d0,%d1
    add.l %d1,%d1
    add.l %d0,%d1            | 3*root
    lsl.l #2,%d1           | 12*root
    sub.l %d1,%d2         | D2 = quality = v1%12
    lea NOTETAB,%a0
    lsl.l #2,%d0
    adda.l %d0,%a0
    move.l (%a0),%d1       | D1 = root-name ptr  (NOTETAB[root])
    lea QUALTAB,%a0
    lsl.l #2,%d2
    adda.l %d2,%a0
    move.l (%a0),%d0       | D0 = suffix ptr     (QUALTAB[quality])
    bra.w fc_draw
fc_off:
    move.l #0x400b44b5,%d0     | EMPTY suffix
    move.l #0x400b4e78,%d1     | "OFF" root
fc_draw:
    move.l %d1,0xc(%a6)        | root ptr -> arg2 (survives the call, used by tail)
    move.l %d0,-(%sp)          | push suffix
    move.l %d3,-(%sp)          | push pos
    jsr 0x40013a08
    addq.l #8,%sp
    addq.l #5,%d3
    move.l %d3,0x8(%a6)        | pos+5 -> arg1
    movem.l (%sp),%d2-%d3
    unlk %a6
    jmp 0x40013a08            | tail: draw root at pos+5

| ============================ DATA ============================
    .balign 4
| Unified snap table: UT[quality*12 + pitchclass], signed byte delta.
| MAJ/MIN reproduce the stock table 0x400d80a0 exactly (verified).
UT:
    .byte 0,255,0,255,0,0,255,0,255,0,255,0      | MAJ  {0,2,4,5,7,9,11}
    .byte 0,255,0,0,255,0,255,0,0,255,0,255      | MIN  {0,2,3,5,7,8,10}
    .byte 0,255,0,0,255,0,255,0,255,0,0,255      | DOR  {0,2,3,5,7,9,10}
    .byte 0,0,255,0,255,0,255,0,0,255,0,255      | PHR  {0,1,3,5,7,8,10}
    .byte 0,255,0,255,0,255,0,0,255,0,255,0      | LYD  {0,2,4,6,7,9,11}
    .byte 0,255,0,255,0,0,255,0,255,0,0,255      | MIX  {0,2,4,5,7,9,10}
    .byte 0,0,255,0,255,0,0,255,0,255,0,255      | LOC  {0,1,3,5,6,8,10}
    .byte 0,255,1,0,255,0,0,0,255,1,0,255        | BLU  {0,3,5,6,7,10}   (nearest)
    .byte 0,0,255,254,0,0,255,0,0,255,0,255      | PHD  {0,1,4,5,7,8,10}
    .byte 0,255,0,0,255,0,255,0,255,0,255,0      | MEL  {0,2,3,5,7,9,11}
    .byte 0,255,0,0,255,0,0,255,0,0,255,0        | OCT  {0,2,3,5,6,8,9,11}(nearest)
    .byte 0,255,0,0,255,254,1,0,0,255,254,1      | HIR  {0,2,3,7,8}      (nearest)

    .balign 4
| Root-note name pointers, indexed by root pitch-class 0..11 (reuse stock strings).
NOTETAB:
    .long 0x400b576f, 0x400b5741, 0x400b421a, 0x400b5744   | C  C# D  D#
    .long 0x400b5963, 0x400b672c, 0x400b5747, 0x400b5eae   | E  F  F# G
    .long 0x400b574a, 0x400b5fd4, 0x400b574d, 0x400b5ef5   | G# A  A# B

| Quality suffix pointers, indexed by quality 0..11.
QUALTAB:
    .long 0x400b5750, 0x400b4419                           | MAJ  MIN (stock)
    .long s_dor, s_phr, s_lyd, s_mix, s_loc, s_blu
    .long s_phd, s_mel, s_oct, s_hir

s_dor: .asciz "DOR"
s_phr: .asciz "PHR"
s_lyd: .asciz "LYD"
s_mix: .asciz "MIX"
s_loc: .asciz "LOC"
s_blu: .asciz "BLU"
s_phd: .asciz "PHD"
s_mel: .asciz "MEL"
s_oct: .asciz "OCT"
s_hir: .asciz "HIR"
