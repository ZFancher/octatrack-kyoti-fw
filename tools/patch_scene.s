| scene_stub — "sticky global" scene A/B selection across Part/pattern changes.
| Inserted into the existing detour chain at FUN_40009094 (part-apply):
|   0x40009094 -> scene_stub -> save_stub(0x400d64e0) -> ... -> jmp 0x4000909c
| It copies the OUTGOING pattern's scene A/B selection (LAST_PAT block) into the
| INCOMING pattern's selection (arg2 block) so the crossfader keeps reading the
| previously-selected scenes. Manual scene assignment still overwrites normally.
|
| Data model (verified): project base = *(0x46c82456);
|   sceneA = base + pattern*0x18b2 + 0x8ed90 ; sceneB = +0x8ed91  (bytes)
| Crossfader FUN_4003f1b4 reads them indexed by active pattern (0x80000003).
|
| RAM: LAST_PAT @0x80006c60 (byte), INIT_FLAG @0x80006c61 (0xA5 = LAST_PAT valid)
| (does not collide with audio 0x80006a00.. or GUI 0x80006c30..)
| Cave: 0x400d6700 (free; past GUI stubs).

    .text
    .global _start
_start:
scene_stub:
    lea     -0x18(%sp),%sp             | reservar 0x18 (ColdFire no admite movem -(An))
    .short  0x48d7, 0x030f             | movem.l %d0-%d3/%a0-%a1,(%sp)  (save)
    move.l  0x20(%sp),%d0               | d0 = arg2 (incoming pattern), was 8(sp)+0x18
    andi.l  #0xff,%d0                   | d0 = new pattern (0..255)
    moveq   #0,%d1
    move.b  0x80006c61,%d1              | d1 = INIT_FLAG
    cmpi.l  #0xa5,%d1
    bne.b   sc_first                    | not yet initialized -> just record, no copy
    moveq   #0,%d1
    move.b  0x80006c60,%d1              | d1 = LAST_PAT (outgoing pattern)
    cmp.l   %d0,%d1
    beq.b   sc_restore                  | same pattern -> nothing to do
    movea.l 0x46c82456,%a1             | a1 = project data base
    move.l  #0x18b2,%d3                 | pattern stride
    move.l  %d1,%d2
    muls.l  %d3,%d2                     | d2 = LAST_PAT * 0x18b2
    lea     (%a1,%d2.l),%a0
    adda.l  #0x8ed90,%a0               | a0 = src (outgoing scene A)
    move.l  %d0,%d2
    muls.l  %d3,%d2                     | d2 = new * 0x18b2
    lea     (%a1,%d2.l),%a1
    adda.l  #0x8ed90,%a1               | a1 = dst (incoming scene A)
    move.b  (%a0),%d2
    move.b  %d2,(%a1)                   | copy scene A
    move.b  1(%a0),%d2
    move.b  %d2,1(%a1)                  | copy scene B
sc_first:
    move.b  %d0,0x80006c60             | LAST_PAT = new pattern
    move.l  #0xa5,%d1
    move.b  %d1,0x80006c61             | INIT_FLAG = valid
sc_restore:
    .short  0x4cd7, 0x030f             | movem.l (%sp),%d0-%d3/%a0-%a1  (restore)
    lea     0x18(%sp),%sp              | liberar 0x18
    jmp     0x400d64e0                  | -> save_stub (audio patch entry logic)
