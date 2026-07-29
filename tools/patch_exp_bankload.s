    .cpu 5407
    .text
| =====================================================================
|  EXPERIMENT v2 (throwaway): does loading a NON-playing bank from CF glitch
|  the audio while the sequencer runs?
|
|  v1 hooked the job poster but left the synchronous pre-step FUN_400a10c8
|  running -> it reset per-track note/voice scratch and CUT the audio at the
|  instant of confirm (before the async load even started). The real feature
|  posts the job WITHOUT that pre-step; so v2 hooks the reload confirm handler
|  FUN_40063bf8 at its `jsr FUN_400a10c8` and:
|    - SKIPS FUN_400a10c8 (the audio-cutting pre-step), and
|    - forces the reload mask to a NON-playing bank: (current+1) & 15,
|    - then posts via the stock FUN_40022778.
|  (FUN_40022778 has a single caller, FUN_40063bf8, so this catches the gesture.)
|  The end-of-load re-sync (FUN_400238a4) is still left stock this round, to
|  isolate the pre-step as the immediate-cut cause.
|
|  Test: play an AUDIO track, do the RELOAD BANK gesture, listen.
|    audio continues (maybe a blip only at load completion) -> background load
|      of a non-playing bank is clean; feature viable (blip = re-sync, skip next).
|    audio still cuts instantly -> something else in the path cuts; report.
| =====================================================================
exp_cave:
    mvz.b   0x100b14ce, %d1    | current bank index (byte)
    addq.l  #1, %d1
    moveq   #15, %d0
    and.l   %d0, %d1           | (current+1) & 15  -> a NON-current bank
    moveq   #1, %d0
    lsl.l   %d1, %d0           | D0 = 1 << target  (reload mask)
    mvz.w   %d0, %d0
    move.l  %d0, 4(%sp)        | store mask into the param slot (as the stock code does)
    jmp     0x40022778         | post the job (stock; reads mask at 0x4(SP))
