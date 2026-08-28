// GhidraResolve33.java
// Save as tools/GhidraResolve33.java.
//
// Session 4 continued (part 6): decompile FUN_4009b95a -- the "normal, quantized,
// sequencer-stepping" fallthrough both FUN_4009b5c8 (start) and FUN_4009f3a4 (retrigger)
// defer to when NOT taking the DIRECT/not-stepping shortcut branch. This is the last
// undecompiled piece of the manual-trig dispatch chain. Also decompile FUN_4009b290 in full
// (only its one-line body was seen via GhidraResolve31's summary) in case it holds more than
// the simple active-flag check already noted, and re-dump FUN_40044584's raw disassembly
// around the 0/1/2 TRIG_MODE comparisons to double check no toggle/latch state is touched
// inline (vs. deferred into FUN_4009b95a) for value 0.
//
// Run headless (same pattern as GhidraResolve26-32):
//   export JAVA_HOME=<temurin21>; export PATH="$JAVA_HOME/bin:$PATH"
//   export GHIDRA_JAVA_OPTIONS="-Duser.name=kyoti_m4"
//   <ghidra>/support/analyzeHeadless ~/Documents/octamax/ghidra_project octamax \
//     -process "section_3_MAIN_OS.bin" -noanalysis \
//     -scriptPath ~/Documents/octamax/tools -postScript GhidraResolve33.java

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.address.Address;
import ghidra.util.task.ConsoleTaskMonitor;

public class GhidraResolve33 extends GhidraScript {
    public void run() throws Exception {
        String[] addrs = new String[] { "0x4009b95a", "0x4009b290" };

        DecompInterface decomp = new DecompInterface();
        decomp.openProgram(currentProgram);
        FunctionManager fm = currentProgram.getFunctionManager();

        for (String a : addrs) {
            Address addr = currentProgram.getAddressFactory().getAddress(a);
            Function f = fm.getFunctionContaining(addr);
            println("\n==================== " + a + " ====================");
            if (f == null) {
                println("No function contains this address. Trying disassemble+createFunction...");
                try {
                    disassemble(addr);
                    f = createFunction(addr, "candidate_" + a.replace("0x",""));
                } catch (Exception e) {
                    println("createFunction failed: " + e);
                }
            }
            if (f == null) { println("Still no function here."); continue; }
            println("Function: " + f.getName() + " Entry:" + f.getEntryPoint() + " Size:" + f.getBody().getNumAddresses());
            DecompileResults res = decomp.decompileFunction(f, 90, new ConsoleTaskMonitor());
            if (res != null && res.decompileCompleted()) {
                println(res.getDecompiledFunction().getC());
            } else {
                println("Decompile failed: " + (res != null ? res.getErrorMessage() : "null"));
            }
        }

        // Raw disasm of FUN_40044584 around the TRIG_MODE compares, for ground truth on value 0.
        println("\n==== Raw disasm of FUN_40044584 (full) ====");
        Address fa = currentProgram.getAddressFactory().getAddress("0x40044584");
        Function ff = fm.getFunctionContaining(fa);
        if (ff != null) {
            Instruction insn = getInstructionAt(ff.getEntryPoint());
            while (insn != null && ff.getBody().contains(insn.getAddress())) {
                println("  " + insn.getAddress() + "  " + insn.toString());
                insn = getInstructionAfter(insn.getAddress());
            }
        }

        decomp.dispose();
        println("\nDone.");
    }
}
