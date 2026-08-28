// GhidraResolve26.java
// Save as tools/GhidraResolve26.java.
//
// FUN_400a1eea showed up as by far the heaviest user of the MIDI track state
// array (0x80006500 / 0x800065b8) - 20 references, spanning ~0x2c00 bytes. This
// is the same function the original session flagged as suspicious ("fragment of
// a still-larger function") while chasing the arp-quantizer dead end, but it was
// never actually decompiled/investigated on its own merits. Given how central it
// is to MIDI track state, it's now the leading candidate for the real MIDI
// trig/sequencer engine.
//
// Also decompiling two small functions that directly READ TRIG_MODE_MIDI at
// runtime (not at project load/save), plus one more MIDI-state toucher:
//   0x400a1eea - heaviest user of MIDI track state array (main target)
//   0x4000db98 - reads TRIG_MODE_MIDI live value
//   0x4007c37c - reads TRIG_MODE_MIDI live value
//   0x400a0570 - touches both MIDI track state array and MIDI global
//
// Run headless (GUI fully quit first):
//   export PATH="/opt/homebrew/Cellar/openjdk@21/21.0.12/bin:$PATH"
//   export JAVA_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home"
//   /opt/homebrew/Cellar/ghidra/12.1.2/libexec/support/analyzeHeadless \
//     ~/Documents/octamax/ghidra_project octamax \
//     -process "section_3_MAIN_OS.bin" \
//     -noanalysis \
//     -scriptPath ~/Documents/octamax/tools \
//     -postScript GhidraResolve26.java

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.address.Address;
import ghidra.util.task.ConsoleTaskMonitor;

public class GhidraResolve26 extends GhidraScript {
    public void run() throws Exception {
        String[] addrs = new String[] {
            "0x400a1eea", // heaviest MIDI-track-state user - main target
            "0x4000db98", // reads TRIG_MODE_MIDI live
            "0x4007c37c", // reads TRIG_MODE_MIDI live
            "0x400a0570", // touches MIDI state array + global
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
            println("Function: " + f.getName() + "  Signature: " + f.getSignature()
                + "  Body size: " + f.getBody().getNumAddresses() + " bytes");

            DecompileResults res = decomp.decompileFunction(f, 90, new ConsoleTaskMonitor());
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
