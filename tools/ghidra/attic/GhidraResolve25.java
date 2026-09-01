// GhidraResolve25.java
// Save as tools/GhidraResolve25.java (new file, nothing to overwrite/confuse).
//
// FUN_40097924/trig_to_voice/FUN_40097168 are now confirmed audio-only
// (FUN_40000e50 returns a garbage pointer for any index >= 8), so we're done
// with that chain. This script instead lists every xref TO the MIDI-specific
// TRIG_MODE_MIDI field (found in the project-file parser: DAT_80000063[t] live,
// DAT_100b14c3[t] project-mirror, t = MIDI track index, values 0-5) and to the
// MIDI track state array from NOTES.md (0x80006500 / 0x800065b8). Whoever READS
// DAT_80000063 at runtime is our best lead yet for the actual, still-unmapped
// MIDI trig/sequencer dispatch code.
//
// Run headless (GUI fully quit first):
//   export PATH="/opt/homebrew/Cellar/openjdk@21/21.0.12/bin:$PATH"
//   export JAVA_HOME="/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home"
//   /opt/homebrew/Cellar/ghidra/12.1.2/libexec/support/analyzeHeadless \
//     ~/Documents/octamax/ghidra_project octamax \
//     -process "section_3_MAIN_OS.bin" \
//     -noanalysis \
//     -scriptPath ~/Documents/octamax/tools \
//     -postScript GhidraResolve25.java

import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.ReferenceManager;

public class GhidraResolve25 extends GhidraScript {

    private FunctionManager fm;

    private void dumpXrefs(String label, String addrStr) {
        Address addr = currentProgram.getAddressFactory().getAddress(addrStr);
        println("\n---- xrefs to " + label + " (" + addrStr + ") ----");

        ReferenceManager refMgr = currentProgram.getReferenceManager();
        ReferenceIterator it = refMgr.getReferencesTo(addr);
        int count = 0;
        while (it.hasNext()) {
            Reference ref = it.next();
            Address from = ref.getFromAddress();
            Function f = fm.getFunctionContaining(from);
            String fname = (f != null) ? (f.getName() + " @ " + f.getEntryPoint()) : "(no containing function)";
            println("  " + from + "  in " + fname + "  type=" + ref.getReferenceType());
            count++;
        }
        if (count == 0) {
            println("  (no references found)");
        }
    }

    public void run() throws Exception {
        fm = currentProgram.getFunctionManager();

        dumpXrefs("TRIG_MODE_MIDI (live)", "0x80000063");
        dumpXrefs("TRIG_MODE_MIDI (project mirror)", "0x100b14c3");
        dumpXrefs("MIDI track state array", "0x80006500");
        dumpXrefs("MIDI track state global", "0x800065b8");

        println("\nDone.");
    }
}
