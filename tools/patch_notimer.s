| patch_menu — the two PERSONALIZE entries and the BANK/PTN countdown gate.
|
|   NO BANK/PTN TIMER  0x800000d4   SELECT windows stop expiring
|   LAZY TRANSITIONS   0x800000d8   lazy part apply + dimmed track LED + sticky scenes
|
| Both cleared by default, so an unconfigured unit behaves exactly like stock.
|
| Flag: 0x800000d4, one of the free words inside the battery-backed PERSONALIZE settings
| block (0x8000008c..0x800000d0). Cleared = factory 4-second countdown, so an unconfigured
| unit behaves exactly like stock. Checked = the SELECT windows stay open until you pick a
| trig or press the same key again (that press-again-to-exit toggle already exists in stock
| firmware; only the timeout stood in the way).
|
| Why killing the tick is safe: the whole timer belongs to bank/pattern selection alone.
| FUN_40059f8c (create timed window) has exactly three callers -- SELECT PATTERN, SELECT
| BANK and "BANK %c: SELECT PTN" -- and the tick FUN_40056ab8 has one. Closing on a trig
| press goes through FUN_40056b00 from FUN_4007b2fc, independent of the countdown. With the
| tick dead the four countdown boxes stay full and read as "selection mode is active".
|
| Menu: three parallel 16-entry arrays (labels 0x400b2a34, getters 0x400b2a74, setters
| 0x400b2ac0), contiguous and immediately followed by unrelated data, so they are relocated
| to the cave with 17 entries by the packer. Item count comes from `moveq #15` in
| FUN_40068fa8 (result 15 or 16 depending on 0x46c8d18c); raising it to 16 gives 16 or 17
| and preserves that conditionality.
|
| Glyphs: 0x400b5e90 checked, 0x400b5e8e unchecked (deduced from the stock getters).
| The setter is (flag + delta) & 1, which turns both [YES] and the arrow keys into a toggle.

    .equ F_NOTIMER, 0x800000d4
    .equ F_LAZY,    0x800000d8

    .equ CD_ENABLE, 0x460d1e4c      | the countdown's own enable field
    .equ CD_RESUME, 0x40056abe      | the beq that follows the displaced tst

    .equ GLYPH_ON,  0x400b5e90
    .equ GLYPH_OFF, 0x400b5e8e

    .text
    .global _start
_start:

| ---- countdown gate ----
| Host FUN_40056ab8, whose first instruction is
|   4ab9 460d1e4c   tst.l (0x460d1e4c).l      -- 6 bytes, exactly a jmp
| followed at 0x40056abe by `beq.b`, which consumes the flags that tst sets. The stub
| re-executes the displaced tst so the Z flag is correct on the factory path.
    .global cd_stub
cd_stub:
    tst.l   F_NOTIMER
    bne.b   cd_off                  | checked -> no countdown at all
    tst.l   CD_ENABLE               | displaced instruction, restores Z
    jmp     CD_RESUME               | factory path, back into the tick
cd_off:
    rts

| ---- PERSONALIZE entry ----
    .global lbl_notimer, get_notimer, set_notimer
lbl_notimer:
    .asciz "NO BANK/PTN TIMER"
    .align 2

get_notimer:
    move.l  #GLYPH_ON,%d0
    tst.l   F_NOTIMER
    bne.b   gn_ret
    move.l  #GLYPH_OFF,%d0
gn_ret:
    rts

set_notimer:
    move.l  F_NOTIMER,%d0
    add.l   4(%sp),%d0
    andi.l  #1,%d0
    move.l  %d0,F_NOTIMER
    rts

    .global lbl_lazy, get_lazy, set_lazy
lbl_lazy:
    .asciz "LAZY TRANSITIONS"
    .align 2

get_lazy:
    move.l  #GLYPH_ON,%d0
    tst.l   F_LAZY
    bne.b   gl_ret
    move.l  #GLYPH_OFF,%d0
gl_ret:
    rts

set_lazy:
    move.l  F_LAZY,%d0
    add.l   4(%sp),%d0
    andi.l  #1,%d0
    move.l  %d0,F_LAZY
    rts
