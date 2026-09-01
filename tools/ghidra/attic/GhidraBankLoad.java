// Find the bank load/reload code path and inspect whether it stops the sequencer/audio.
// Anchors: "RELOADING BANK" 0x400b3898, "RELOAD BANK" 0x400b39b7,
//          "RELOAD CUR BANK" 0x400b5e45, "WORKING, PLEASE WAIT" 0x400b68b2,
//          "PRELOAD" 0x400be7c5.
// Prints referencing functions and their decompilation (which reveals what they call:
// a sequencer-stop / audio-halt would show up as a call in the reload path).
// @category Octatrack
import ghidra.app.decompiler.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.*;

public class GhidraBankLoad extends ghidra.app.script.GhidraScript {
    long[] TARGETS = { 0x400b3898L, 0x400b39b7L, 0x400b5e45L, 0x400b68b2L, 0x400be7c5L };

    public void run() throws Exception {
        DecompInterface dec = new DecompInterface();
        dec.openProgram(currentProgram);
        ReferenceManager rm = currentProgram.getReferenceManager();
        java.util.LinkedHashSet<Function> funcs = new java.util.LinkedHashSet<>();
        for (long t : TARGETS) {
            Address a = toAddr(t);
            println("=== refs to " + a + " ===");
            for (Reference r : rm.getReferencesTo(a)) {
                Function f = getFunctionContaining(r.getFromAddress());
                println("  from " + r.getFromAddress() + "  in " + (f == null ? "<none>" : f.getName()));
                if (f != null) funcs.add(f);
            }
            // also look for a pointer to the string (indirect table use)
            for (Address da : findBytes(null, String.format("%02x%02x%02x%02x",
                    (t>>24)&0xff,(t>>16)&0xff,(t>>8)&0xff,t&0xff), 8)) {
                println("  ptr-literal at " + da);
            }
        }
        for (Function f : funcs) {
            println("\n########## " + f.getName() + " @" + f.getEntryPoint() + " ##########");
            DecompileResults dr = dec.decompileFunction(f, 60, monitor);
            if (dr != null && dr.getDecompiledFunction() != null)
                println(dr.getDecompiledFunction().getC());
        }
    }
}
