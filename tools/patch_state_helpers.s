    .cpu 5407
    .text
| =====================================================================
|  Dual-table STATIC state-table accessors (approach B) — Layer 1.
|
|  Stock computes a state-slot pointer inline as `base + slot*44` with
|  base = 0x46c90a78 (table A, 128 slots). 35 random-access sites do
|  `muls.l dS,REG` (REG = slot*44) then `addi.l/adda.l #0x46c90a78,REG`.
|
|  We replace ONLY that 6-byte base-add with `jsr sh_<REG>` (also 6 bytes,
|  byte-exact). The helper range-checks the PRODUCT (= slot*44):
|      product <= 0x1600 (slot 0..128) -> + 0x46c90a78   (table A, unchanged)
|      product >  0x1600 (slot 129..255)-> + ADJ          (table B, Layer-2)
|
|  CRITICAL: the boundary is `bhi` (strictly >), NOT `bcc` (>=). Index 128 is
|  the stock TEMPLATE/sentinel slot at table-A end (0x46c92078); accessors with
|  an INCLUSIVE `bls #128` guard, and the voice "empty slot" path, legitimately
|  read it. It MUST stay in table A. A `bcc` here redirected index 128 into the
|  uninitialised table B -> garbage ptr -> jump to 0 -> VEC:04 ADDR:0 crash in
|  the audio interrupt. `bhi` keeps 0..128 in table A = byte-identical to stock.
|
|  Behaviour-neutral by construction: stock never produces a static-base product
|  above 128*44=0x1600 (128 slots + the index-128 template; recorders use the
|  FLEX base), so the table-B branch is dead until Layer 2. The index-128 /
|  new-slot-128 collision is a Layer-2 problem (split template vs audio sites).
|
|  No register or CCR clobber: cmpi.l/cmpa.l #imm are single-op on ColdFire,
|  and no call site branches on the base-add's CCR (verified: each is
|  followed by an unconditional branch, a movea, or a flag-overwriting move).
|  The allocator loop-start (lea 0x46c90a78 @ 0x4002409c) is NOT converted
|  here — it walks table A sequentially and is extended in Layer 2.
| =====================================================================
    .equ TA,  0x46c90a78
    .equ ADJ, 0x40afea00           | 0x40b00000 - 0x1600

sh_d0:  cmpi.l #0x1600,%d0
        bhi.b  sh_d0_hi
        addi.l #TA,%d0
        rts
sh_d0_hi:
        addi.l #ADJ,%d0
        rts

sh_d1:  cmpi.l #0x1600,%d1
        bhi.b  sh_d1_hi
        addi.l #TA,%d1
        rts
sh_d1_hi:
        addi.l #ADJ,%d1
        rts

sh_d2:  cmpi.l #0x1600,%d2
        bhi.b  sh_d2_hi
        addi.l #TA,%d2
        rts
sh_d2_hi:
        addi.l #ADJ,%d2
        rts

sh_d4:  cmpi.l #0x1600,%d4
        bhi.b  sh_d4_hi
        addi.l #TA,%d4
        rts
sh_d4_hi:
        addi.l #ADJ,%d4
        rts

sh_d5:  cmpi.l #0x1600,%d5
        bhi.b  sh_d5_hi
        addi.l #TA,%d5
        rts
sh_d5_hi:
        addi.l #ADJ,%d5
        rts

sh_a0:  cmpa.l #0x1600,%a0
        bhi.b  sh_a0_hi
        adda.l #TA,%a0
        rts
sh_a0_hi:
        adda.l #ADJ,%a0
        rts

sh_a2:  cmpa.l #0x1600,%a2
        bhi.b  sh_a2_hi
        adda.l #TA,%a2
        rts
sh_a2_hi:
        adda.l #ADJ,%a2
        rts

sh_a3:  cmpa.l #0x1600,%a3
        bhi.b  sh_a3_hi
        adda.l #TA,%a3
        rts
sh_a3_hi:
        adda.l #ADJ,%a3
        rts

sh_a5:  cmpa.l #0x1600,%a5
        bhi.b  sh_a5_hi
        adda.l #TA,%a5
        rts
sh_a5_hi:
        adda.l #ADJ,%a5
        rts
