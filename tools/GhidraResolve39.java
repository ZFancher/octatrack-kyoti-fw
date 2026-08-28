// GhidraResolve39.java
// Session 5: prep for the emulation harness (emu_trigbug.py). Need exact ground truth on:
//   - FUN_40044584 (manual-trig dispatcher) entry: arg order, the _DAT_80000012 gate, every
//     function it calls, and its return path -- full decompile + full raw disasm.
//   - FUN_4009f2f8 (called by FUN_4009f3a4's MIDI clear branch) -- decompile.
//   - FUN_400a539c (per-track reset, both paths) -- decompile.
//   - FUN_40000c3c (event post) -- decompile + size, to decide whether to stub it in the emu.
//
// Run headless:
//   export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home
//   export PATH="$JAVA_HOME/bin:$PATH"
//   /opt/homebrew/Cellar/ghidra/12.1.2/libexec/support/analyzeHeadless \
//     ~/Documents/octamax/ghidra_project octamax -process "section_3_MAIN_OS.bin" -noanalysis \
//     -scriptPath ~/Documents/octamax/tools -postScript GhidraResolve39.java

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.address.Address;
import ghidra.util.task.ConsoleTaskMonitor;

public class GhidraResolve39 extends GhidraScript {
    DecompInterface decomp;
    FunctionManager fm;

    public void run() throws Exception {
        fm = currentProgram.getFunctionManager();
        decomp = new DecompInterface();
        decomp.openProgram(currentProgram);

        for (String a : new String[] { "0x40044584", "0x4009f2f8", "0x400a539c", "0x40000c3c" })
            decompileAt(a);

        rawDisasm("0x40044584");

        decomp.dispose();
        println("\nDone.");
    }

    void decompileAt(String a) {
        Address addr = currentProgram.getAddressFactory().getAddress(a);
        Function f = fm.getFunctionContaining(addr);
        println("\n==================== decompile " + a + " ====================");
        if (f == null) { println("No function at " + a); return; }
        println("Function: " + f.getName() + " Entry:" + f.getEntryPoint()
            + " Size(addrs):" + f.getBody().getNumAddresses()
            + " Body:" + f.getBody().getMinAddress() + ".." + f.getBody().getMaxAddress());
        DecompileResults res = decomp.decompileFunction(f, 180, new ConsoleTaskMonitor());
        if (res != null && res.decompileCompleted()) println(res.getDecompiledFunction().getC());
        else println("Decompile failed: " + (res != null ? res.getErrorMessage() : "null"));
    }

    void rawDisasm(String a) {
        Address addr = currentProgram.getAddressFactory().getAddress(a);
        Function f = fm.getFunctionContaining(addr);
        println("\n==================== raw disasm " + a + " ====================");
        if (f == null) { println("No function at " + a); return; }
        Address end = f.getBody().getMaxAddress();
        Instruction insn = getInstructionAt(f.getEntryPoint());
        while (insn != null && insn.getAddress().compareTo(end) <= 0) {
            StringBuilder sb = new StringBuilder();
            sb.append(insn.getAddress()).append("  ").append(insn.toString());
            Address[] flows = insn.getFlows();
            if (flows != null && flows.length > 0) { sb.append("   -> "); for (Address fl : flows) sb.append(fl).append(" "); }
            // annotate call targets
            String m = insn.getMnemonicString();
            if (m.startsWith("jsr") || m.startsWith("bsr")) {
                Address[] fl2 = insn.getFlows();
                if (fl2 != null) for (Address t : fl2) {
                    Function tf = fm.getFunctionContaining(t);
                    if (tf != null) sb.append("  ; ").append(tf.getName());
                }
            }
            println(sb.toString());
            insn = getInstructionAfter(insn.getAddress());
        }
    }
}
