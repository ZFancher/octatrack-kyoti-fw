// GhidraResolve31.java
// Save as tools/GhidraResolve31.java.
//
// FUN_4009f3a4's callers (GhidraResolve30) include a path in FUN_4000db98 that reads
// SCALE_MODE (+0x48fd) and only tail-calls FUN_4009f3a4 when SCALE_MODE == 2 specifically
// (0 and 1 both skip it) -- but our confirmed test-pair diff only showed SCALE_MODE flip
// 0x00 -> 0x01 for "per track" mode. Need the full picture: decompile the biggest caller
// (FUN_40044584, 4 call sites gated on comparisons against 0/1/2) and FUN_4000e018 (another
// caller in the same neighborhood as FUN_4000db98) to see whether value 1 has its own
// separate path into FUN_4009f3a4/FUN_4009b290, or whether this specific dispatcher only
// cares about distinguishing 2 from everything else.
//
// Run headless (same pattern as GhidraResolve26-30):
//   export JAVA_HOME=<temurin21>; export PATH="$JAVA_HOME/bin:$PATH"
//   export GHIDRA_JAVA_OPTIONS="-Duser.name=kyoti_m4"
//   <ghidra>/support/analyzeHeadless ~/Documents/octamax/ghidra_project octamax \
//     -process "section_3_MAIN_OS.bin" -noanalysis \
//     -scriptPath ~/Documents/octamax/tools -postScript GhidraResolve31.java

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.address.Address;
import ghidra.util.task.ConsoleTaskMonitor;

public class GhidraResolve31 extends GhidraScript {
    public void run() throws Exception {
        String[] addrs = new String[] { "0x40044584", "0x4000e018", "0x4009b290" };

        DecompInterface decomp = new DecompInterface();
        decomp.openProgram(currentProgram);
        FunctionManager fm = currentProgram.getFunctionManager();

        for (String a : addrs) {
            Address addr = currentProgram.getAddressFactory().getAddress(a);
            Function f = fm.getFunctionContaining(addr);
            println("\n==================== " + a + " ====================");
            if (f == null) { println("No function here."); continue; }
            println("Function: " + f.getName() + " Entry:" + f.getEntryPoint() + " Size:" + f.getBody().getNumAddresses());
            DecompileResults res = decomp.decompileFunction(f, 90, new ConsoleTaskMonitor());
            if (res != null && res.decompileCompleted()) {
                println(res.getDecompiledFunction().getC());
            } else {
                println("Decompile failed: " + (res != null ? res.getErrorMessage() : "null"));
            }
        }
        decomp.dispose();
        println("\nDone.");
    }
}
