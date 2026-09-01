// GhidraResolve23.java
//
// FUN_40083544 (the cVar1 != 4 branch) turned out to still be audio-side
// code -- it calls FUN_40005178, the documented audio voice-mailbox
// writer, and reads the same 0-4 machine-type byte FUN_40097168 checks.
// That means the cVar1==4 / cVar1!=4 fork in case 'D' likely is NOT the
// audio/MIDI fork we're after -- probably a fork within audio-track
// handling itself.
//
// FUN_40083480 gates on this exact same cVar1==4 condition elsewhere
// (seen in FUN_40030c60/FUN_40030e6c). It's small and directly relevant
// to pinning down what this byte actually represents before chasing
// anything else. Decompile it plus dump its callers for context.

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Parameter;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.ReferenceManager;

public class GhidraResolve23 extends GhidraScript {

    private static final long TARGET = 0x40083480L;

    @Override
    public void run() throws Exception {
        Address addr = toAddr(TARGET);
        FunctionManager fm = currentProgram.getFunctionManager();
        Function f = fm.getFunctionAt(addr);

        println("=========================================================");
        println("PART 1: Decompiling FUN_40083480");
        println("=========================================================");
        if (f == null) {
            println("No function defined at " + addr + " -- aborting.");
            return;
        }
        println("Body size: " + f.getBody().getNumAddresses() + " bytes");

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

        println("");
        println("=========================================================");
        println("PART 2: Callers of FUN_40083480");
        println("=========================================================");
        ReferenceManager refMgr = currentProgram.getReferenceManager();
        ReferenceIterator refIter = refMgr.getReferencesTo(addr);
        int count = 0;
        while (refIter.hasNext()) {
            Reference r = refIter.next();
            Address from = r.getFromAddress();
            Function containing = fm.getFunctionContaining(from);
            String containingName = (containing != null) ? containing.getName() : "(no containing function)";
            println("  from " + from + "  type=" + r.getReferenceType() + "  in function: " + containingName);
            count++;
        }
        if (count == 0) {
            println("  No references found.");
        }

        println("");
        println("=== GhidraResolve23 complete ===");
    }
}
