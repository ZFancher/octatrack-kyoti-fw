// GhidraResolve24.java
// Dumps full decompiled C for the functions we need before building the
// Unicorn emulator harness for the MIDI Plays-Free + Direct trig bug:
//   0x40097924 - manual-trig gate candidate (checks param_3==0x2b, param_4==1)
//   0x400977cc - trig_to_voice (confirmed audio trig->voice bridge)
//   0x40097168 - machine-state dispatch (Static/Flex/Thru/Neighbor/Pickup)
//   0x4006da78 - per-track pointer getter
//   0x400866c4 - project-file text parser (this is how +0x129/TRIGQUANTIZATION was
//                found last time; decompiling it in full should surface the field
//                name + offset for "Plays Free" the same way)
//
// Run headless, same pattern as GhidraResolve13-23:
//   export PATH="/opt/homebrew/Cellar/openjdk@21/21.0.12/bin:$PATH"
//   export JAVA_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home"
//   /opt/homebrew/Cellar/ghidra/12.1.2/libexec/support/analyzeHeadless \
//     ~/Documents/octamax/ghidra_project octamax \
//     -process "section_3_MAIN_OS.bin" \
//     -noanalysis \
//     -scriptPath ~/Documents/octamax/tools \
//     -postScript GhidraResolve24.java
//
// (Quit the Ghidra GUI fully before running this.)

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.address.Address;
import ghidra.util.task.ConsoleTaskMonitor;

public class GhidraResolve24 extends GhidraScript {
    public void run() throws Exception {
        String[] addrs = new String[] {
            "0x40097924", // manual-trig gate candidate
            "0x400977cc", // trig_to_voice
            "0x40097168", // machine-state dispatch
            "0x4006da78", // per-track pointer getter
            "0x400866c4", // project-file text parser (find the Plays-Free field name+offset)
            "0x40000e50", // record getter used by FUN_40097168 to compute machine-state
        };

        DecompInterface decomp = new DecompInterface();
        decomp.openProgram(currentProgram);

        FunctionManager fm = currentProgram.getFunctionManager();

        for (String a : addrs) {
            Address addr = currentProgram.getAddressFactory().getAddress(a);
            Function f = fm.getFunctionAt(addr);

            println("\n==================== " + a + " ====================");
            if (f == null) {
                println("No function defined at this address (bad boundary? check with GhidraResolve13/20-style backward-RTS scan).");
                continue;
            }
            println("Function: " + f.getName() + "  Signature: " + f.getSignature());

            DecompileResults res = decomp.decompileFunction(f, 60, new ConsoleTaskMonitor());
            if (res != null && res.decompileCompleted()) {
                println(res.getDecompiledFunction().getC());
            } else {
                println("Decompilation failed: " + (res != null ? res.getErrorMessage() : "null result"));
            }
        }

        decomp.dispose();
        println("\nDone.");
    }
}
