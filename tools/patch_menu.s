| patch_menu — two new PERSONALIZE entries, both OFF by default.
|
|   NO BANK/PTN TIMER   0x800000d4   checked -> SELECT windows never expire
|   LAZY TRANSITIONS    0x800000d8   checked -> lazy part apply + GUI-in-transition +
|                                    sticky scenes + both dirty indicators
|
| Unchecked (the power-on / EMPTY RESET state, since the words start cleared) means
| factory behaviour, so a user who never opens the menu sees a stock unit.
|
| The menu is three parallel arrays of 16 entries, contiguous and immediately followed by
| unrelated data, so they cannot grow in place:
|   labels  0x400b2a34   getters 0x400b2a74   setters 0x400b2ac0
| They are relocated to the cave with 18 entries each (built by the packer, since they mix
| stock addresses with the ones assembled here) and the five `lea` instructions that reach
| them are repointed: one for labels and one for getters in FUN_40068e00, three for
| setters in FUN_40068fd0.
|
| The item count comes from FUN_40068fa8: `moveq #15,%d1 ; subl %d0,%d1` where d0 is 0 or
| -1, giving 15 or 16. Changing the immediate to 17 yields 17 or 18 and preserves that
| conditionality — one byte.
|
| Glyphs: 0x400b5e90 = checked, 0x400b5e8e = unchecked (deduced from the stock getters,
| e.g. MUTE FOCUSES TRK at 0x400688d8 returns 0x400b5e90 when its flag is set).
|
| Setters take (delta, wrap) on the stack; the menu passes delta 1 for [YES] and ±1 for
| the arrows. `(flag + delta) & 1` turns all of those into a toggle, which is what a
| checkbox needs.

    .equ F_NOTIMER, 0x800000d4
    .equ F_LAZY,    0x800000d8
    .equ GLYPH_ON,  0x400b5e90
    .equ GLYPH_OFF, 0x400b5e8e

    .text
    .global _start
_start:

| ---------------- labels ----------------
    .global lbl_notimer, lbl_lazy
lbl_notimer:
    .asciz "NO BANK/PTN TIMER"
    .align 2
lbl_lazy:
    .asciz "LAZY TRANSITIONS"
    .align 2

| ---------------- getters ----------------
    .global get_notimer, get_lazy
get_notimer:
    move.l  #GLYPH_ON,%d0
    tst.l   F_NOTIMER
    bne.b   gn_ret
    move.l  #GLYPH_OFF,%d0
gn_ret:
    rts

get_lazy:
    move.l  #GLYPH_ON,%d0
    tst.l   F_LAZY
    bne.b   gl_ret
    move.l  #GLYPH_OFF,%d0
gl_ret:
    rts

| ---------------- setters ----------------
    .global set_notimer, set_lazy
set_notimer:
    move.l  F_NOTIMER,%d0
    add.l   4(%sp),%d0
    andi.l  #1,%d0
    move.l  %d0,F_NOTIMER
    rts

set_lazy:
    move.l  F_LAZY,%d0
    add.l   4(%sp),%d0
    andi.l  #1,%d0
    move.l  %d0,F_LAZY
    rts
