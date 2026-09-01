// GhidraResolve13.java
//
// Purpose: fix the incorrect function boundary at 0x4006e810 that was
// created earlier via a blind disassemble()+createFunction() call before
// full auto-analysis ever ran. Because Ghidra treats an explicit function
// boundary as authoritative, later auto-analysis (even the full pass with
// Decompiler Switch Analysis) did NOT correct it.
//
// Method: scan backward instruction-by-instruction from the wrong start
// address looking for the nearest preceding `rts`. In code like this
// (no inter-function padding), the instruction immediately after that
// `rts` is almost always the true start of the next function -- which is
// very likely our mis-bounded one. Remove the wrong function, create a
// new one at the corrected address, and decompile it. If analysis now
// reports real parameters (instead of unresolved unaff_A2/unaff_A6/unaff_D7),
// that's strong confirmation the fix worked.
//
// Deliberately conservative: makes NO changes at all if it can't find an
// RTS, if the "corrected" address is the same as the original, or if
// createFunction() fails outright. Every decision point prints its
// reasoning so the transcript is self-documenting.

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Parameter;

public class GhidraResolve13 extends GhidraScript {

    private static final long WRONG_START = 0x4006e810L;
    private static final int MAX_STEPS_BACK = 4000; // safety bound on the backward scan

    @Override
    public void run() throws Exception {
        FunctionManager fm = currentProgram.getFunctionManager();
        Listing listing = currentProgram.getListing();

        Address wrongStart = toAddr(WRONG_START);
        Function wrongFunc = fm.getFunctionAt(wrongStart);

        if (wrongFunc == null) {
            println("No function currently defined at " + wrongStart
                + " -- nothing to fix. Aborting with no changes.");
            return;
        }

        println("Found suspect function '" + wrongFunc.getName() + "' at " + wrongStart
            + " (params: " + wrongFunc.getParameterCount() + ")");

        // Scan backward for the nearest RTS instruction.
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
            println("Scanned back " + stepsBack + " instructions without finding an RTS "
                + "(limit " + MAX_STEPS_BACK + "). Aborting -- no changes made. "
                + "This function boundary may need a different resolution strategy.");
            return;
        }

        println("Nearest preceding RTS found at " + rtsAddr + " (" + stepsBack + " instructions back).");

        Instruction afterRts = listing.getInstructionAfter(rtsAddr);
        if (afterRts == null) {
            println("No instruction follows the RTS at " + rtsAddr + " -- aborting, no changes made.");
            return;
        }

        Address correctedStart = afterRts.getAddress();
        println("Candidate corrected function start: " + correctedStart);

        if (correctedStart.equals(wrongStart)) {
            println("Corrected start equals original start -- the existing boundary was "
                + "already correct after all. No changes made.");
            return;
        }

        // Remove the wrong function definition before redefining.
        boolean removed = fm.removeFunction(wrongStart);
        println("Removed incorrect function at " + wrongStart + ": " + removed);

        // If auto-analysis (or a prior script) already put a function at the
        // corrected address, don't clobber it -- just report and decompile it.
        Function existing = fm.getFunctionAt(correctedStart);
        if (existing != null) {
            println("A function ALREADY exists at the corrected address " + correctedStart
                + ": '" + existing.getName() + "'. Not calling createFunction() again -- "
                + "decompiling the existing one instead.");
            printDecompiled(existing);
            return;
        }

        Function newFunc = createFunction(correctedStart, null);
        if (newFunc == null) {
            println("createFunction() FAILED at " + correctedStart + ". This address may not "
                + "be a valid instruction boundary in Ghidra's current view, or disassembly "
                + "is required first. No further action taken -- the wrong function has "
                + "already been removed, so re-run analysis or a disassemble-first script "
                + "before retrying.");
            return;
        }

        println("Created corrected function '" + newFunc.getName() + "' at " + correctedStart);
        printDecompiled(newFunc);
    }

    private void printDecompiled(Function f) throws Exception {
        DecompInterface decomp = new DecompInterface();
        try {
            decomp.openProgram(currentProgram);
            DecompileResults res = decomp.decompileFunction(f, 60, monitor);

            if (res != null && res.decompileCompleted()) {
                println("=== Decompiled '" + f.getName() + "' @ " + f.getEntryPoint() + " ===");
                println("Parameter count: " + f.getParameterCount());
                for (Parameter p : f.getParameters()) {
                    println("  param: " + p.getName() + " : " + p.getDataType());
                }
                println(res.getDecompiledFunction().getC());
            } else {
                String msg = (res != null) ? res.getErrorMessage() : "null DecompileResults";
                println("Decompilation FAILED or did not complete for '" + f.getName() + "': " + msg);
            }
        } finally {
            decomp.dispose();
        }
    }
}
