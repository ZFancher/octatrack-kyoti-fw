// Find the arpeggiator key-scale quantizer.
// Anchors: "SCALE MODE" @0x400b7468, "CHROMATIC" @0x400b4f66, plus we scan for
// small note-remap tables. Prints referencing functions and decompiles them.
// @category Octatrack
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceManager;

public class GhidraArpScale extends ghidra.app.script.GhidraScript {
    long[] TARGETS = { 0x400d3fd9L, 0x400b72c7L, 0x400b7468L, 0x400b4f66L };

    public void run() throws Exception {
        DecompInterface dec = new DecompInterface();
        dec.openProgram(currentProgram);
        java.util.LinkedHashSet<Function> funcs = new java.util.LinkedHashSet<>();
        ReferenceManager rm = currentProgram.getReferenceManager();
        for (long t : TARGETS) {
            Address a = toAddr(t);
            println("=== refs to " + a + " ===");
            for (Reference r : rm.getReferencesTo(a)) {
                Address from = r.getFromAddress();
                Function f = getFunctionContaining(from);
                println("  from " + from + "  in " + (f == null ? "<none>" : f.getName() + " @" + f.getEntryPoint()));
                if (f != null) funcs.add(f);
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
