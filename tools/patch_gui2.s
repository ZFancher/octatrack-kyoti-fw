| patch_gui v2 — GUI-in-transition, con guarda de reentrada.
|
| BUG CORREGIDO (crash en hardware, EXCEPTION VEC:0B, PC en direccion muerta):
| el return-hook usaba UNA sola ranura global (SAVE_RET) y UNA sola bandera
| (DID_OVERRIDE). Si FUN_40052e98 se entraba anidado -- p.ej. manteniendo [SCENE B]
| y girando un encoder sobre un track en transicion -- pasaba esto:
|
|   1. externa: SAVE_RET = retExterna, (sp) = cleanup, DID_OVERRIDE = 1
|   2. interna: SAVE_RET = retInterna   <-- pisa retExterna
|   3. interna retorna -> cleanup -> restaura, DID_OVERRIDE = 0, jmp retInterna  (ok)
|   4. externa retorna -> cleanup -> DID_OVERRIDE ya es 0 -> jmp SAVE_RET, que
|      sigue siendo retInterna -> SALTO A UNA DIRECCION YA CONSUMIDA -> F-line
|
| Ademas, en el paso 2 la interna guardaba como "originales" los globals que la
| externa YA habia sobreescrito, asi que la restauracion los dejaba corruptos.
|
| FIX: si ya hay un override activo, la reentrada NO overridea ni engancha el
| retorno; corre como de fabrica. Una sola ranura alcanza porque solo puede haber
| un override vivo a la vez. Cuesta 3 instrucciones.
|
| Todo lo demas es identico al original: si el track actual esta en transicion
| (per_track_part[track] != Part activo), redirige DAT_100b14cf/80000002/80000003
| a source y engancha el retorno a cleanup para restaurarlos en TODAS las salidas
| del editor (rts y tail-call).
|
| Regs scratch: d0,d1,d6,a0. NO toca d2-d5/a2-a3 (los salva el movem del editor).

    .cpu 5407
    .text
| ===== SETUP (entrada de FUN_40052e98 = editor de encoder) =====
setup:
    move.b 0x80006c33, %d0          | DID_OVERRIDE
    bne.w no_override               | <-- FIX: ya hay uno activo -> reentrada, no tocar
                                    | (.w: el salto cruza todo el bloque de override, >127 B)
    move.b 0x100b14cc, %d0          | track
    andi.l #0xff, %d0
    lea 0x8000182a, %a0
    move.b (0,%a0,%d0:l), %d1       | per_track_part[track]
    andi.l #0xff, %d1
    move.b 0x80000002, %d6          | Part activo
    andi.l #0xff, %d6
    cmp.l %d1, %d6
    beq.b no_override               | per_track == activo -> edicion normal
    | --- TRANSICION: override a source ---
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
    move.l (%a7), %d6               | direccion de retorno real (SP -> ret)
    move.l %d6, 0x80006c34          | SAVE_RET
    move.l #cleanup, (%a7)          | engancha retorno -> cleanup
    moveq #1, %d6
    move.b %d6, 0x80006c33          | DID_OVERRIDE
no_override:
    .short 0x4fef, 0xffe0           | lea -0x20(a7),a7   (entrada desplazada)
    .short 0x48d7, 0x0cfc           | movem.l d2-d5/a2-a3,(a7)
    jmp 0x40052ea0                  | continua el editor
| ===== CLEANUP (return-hook: el editor retorna aqui) =====
| Solo se llega aca si setup engancho el retorno, y eso solo pasa cuando
| DID_OVERRIDE estaba en 0. Asi que DID_OVERRIDE siempre vale 1 al entrar y
| SAVE_RET siempre es el retorno de ESTA invocacion.
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
