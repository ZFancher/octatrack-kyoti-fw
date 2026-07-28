    .cpu 5407
    .text
| ===== SAVE_STUB (entrada de FUN_40009094) =====
save_stub:
    | gate: LAZY TRANSITIONS apagado -> comportamiento de fabrica
    tst.l 0x800000d8
    beq.w sv_stock
    | bulk-save buffers de voz: 0x80000a50 -> 0x80006a00 (0x200 B = 128 longs)
    lea 0x80000a50, %a0
    lea 0x80006a00, %a1
    move.l #128, %d0
sv1:
    move.l (%a0)+, (%a1)+
    subq.l #1, %d0
    bne.b sv1
    | bulk-save per_track (16 B): 0x8000182a -> 0x80006c00
    lea 0x8000182a, %a0
    lea 0x80006c00, %a1
    moveq #4, %d0
sv2:
    move.l (%a0)+, (%a1)+
    subq.l #1, %d0
    bne.b sv2
    | flags de "sonando" por track -> 0x80006c20
    moveq #0, %d1
    lea 0x800049d8, %a0
    lea 0x80006c20, %a1
sv3:
    move.b (%a0), %d0
    move.b %d0, (%a1)+
    lea 0xa8(%a0), %a0
    addq.l #1, %d1
    moveq #8, %d0
    cmp.l %d1, %d0
    bne.b sv3
    | instrucciones desplazadas de la entrada + continúa
sv_stock:
    .short 0x4fef, 0xff98
    .short 0x48d7, 0x7cfc
    jmp 0x4000909c
| ===== RESTORE_STUB (rts de FUN_40009094) =====
restore_stub:
    | gate: si no se salvo nada, no hay nada que restaurar
    tst.l 0x800000d8
    beq.w rs_stock
    moveq #0, %d1
rs0:
    lea 0x80006c20, %a0
    move.b (0,%a0,%d1:l), %d0
    beq.b rs_next
    | [NUEVO] snapshot de los params de DESTINO antes de pisarlos:
    | en este instante el buffer de voz todavia tiene lo que escribio apply_part.
    | enc_stub los consume cuando el usuario mueve un encoder del track.
    move.l %d1, %d0
    lsl.l #6, %d0
    lea 0x80000a50, %a0
    add.l %d0, %a0
    lea 0x80006e00, %a1
    add.l %d0, %a1
    moveq #16, %d0
rs_snap:
    move.l (%a0)+, (%a1)+
    subq.l #1, %d0
    bne.b rs_snap
    | restaura buffer de voz del track (params de ORIGEN)
    move.l %d1, %d0
    lsl.l #6, %d0
    lea 0x80006a00, %a0
    add.l %d0, %a0
    lea 0x80000a50, %a1
    add.l %d0, %a1
    moveq #16, %d0
rs1:
    move.l (%a0)+, (%a1)+
    subq.l #1, %d0
    bne.b rs1
    | restaura per_track_part/pattern (para re-aplicar en el trig)
    lea 0x80006c00, %a0
    move.b (0,%a0,%d1:l), %d0
    lea 0x8000182a, %a1
    move.b %d0, (0,%a1,%d1:l)
    lea 0x80006c08, %a0
    move.b (0,%a0,%d1:l), %d0
    lea 0x80001832, %a1
    move.b %d0, (0,%a1,%d1:l)
rs_next:
    addq.l #1, %d1
    moveq #8, %d0
    cmp.l %d1, %d0
    bne.b rs0
    | tail-call original desplazado (a post_work)
rs_stock:
    jmp 0x40000c3c
