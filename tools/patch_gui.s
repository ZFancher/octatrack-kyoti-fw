    .cpu 5407
    .text
| ===== SETUP (entrada de FUN_40052e98 = editor de encoder) =====
| Si el track actual está en transición (per_track_part[track] != Part_activo),
| redirige DAT_100b14cf/80000002/80000003 -> source, y engancha el retorno a cleanup.
| Regs scratch: d0,d1,d6,a0. NO toca d2-d5/a2-a3 (los salva el movem del editor).
setup:
    move.b 0x100b14cc, %d0          | track
    andi.l #0xff, %d0
    lea 0x8000182a, %a0
    move.b (0,%a0,%d0:l), %d1       | per_track_part[track]
    andi.l #0xff, %d1
    move.b 0x80000002, %d6          | Part activo
    andi.l #0xff, %d6
    cmp.l %d1, %d6
    beq.b no_override               | per_track == activo -> edición normal
    | --- TRANSICIÓN: override a source ---
    move.b 0x100b14cf, %d6
    move.b %d6, 0x80006c30          | SAVE_CF
    move.b 0x80000002, %d6
    move.b %d6, 0x80006c31          | SAVE_02
    move.b 0x80000003, %d6
    move.b %d6, 0x80006c32          | SAVE_03
    lea 0x80001832, %a0
    move.b (0,%a0,%d0:l), %d6       | per_track_pattern[track]
    move.b %d6, 0x100b14cf          | DAT_100b14cf = source pattern
    move.b %d6, 0x80000003          | pattern activo = source (para el gate)
    move.b %d1, 0x80000002          | part activo = source (para el gate)
    move.l (%a7), %d6               | dirección de retorno real (SP -> ret)
    move.l %d6, 0x80006c34          | SAVE_RET
    move.l #cleanup, (%a7)          | engancha retorno -> cleanup
    moveq #1, %d6
    move.b %d6, 0x80006c33          | DID_OVERRIDE
no_override:
    .short 0x4fef, 0xffe0           | lea -0x20(a7),a7   (entrada desplazada)
    .short 0x48d7, 0x0cfc           | movem.l d2-d5/a2-a3,-(a7)
    jmp 0x40052ea0                  | continúa el editor
| ===== CLEANUP (return-hook: el editor retorna aquí) =====
cleanup:
    move.b 0x80006c33, %d0          | DID_OVERRIDE
    beq.b cl_done
    move.b 0x80006c30, %d0
    move.b %d0, 0x100b14cf          | restaura DAT_100b14cf
    move.b 0x80006c31, %d0
    move.b %d0, 0x80000002          | restaura Part activo
    move.b 0x80006c32, %d0
    move.b %d0, 0x80000003          | restaura pattern activo
    clr.b 0x80006c33
cl_done:
    move.l 0x80006c34, %d0
    movea.l %d0, %a0
    jmp (%a0)                       | retorna al llamador real
