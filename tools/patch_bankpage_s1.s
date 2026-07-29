    .cpu 5407
    .text
| =====================================================================
|  BANK PAGING — Stage 1 (redirected sibling load, crudely triggered)
|
|  Proves the path redirect: the RELOAD BANK gesture now loads the 15
|  NON-playing banks from the sibling project "<current>_2" into RAM,
|  without stopping audio. Reuses the hardware-proven de-risk path
|  (skip pre-step FUN_400a10c8, skip re-sync FUN_400238a4, mask excludes
|  the playing bank) plus a redirect of FUN_40025230's project dir.
|
|  Redirect mechanism: g_redirect (char*, default 0). FUN_40025230 uses it
|  in place of the default project-name global (0x100f8378) ONLY when the
|  caller passes projname==0 (which the bank load/copy path does). Set it
|  to "<name>_2" before posting the job; cleared in the load done-callback.
|
|  Requires a sibling project "<current>_2" on the card that SHARES the
|  sample pool (SAVE PROJECT AS from the base, edit patterns only).
| =====================================================================

| ---- GATE: hook FUN_40025230 @0x40025244 (the projname==0 default) ----
| entry here only when projname arg (A0) == 0; D0 already = base dir.
gate_cave:
    move.l  g_redirect, %a0        | A0 = redirect target (or 0)
    tst.l   %a0
    bne.b   gc_use                 | if set, use it
    lea     0x100f8378, %a0        | else stock default: current project name
gc_use:
    jmp     0x4002524a             | resume FUN_40025230

| ---- TRIGGER: hook FUN_40063bf8 @0x40063bfe (was `jsr FUN_400a10c8`) ----
| skips the audio-cutting pre-step; builds "<name>_2"; arms redirect;
| sets mask = all banks except the playing one; tail-posts the job.
trig_cave:
    pea     0x100f8378             | sprintf arg: current project name ptr
    pea     fmt_s2                 | "%s_2"
    pea     sib_name               | dest buffer
    jsr     0x40013a08             | sprintf(sib_name, "%s_2", name)
    lea     12(%sp), %sp
    lea     sib_name, %a0
    move.l  %a0, g_redirect        | arm redirect
    mvz.b   0x100b14ce, %d1        | current (playing) bank
    moveq   #1, %d0
    lsl.l   %d1, %d0               | 1 << bank
    not.l   %d0                    | low word = 0xffff & ~(1<<bank)  (all others)
    move.l  %d0, 4(%sp)            | store mask into the param slot
    jmp     0x40022778             | post the reload job (stock poster)

| ---- DONE: hook FUN_40023998 @0x400239a2 (was `jsr FUN_400238a4` + `pea`) ----
| clears the redirect and SKIPS the re-sync; replicates the displaced pea.
done_cave:
    clr.l   g_redirect             | load finished -> disarm redirect
    move.l  #1, -(%sp)             | replicate displaced `pea (0x1).w`
    jmp     0x400239aa             | resume at `jsr FUN_40022e04`

| ---- DATA (writable SDRAM in the loaded image) ----
    .balign 4
g_redirect: .long 0
fmt_s2:     .asciz "%s_2"
    .balign 4
sib_name:   .space 288
