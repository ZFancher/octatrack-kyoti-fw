| trig_stub — mark the selected scene trig AMBER while any track is in transition.
|
| Companion to patch_led.s. The track LEDs carry the exact per-track dirty state;
| this is the approximate, global hint on the scene trig: "something is still
| sounding with the source Part's parameters".
|
| Host: FUN_40034a44, the 16-trig painter. It builds two local arrays and only then
| emits them:
|   colour  local_80  @ SP+0xA0, 4 B per entry, 2 entries per trig (the two dies)
|   bright  local_100 @ SP+0x20, same layout
| Confirmed by the prologue: lea (-0x120,SP),SP ; lea (0xa0,SP),A6 ; lea (0x20,SP),A2.
|
| Colour combinations the stock code uses:
|   (0,0) empty trig   (0,1) trig with content   (1,0) selected scene   (.,1) other scene
| (1,1) — both dies lit, i.e. amber — is never used. That is the free slot we take.
|
| Insertion: 0x40034b5e, right after the scene overlay and before the function
| pointers are loaded. That address holds `lea (0x400a75f2).l,A3`, exactly 6 bytes,
| so the jmp displaces one whole instruction and nothing else.
|
| Registers: entered by jmp, so SP is untouched and nothing needs saving. A6 (colour
| walker), D3 (brightness walker) and SP must survive — everything else is either
| scratch or reloaded immediately after by the host. We use D0/D1/D4/A0/A1 and A3
| (which the displaced instruction sets anyway). The array base comes from A6, NOT
| from an SP offset, so the stub stays correct without a frame of its own.

    .equ TRK_PART,  0x8000182a      | per_track_part[8]
    .equ ACT_PART,  0x80000002      | active Part
    .equ PROJ_PTR,  0x46c82456      | -> project base
    .equ DISP_PAT,  0x100b14cf      | DISPLAYED pattern (what this painter uses)
    .equ SLOT_EDIT, 0x460d169c      | scene slot being edited, 1 = A
    .equ STRIDE,    0x18b2
    .equ SEL_OFF,   0x8ed90

    .equ ID_TABLE,  0x400a75f2      | operand of the displaced instruction
    .equ RESUME,    0x40034b64

    .text
    .global _start
_start:
trig_stub:
    tst.l   0x800000d8                 | gate: LAZY TRANSITIONS apagado -> no pintar
    beq.w   ts_done
    | --- is ANY track in transition? ---
    lea     TRK_PART,%a0
    moveq   #0,%d1
    move.b  ACT_PART,%d1               | d1 = active Part
    moveq   #7,%d0                     | scan tracks 7..0
ts_scan:
    moveq   #0,%d4
    move.b  (%a0,%d0.l),%d4            | per_track_part[i]
    cmp.l   %d1,%d4
    bne.b   ts_dirty                   | differs -> that track is in transition
    subq.l  #1,%d0
    bpl.b   ts_scan
    bra.b   ts_done                    | none dirty -> leave the painting alone

ts_dirty:
    | --- which trig is the selected scene? ---
    movea.l PROJ_PTR,%a0
    moveq   #0,%d0
    move.b  DISP_PAT,%d0
    move.l  #STRIDE,%d1
    muls.l  %d1,%d0
    adda.l  %d0,%a0
    adda.l  #SEL_OFF,%a0               | a0 = &live[displayed].sceneA

    moveq   #0,%d4
    move.b  SLOT_EDIT,%d4
    cmpi.l  #1,%d4
    bne.b   ts_slot_b
    moveq   #0,%d0
    move.b  (%a0),%d0                  | slot A in edit -> scene A
    bra.b   ts_set
ts_slot_b:
    moveq   #0,%d0
    move.b  1(%a0),%d0                 | otherwise scene B
ts_set:
    andi.l  #0xf,%d0                   | 16 trigs; clamp so a stray index cannot
                                       | write outside the 32-entry local array
    lsl.l   #3,%d0                     | *8 = byte offset of this trig's pair
    lea     (%a6,%d0.l),%a1            | a1 = &colour[trig*2]   (A6 = &colour[0])
    moveq   #1,%d4
    move.l  %d4,(%a1)                  | die 0 on
    move.l  %d4,4(%a1)                 | die 1 on  -> (1,1) = amber

ts_done:
    lea     ID_TABLE,%a3               | displaced instruction
    jmp     RESUME
