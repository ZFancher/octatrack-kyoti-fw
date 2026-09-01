// GhidraResolve34.java
// Session 4 continued (part 6, cont'd): DAT_80000012 (the outer gate in FUN_40044584 that
// decides whether the whole TRIG_MODE dispatch for MIDI tracks even runs, vs falling into an
// entirely different direct-MIDI-scheduling path at 0x40044710) has exactly ONE write site,
// in FUN_400866c4 -- the profile of a global project-level setting latched once (e.g. on
// project/pattern load), not a per-step/per-event flag. Strong candidate for being the REAL
// "track scale mode = per track" project setting the user named as bug precondition #3.
// Decompile FUN_400866c4 and print its callers to see what it reads/when it runs.
//
// Run headless (same pattern as GhidraResolve26-33):
//   export JAVA_HOME=<temurin21>; export PATH="$JAVA_HOME/bin:$PATH"
//   export GHIDRA_JAVA_OPTIONS="-Duser.name=kyoti_m4"
//   <ghidra>/support/analyzeHeadless ~/Documents/octamax/ghidra_project octamax \
//     -process "section_3_MAIN_OS.bin" -noanalysis \
//     -scriptPath ~/Documents/octamax/tools -postScript GhidraResolve34.java

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.util.task.ConsoleTaskMonitor;

public class GhidraResolve34 extends GhidraScript {
    public void run() throws Exception {
        FunctionManager fm = currentProgram.getFunctionManager();

        println("==== Callers of FUN_400866c4 (0x400866c4) ====");
        Address targetAddr = currentProgram.getAddressFactory().getAddress("0x400866c4");
        ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(targetAddr);
        while (refs.hasNext()) {
            Reference r = refs.next();
            Address from = r.getFromAddress();
            Function callerFunc = fm.getFunctionContaining(from);
            println("  from " + from + " in " + (callerFunc != null ? callerFunc.getName() + "@" + callerFunc.getEntryPoint() : "NOFUNC")
                + "  refType=" + r.getReferenceType());
        }

        println("\n==== FUN_400866c4 decompile ====");
        DecompInterface decomp = new DecompInterface();
        decomp.openProgram(currentProgram);
        Function f = fm.getFunctionContaining(targetAddr);
        if (f != null) {
            println("Function: " + f.getName() + " Entry:" + f.getEntryPoint() + " Size:" + f.getBody().getNumAddresses());
            DecompileResults res = decomp.decompileFunction(f, 90, new ConsoleTaskMonitor());
            if (res != null && res.decompileCompleted()) {
                println(res.getDecompiledFunction().getC());
            } else {
                println("Decompile failed: " + (res != null ? res.getErrorMessage() : "null"));
            }
        } else {
            println("No function at 0x400866c4");
        }
        decomp.dispose();
        println("\nDone.");
    }
}
