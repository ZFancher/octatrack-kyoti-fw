// GhidraResolve16.java
//
// GhidraResolve15 established that FUN_4006ecf8 / FUN_4006edc8 / FUN_4006eefc
// (the three clustered offset+0x129 functions near FUN_4006e450) are the
// SETTINGS-MENU encoder handlers for editing the trig-mode value (clamped
// -1..16, i.e. -1=DIRECT, 0-16=quantize-length index). That's useful for
// confirming the field, but it's UI, not the runtime engine -- not our bug.
//
// This script decompiles the remaining offset+0x129 hit functions found by
// GhidraResolve14's program-wide scan, all of which sit OUTSIDE the
// settings-menu address cluster (0x4006dxxx-0x4007fxxx) and are therefore
// much better candidates for actual playback/engine code:
//
//   FUN_40005030   (0x40005102 hit)
//   FUN_40025288   (0x400253ac hit)
//   FUN_4002ef28   (0x4002efc4 hit)
//   FUN_4002f2f8   (0x4002fdba hit)
//   FUN_400866c4   (0x40086d24 hit)
//   FUN_40089940   (0x40089bec hit -- was a `lea`, address calc only)
//   FUN_4008b8d0   (0x4008bc52 / 0x4008bc86 hits)
//   FUN_40099148   (0x40099340 hit)
//
// For each function, this also does a lightweight scan of its own
// instructions for references to nearby ASCII string data, printing any
// found -- a fast way to see if a function is annotated with strings like
// "MIDI", "AUDIO", "TRIG", "STEP", etc. without reading the full assembly
// by hand.

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.DataType;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Parameter;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.ReferenceManager;

public class GhidraResolve16 extends GhidraScript {

    private static final long[] TARGETS = {
        0x40005030L,
        0x40025288L,
        0x4002ef28L,
        0x4002f2f8L,
        0x400866c4L,
        0x40089940L,
        0x4008b8d0L,
        0x40099148L
    };

    @Override
    public void run() throws Exception {
        for (long addr : TARGETS) {
            decompileAndScanStrings(addr);
        }
        println("");
        println("=== GhidraResolve16 complete ===");
    }

    private void decompileAndScanStrings(long addrLong) throws Exception {
        Address addr = toAddr(addrLong);
        FunctionManager fm = currentProgram.getFunctionManager();
        Function f = fm.getFunctionAt(addr);

        println("");
        println("=========================================================");
        if (f == null) {
            println("No function defined at " + addr + " -- skipping.");
            return;
        }
        println(f.getName() + " @ " + addr
            + "  (body size: " + f.getBody().getNumAddresses() + " bytes)");
        println("=========================================================");

        // Decompile
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

        // Scan this function's instructions for references to string data
        println("--- String references touched by this function ---");
        Listing listing = currentProgram.getListing();
        ReferenceManager refMgr = currentProgram.getReferenceManager();
        InstructionIterator it = listing.getInstructions(f.getBody(), true);
        int stringHits = 0;

        while (it.hasNext()) {
            Instruction insn = it.next();
            Address at = insn.getAddress();
            Reference[] refsFrom = refMgr.getReferencesFrom(at);
            for (Reference r : refsFrom) {
                Address to = r.getToAddress();
                Data data = listing.getDataAt(to);
                if (data != null) {
                    DataType dt = data.getDataType();
                    if (dt != null && dt.getName() != null
                        && dt.getName().toLowerCase().contains("string")) {
                        Object val = data.getValue();
                        println("  " + at + " -> " + to + "  string: \""
                            + (val != null ? val.toString() : data.toString()) + "\"");
                        stringHits++;
                    }
                }
            }
        }
        if (stringHits == 0) {
            println("  (none found)");
        }
    }
}
