// GhidraResolve15.java
//
// Follow-up to GhidraResolve14's findings:
//
//   1. FUN_4006e450 has exactly ONE xref, type=DATA, from 0x400b2bfc.
//      That means it's stored as a function-pointer VALUE somewhere,
//      likely inside a dispatch/menu table, not called via bsr/jsr.
//      We dump the surrounding words at 400b2bfc to see the table shape
//      -- how many entries, whether neighboring entries are also valid
//      function pointers into our program (which would confirm it's a
//      menu-item table and might include our still-unresolved
//      direct_ref_* getters as siblings).
//
//   2. DAT_46c8d1a0 / DAT_46c8d19c are both written in exactly one place:
//      FUN_4006de34. Decompiling it should reveal what these globals
//      actually represent (very likely "current selected track" /
//      "current selected page or mode").
//
//   3. Three near-identical functions cluster right after FUN_4006e450 --
//      FUN_4006ecf8, FUN_4006edc8, FUN_4006eefc -- each reading then
//      writing struct offset +0x129 (the trig-mode byte). This mirrors
//      the earlier pattern of near-duplicate per-track-type functions
//      (direct_ref_40081f64 / direct_ref_400826d4). Decompiling all
//      three side by side should show whether they differ only in a
//      track-type check, a bounds constant, or something more
//      structural -- and whether one of the three is where the actual
//      MIDI-track bug lives.

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Parameter;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolTable;

public class GhidraResolve15 extends GhidraScript {

    private static final long CONTEXT_SETTER = 0x4006de34L;
    private static final long GETSET_1 = 0x4006ecf8L;
    private static final long GETSET_2 = 0x4006edc8L;
    private static final long GETSET_3 = 0x4006eefcL;

    private static final long TABLE_HIT_ADDR = 0x400b2bfcL; // where FUN_4006e450's pointer was found
    private static final int TABLE_WORDS_BEFORE = 12; // how many 4-byte entries to dump before the hit
    private static final int TABLE_WORDS_AFTER = 12;  // and after

    @Override
    public void run() throws Exception {
        println("=========================================================");
        println("PART 1: Decompiling context-setter FUN_4006de34");
        println("=========================================================");
        decompileByAddr(CONTEXT_SETTER);

        println("");
        println("=========================================================");
        println("PART 2: Decompiling the three clustered offset+0x129 functions");
        println("=========================================================");
        decompileByAddr(GETSET_1);
        decompileByAddr(GETSET_2);
        decompileByAddr(GETSET_3);

        println("");
        println("=========================================================");
        println("PART 3: Inspecting data table around " + toAddr(TABLE_HIT_ADDR));
        println("=========================================================");
        dumpTable(TABLE_HIT_ADDR, TABLE_WORDS_BEFORE, TABLE_WORDS_AFTER);

        println("");
        println("=== GhidraResolve15 complete ===");
    }

    private void decompileByAddr(long addrLong) throws Exception {
        Address addr = toAddr(addrLong);
        FunctionManager fm = currentProgram.getFunctionManager();
        Function f = fm.getFunctionAt(addr);

        println("");
        if (f == null) {
            println("--- No function defined at " + addr + " -- skipping.");
            return;
        }

        println("--- " + f.getName() + " @ " + addr);

        DecompInterface decomp = new DecompInterface();
        try {
            decomp.openProgram(currentProgram);
            DecompileResults res = decomp.decompileFunction(f, 60, monitor);

            if (res != null && res.decompileCompleted()) {
                println("Parameter count: " + f.getParameterCount());
                for (Parameter p : f.getParameters()) {
                    println("  param: " + p.getName() + " : " + p.getDataType());
                }
                println(res.getDecompiledFunction().getC());
            } else {
                String msg = (res != null) ? res.getErrorMessage() : "null DecompileResults";
                println("Decompilation FAILED or did not complete: " + msg);
            }
        } finally {
            decomp.dispose();
        }
    }

    private void dumpTable(long hitAddrLong, int before, int after) throws Exception {
        Memory mem = currentProgram.getMemory();
        FunctionManager fm = currentProgram.getFunctionManager();
        SymbolTable symTable = currentProgram.getSymbolTable();

        Address hitAddr = toAddr(hitAddrLong);
        Address start = hitAddr.subtract((long) before * 4);

        println("Dumping " + (before + after + 1) + " x 4-byte words starting at " + start
            + " (hit entry at " + hitAddr + " is index " + before + "):");
        println("");

        Address cur = start;
        for (int i = 0; i < before + after + 1; i++) {
            long val;
            try {
                val = mem.getInt(cur) & 0xFFFFFFFFL;
            } catch (Exception e) {
                println("  " + cur + "  <unreadable: " + e.getMessage() + ">");
                cur = cur.add(4);
                continue;
            }

            String marker = cur.equals(hitAddr) ? "  <-- our hit (FUN_4006e450 pointer)" : "";
            String annotation = "";

            try {
                Address asAddr = toAddr(val);
                Function fAtVal = fm.getFunctionAt(asAddr);
                if (fAtVal != null) {
                    annotation = "  => valid function: " + fAtVal.getName();
                } else {
                    Symbol[] syms = symTable.getSymbols(asAddr);
                    if (syms != null && syms.length > 0) {
                        annotation = "  => has symbol(s): " + syms[0].getName()
                            + (syms.length > 1 ? " (+" + (syms.length - 1) + " more)" : "");
                    }
                }
            } catch (Exception ignore) {
                // val doesn't form a valid address in this address space; leave annotation blank
            }

            println("  " + cur + "  = 0x" + Long.toHexString(val) + annotation + marker);
            cur = cur.add(4);
        }
    }
}
