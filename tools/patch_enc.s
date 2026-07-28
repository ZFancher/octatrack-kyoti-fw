| enc_stub — moving an encoder on a track that is still in transition makes that track
| adopt the destination Part immediately (spec point (c), semantics "B": you hear the
| jump at that moment).
|
| REPLACES the GUI-in-transition patch, which did the opposite: it wrote edits into the
| SOURCE Part and deliberately kept the track dirty. It also overrode shared globals
| (0x80000002/03) for the duration of the edit and installed a return-hook whose single
| saved slot caused a hardware crash. None of that is needed now: this stub touches only
| this track's voice buffer and its own index bytes, and returns through the normal path.
|
| The problem it solves: by the time the user moves the encoder, the destination Part's
| parameters no longer exist anywhere. apply_part computed them at the pattern change and
| restore_stub then overwrote them, which is exactly how the track stays protected. So
| restore_stub now snapshots them on the way past -- at that instant the voice buffer still
| holds the destination values -- and this stub consumes the snapshot.
|
|   pattern change:  save_stub    saves SOURCE params
|                    apply_part   writes DESTINATION params into the voice buffer
|                    restore_stub snapshots them to DEST_SNAP[T], then restores SOURCE
|   encoder move:    enc_stub     DEST_SNAP[T] -> voice buffer, clear the dirty index
|
| Host FUN_40052e98, entry displaced exactly as the previous patch did:
|   lea -0x20(a7),a7 ; movem.l d2-d5/a2-a3,(a7) ; jmp 0x40052ea0
| Scratch: d0,d1,d6,a0,a1 -- d2-d5/a2-a3 are saved by the editor's own movem, which runs
| after us, so they must not be touched here.

    .equ F_LAZY,     0x800000d8      | PERSONALIZE: LAZY TRANSITIONS
    .equ CUR_TRACK,  0x100b14cc      | track being edited
    .equ TRK_PART,   0x8000182a      | per_track_part[8]
    .equ TRK_PAT,    0x80001832      | per_track_pattern[8]
    .equ ACT_PART,   0x80000002
    .equ ACT_PAT,    0x80000003
    .equ VOICE,      0x80000a50      | voice buffer, 0x40 per track
    .equ DEST_SNAP,  0x80006e00      | destination params parked by restore_stub
    .equ EDITOR,     0x40052ea0      | after the displaced prologue

    .cpu 5407
    .text
    .global _start
_start:
enc_stub:
    tst.l   F_LAZY
    beq.w   ec_done                 | feature off -> stock editor

    moveq   #0,%d0
    move.b  CUR_TRACK,%d0           | d0 = track being edited
    andi.l  #7,%d0                  | 8 tracks; clamp so a stray value cannot
                                    | index outside the arrays below
    lea     TRK_PART,%a0
    moveq   #0,%d1
    move.b  (%a0,%d0.l),%d1         | per_track_part[track]
    moveq   #0,%d6
    move.b  ACT_PART,%d6
    cmp.l   %d6,%d1
    beq.b   ec_done                 | same Part -> not in transition, nothing to do

    | --- adopt the destination Part for this track ---
    move.l  %d0,%d1
    lsl.l   #6,%d1                  | track * 0x40
    lea     DEST_SNAP,%a0
    adda.l  %d1,%a0
    lea     VOICE,%a1
    adda.l  %d1,%a1
    moveq   #16,%d1                 | 0x40 bytes = 16 longs
ec_copy:
    move.l  (%a0)+,(%a1)+
    subq.l  #1,%d1
    bne.b   ec_copy

    | --- clear the dirty marker so the LED goes back to full brightness ---
    lea     TRK_PART,%a0
    move.b  %d6,(%a0,%d0.l)         | per_track_part[track] = active Part
    moveq   #0,%d6
    move.b  ACT_PAT,%d6
    lea     TRK_PAT,%a0
    move.b  %d6,(%a0,%d0.l)         | per_track_pattern[track] = active pattern

ec_done:
    .short  0x4fef, 0xffe0          | lea -0x20(a7),a7        (displaced entry)
    .short  0x48d7, 0x0cfc          | movem.l d2-d5/a2-a3,(a7)
    jmp     EDITOR
