// GhidraResolve20.java
//
// candidate_400a1f2e calls FUN_400a1608 at 0x400a1f10, which is BEFORE its
// own listed entry point 0x400a1f2e -- impossible for a correctly-bounded
// function. Same symptom as FUN_4006e810 earlier this session (a boundary
// from a blind manual createFunction() call, not real analysis). Likely
// explains why it has zero found callers too -- we'd be searching xrefs to
// the wrong address.
//
// PART 1: reuse the proven backward-RTS-scan technique from GhidraResolve13
// to find the real start of this function and re-fix the boundary.
//
// PART 2: decompile FUN_4009d1e8 and FUN_4009cf4c -- the two per-track
// "fire the note" functions candidate_400a1f2e calls once each (for track
// ranges 0-7 and 8-15 respectively). These are much better candidates for
// where DIRECT-mode branching logic would actually live than the giant
// tick-dispatcher itself, since it showed no direct offset+0x129 hits.

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Parameter;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.ReferenceManager;

public class GhidraResolve20 extends GhidraScript {

    private static final long WRONG_START = 0x400a1f2eL; // candidate_400a1f2e
    private static final int MAX_STEPS_BACK = 4000;

    private static final long NOTE_EMIT_1 = 0x4009d1e8L;
    private static final long NOTE_EMIT_2 = 0x4009cf4cL;

    @Override
    public void run() throws Exception {
        println("=========================================================");
        println("PART 1: Fixing candidate_400a1f2e's boundary (same method as GhidraResolve13)");
        println("=========================================================");
        fixBoundary();

        println("");
        println("=========================================================");
        println("PART 2: Decompiling FUN_4009d1e8 (note-emit, tracks 0-7)");
        println("=========================================================");
        decompile(NOTE_EMIT_1);

        println("");
        println("=========================================================");
        println("PART 3: Decompiling FUN_4009cf4c (note-emit, tracks 8-15)");
        println("=========================================================");
        decompile(NOTE_EMIT_2);

        println("");
        println("=== GhidraResolve20 complete ===");
    }

    private void fixBoundary() throws Exception {
        FunctionManager fm = currentProgram.getFunctionManager();
        Listing listing = currentProgram.getListing();

        Address wrongStart = toAddr(WRONG_START);
        Function wrongFunc = fm.getFunctionAt(wrongStart);

        if (wrongFunc == null) {
            println("No function currently defined at " + wrongStart + " -- nothing to fix.");
            return;
        }
        println("Found suspect function '" + wrongFunc.getName() + "' at " + wrongStart);

        Instruction insn = listing.getInstructionBefore(wrongStart);
        Address rtsAddr = null;
        int stepsBack = 0;

        while (insn != null && stepsBack < MAX_STEPS_BACK) {
            String mnem = insn.getMnemonicString();
            if (mnem != null && mnem.equalsIgnoreCase("rts")) {
                rtsAddr = insn.getAddress();
                break;
            }
            insn = listing.getInstructionBefore(insn.getAddress());
            stepsBack++;
        }

        if (rtsAddr == null) {
            println("No RTS found scanning back " + stepsBack + " instructions -- aborting, no changes.");
            return;
        }
        println("Nearest preceding RTS at " + rtsAddr + " (" + stepsBack + " instructions back).");

        Instruction afterRts = listing.getInstructionAfter(rtsAddr);
        if (afterRts == null) {
            println("No instruction follows the RTS -- aborting, no changes.");
            return;
        }
        Address correctedStart = afterRts.getAddress();
        println("Candidate corrected function start: " + correctedStart);

        if (correctedStart.equals(wrongStart)) {
            println("Corrected start equals original -- boundary was already correct. No changes.");
            return;
        }

        boolean removed = fm.removeFunction(wrongStart);
        println("Removed old function at " + wrongStart + ": " + removed);

        Function existing = fm.getFunctionAt(correctedStart);
        Function resultFunc;
        if (existing != null) {
            println("A function already exists at corrected address " + correctedStart
                + ": '" + existing.getName() + "' -- using it instead of creating new.");
            resultFunc = existing;
        } else {
            resultFunc = createFunction(correctedStart, null);
            if (resultFunc == null) {
                println("createFunction() FAILED at " + correctedStart + ". No further action.");
                return;
            }
            println("Created corrected function '" + resultFunc.getName() + "' at " + correctedStart);
        }

        // Re-check callers of the corrected address now that the boundary is fixed
        println("");
        println("--- Callers of corrected function at " + correctedStart + " ---");
        ReferenceManager refMgr = currentProgram.getReferenceManager();
        ReferenceIterator refIter = refMgr.getReferencesTo(correctedStart);
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
            println("  Still no references found -- may need full re-analysis pass, or is reached via a table.");
        }
    }

    private void decompile(long addrLong) throws Exception {
        Address addr = toAddr(addrLong);
        FunctionManager fm = currentProgram.getFunctionManager();
        Function f = fm.getFunctionAt(addr);

        println("");
        if (f == null) {
            println("No function defined at " + addr + " -- skipping decompile.");
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
