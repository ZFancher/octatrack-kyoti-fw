// GhidraResolve21.java
//
// Course correction: candidate_400a1f2e and everything downstream of it
// (FUN_4009d1e8, FUN_4009cf4c, FUN_400a1608, FUN_4009f794) turned out to
// be the arp key-scale quantizer subsystem -- leftover breadcrumbs from
// the earlier, abandoned "MIDI PLAYS FREE + SCALE PER TRACK" investigation,
// not our current DIRECT+Free bug.
//
// The one solid unexplored thread: FUN_40097924 is a small, clean, real
// function -- the confirmed audio-side manual-trig gate (checks machine
// state + event type + the 0x2b/manual-flag combo, then calls
// trig_to_voice). We've never looked at what CALLS FUN_40097924. That
// caller is likely the actual per-track-type dispatcher -- the real fork
// between audio and MIDI handling for manual trig key presses.
//
// This script finds and decompiles all callers of FUN_40097924.

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

import java.util.LinkedHashSet;
import java.util.Set;

public class GhidraResolve21 extends GhidraScript {

    private static final long TARGET = 0x40097924L; // FUN_40097924, audio manual-trig gate

    @Override
    public void run() throws Exception {
        Address addr = toAddr(TARGET);
        FunctionManager fm = currentProgram.getFunctionManager();
        ReferenceManager refMgr = currentProgram.getReferenceManager();

        println("=========================================================");
        println("Callers of FUN_40097924 (confirmed audio manual-trig gate)");
        println("=========================================================");

        Set<Long> callerFunctionAddrs = new LinkedHashSet<>();

        ReferenceIterator refIter = refMgr.getReferencesTo(addr);
        int count = 0;
        while (refIter.hasNext()) {
            Reference r = refIter.next();
            Address from = r.getFromAddress();
            Function containing = fm.getFunctionContaining(from);
            String containingName = (containing != null) ? containing.getName() : "(no containing function)";
            println("  from " + from + "  type=" + r.getReferenceType() + "  in function: " + containingName);
            if (containing != null) {
                callerFunctionAddrs.add(containing.getEntryPoint().getOffset());
            }
            count++;
        }

        if (count == 0) {
            println("  No references found -- reached via computed/table call.");
            println("");
            println("=== GhidraResolve21 complete (nothing to decompile) ===");
            return;
        }

        println("");
        println("Found " + count + " reference(s), " + callerFunctionAddrs.size() + " distinct containing function(s).");

        for (Long callerAddr : callerFunctionAddrs) {
            println("");
            println("=========================================================");
            println("Decompiling caller function @ 0x" + Long.toHexString(callerAddr));
            println("=========================================================");
            decompile(callerAddr);
        }

        println("");
        println("=== GhidraResolve21 complete ===");
    }

    private void decompile(long addrLong) throws Exception {
        Address addr = toAddr(addrLong);
        FunctionManager fm = currentProgram.getFunctionManager();
        Function f = fm.getFunctionAt(addr);

        if (f == null) {
            println("No function defined at " + addr + " -- skipping.");
            return;
        }
        println("--- " + f.getName() + " @ " + addr
            + "  (body size: " + f.getBody().getNumAddresses() + " bytes)");

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
}
