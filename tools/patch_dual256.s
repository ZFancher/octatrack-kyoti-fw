    .cpu 5407
    .text
| =====================================================================
|  DUAL-256 redirect helpers — one family for the FOUR per-slot tables that travel together.
|
|  Contract (same proven shape as patch_state_helpers_b.s): each helper receives PRODUCT = idx*stride
|  in its register and returns the correct table pointer, redirecting the new slots into table B while
|  leaving every stock index byte-identical to the original `add #base,reg`:
|
|    SETTINGS (stride 0x448, base 0x100d5b30): idx<128 -> A ; idx 128..255 -> B ; idx>=256 -> A
|      (settings has NO template; A[128]=0x100f7f30 is OOB, so redirect starts at 128.)
|    STATE    (stride 44,    base 0x46c90a78): idx<=128 -> A (incl. TEMPLATE A[128]) ; 129..255 -> B ; >=256 -> A
|    STRIDE4#1(stride 4,     base 0x46c920a4): idx<=128 -> A ; 129..255 -> B ; >=256 -> A
|    STRIDE4#2(stride 4,     base 0x46c93a24): idx<=128 -> A ; 129..255 -> B ; >=256 -> A
|
|  Two-sided product bounds send OOR/sentinel indices back to A = stock-safe; the redirect fires only
|  for real new slots. B-table layout ABOVE the OS working-DDR region (which the OS itself boot-clears
|  as [0x46025de0, 0x4763d580) -- putting B-tables inside it is what corrupted the project). New base
|  0x47700000 is above that boundary, so the OS never touches it and B cannot corrupt project state.
|    STATE-B    0x47700000 (+0x1600)   STRIDE4-B1 0x47701600 (+0x200)   STRIDE4-B2 0x47701800 (+0x200)
|    SETTINGS-B 0x47701a00 (+0x22400 -> ends 0x47723e00)
|  ADJ = B_base - idx0*stride, so B addr = product + ADJ = B_base + (idx-idx0)*stride.
| =====================================================================

|  ---- SETTINGS: A=0x100d5b30, redirect idx 128..255 ----
    .equ SET_A,    0x100d5b30
    .equ SET_LO,   0x22400            | 128*0x448  (product < LO -> A)
    .equ SET_HI,   0x44800            | 256*0x448  (product >= HI -> A, OOR)
    .equ SET_ADJ,  0x476df600         | SETTINGS_B(0x47701a00) - 128*0x448
|  folded +0x10e field accessors:
    .equ SETF_A,   0x100d5c3e         | SET_A + 0x10e
    .equ SETF_ADJ, 0x476df70e         | SET_ADJ + 0x10e

|  ---- STATE: A=0x46c90a78, redirect idx 129..255 (template A[128]) ----
    .equ ST_A,     0x46c90a78
    .equ ST_LO,    0x1600             | 128*44   (product <= LO -> A, incl template)
    .equ ST_HI,    0x2bf4             | 255*44   (product > HI -> A, OOR)
    .equ ST_ADJ,   0x476fea00         | STATE_B(0x47700000) - 128*44

|  ---- STRIDE4#1: A=0x46c920a4, redirect idx 129..255 ----
    .equ S41_A,    0x46c920a4
    .equ S4_LO,    0x200              | 128*4
    .equ S4_HI,    0x3fc              | 255*4
    .equ S41_ADJ,  0x47701400         | STRIDE4B1(0x47701600) - 128*4

|  ---- STRIDE4#2: A=0x46c93a24, redirect idx 129..255 ----
    .equ S42_A,    0x46c93a24
    .equ S42_ADJ,  0x47701600         | STRIDE4B2(0x47701800) - 128*4

| SETTINGS data helpers (blo=below LO ->A, bhs HI ->A):
    .macro SETD reg
h_set_\reg:  cmpi.l #SET_LO,%\reg
        blo.b  9f
        cmpi.l #SET_HI,%\reg
        bhs.b  9f
        addi.l #SET_ADJ,%\reg
        rts
9:      addi.l #SET_A,%\reg
        rts
    .endm
    .macro SETA reg
h_set_\reg:  cmpa.l #SET_LO,%\reg
        blo.b  9f
        cmpa.l #SET_HI,%\reg
        bhs.b  9f
        adda.l #SET_ADJ,%\reg
        rts
9:      adda.l #SET_A,%\reg
        rts
    .endm
| folded +0x10e (data only, d0):
h_setf_d0:  cmpi.l #SET_LO,%d0
        blo.b  9f
        cmpi.l #SET_HI,%d0
        bhs.b  9f
        addi.l #SETF_ADJ,%d0
        rts
9:      addi.l #SETF_A,%d0
        rts

| STATE helpers (product<=LO ->A incl template ; LO<p<=HI ->B ; p>HI ->A):
    .macro STD reg
h_st_\reg:  cmpi.l #ST_LO,%\reg
        bls.b  9f
        cmpi.l #ST_HI,%\reg
        bhi.b  9f
        addi.l #ST_ADJ,%\reg
        rts
9:      addi.l #ST_A,%\reg
        rts
    .endm
    .macro STA reg
h_st_\reg:  cmpa.l #ST_LO,%\reg
        bls.b  9f
        cmpa.l #ST_HI,%\reg
        bhi.b  9f
        adda.l #ST_ADJ,%\reg
        rts
9:      adda.l #ST_A,%\reg
        rts
    .endm

| STRIDE4#1 helpers:
    .macro S41D reg
h_s41_\reg: cmpi.l #S4_LO,%\reg
        bls.b  9f
        cmpi.l #S4_HI,%\reg
        bhi.b  9f
        addi.l #S41_ADJ,%\reg
        rts
9:      addi.l #S41_A,%\reg
        rts
    .endm
    .macro S41A reg
h_s41_\reg: cmpa.l #S4_LO,%\reg
        bls.b  9f
        cmpa.l #S4_HI,%\reg
        bhi.b  9f
        adda.l #S41_ADJ,%\reg
        rts
9:      adda.l #S41_A,%\reg
        rts
    .endm
| STRIDE4#2 helpers:
    .macro S42D reg
h_s42_\reg: cmpi.l #S4_LO,%\reg
        bls.b  9f
        cmpi.l #S4_HI,%\reg
        bhi.b  9f
        addi.l #S42_ADJ,%\reg
        rts
9:      addi.l #S42_A,%\reg
        rts
    .endm
    .macro S42A reg
h_s42_\reg: cmpa.l #S4_LO,%\reg
        bls.b  9f
        cmpa.l #S4_HI,%\reg
        bhi.b  9f
        adda.l #S42_ADJ,%\reg
        rts
9:      adda.l #S42_A,%\reg
        rts
    .endm

| ---- instantiate exactly the (table,reg) helpers the census requires ----
    SETD d0
    SETD d1
    SETD d2
    SETD d3
    SETA a1
    SETA a2
    SETA a3
    SETA a4
    STD  d0
    STD  d1
    STD  d4
    STD  d5
    STA  a0
    STA  a2
    STA  a3
    S41A a0
    S41D d0
    S42A a0
    S42D d0
