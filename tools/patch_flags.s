| Feature flags — make every behaviour patch OPTIONAL, off by default.
|
| The unit must behave exactly like stock firmware for a user who never opens the
| PERSONALIZE menu. Two settings words in the battery-backed block turn the patches on:
|
|   0x800000d4  NO BANK/PTN TIMER   0 = factory 4-second countdown   1 = window stays open
|   0x800000d8  LAZY TRANSITIONS    0 = factory                      1 = lazy part apply,
|                                   GUI-in-transition, sticky scenes and both dirty
|                                   indicators, all together (one switch, by request)
|
| Both words are unreferenced in stock firmware and sit inside the settings block at
| 0x8000008c..0x800000d0, which survives power cycles (the Startup Menu's EMPTY RESET is
| documented as clearing "settings"), so they persist without touching the project file.
| A cleared word therefore means factory behaviour, which is also the state after an
| EMPTY RESET or on a fresh unit.
|
| ---------------------------------------------------------------------------
| cd_stub — gate for the BANK/PTN countdown.
|
| Replaces the plain `rts` used while the feature was unconditional. The host is
| FUN_40056ab8, the countdown tick, whose first instruction is
|   40056ab8  4ab9 460d1e4c   tst.l (0x460d1e4c).l     (6 bytes -- exactly a jmp)
| followed at 0x40056abe by `beq.b 0x40056afc`, which consumes the flags that tst sets.
| The stub re-executes the displaced tst before returning, so the Z flag is correct.

    .equ F_NOTIMER,  0x800000d4
    .equ F_LAZY,     0x800000d8

    .equ CD_ENABLE,  0x460d1e4c      | the countdown's own enable field
    .equ CD_RESUME,  0x40056abe      | the beq that follows the displaced tst

    .text
    .global _start
_start:
cd_stub:
    tst.l   F_NOTIMER
    bne.b   cd_off                  | flag set -> patched: no countdown at all
    tst.l   CD_ENABLE               | displaced instruction, restores the Z flag
    jmp     CD_RESUME               | factory path, straight back into the tick
cd_off:
    rts
