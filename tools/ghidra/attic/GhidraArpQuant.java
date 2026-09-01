// Investigate ARP key-scale quantizer.
// 1) Decompile ARP SETUP renderer FUN_40079d48.
// 2) Find refs to anchor strings & decompile referrers.
// @category Octatrack
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceManager;

public class GhidraArpQuant extends ghidra.app.script.GhidraScript {
    DecompInterface dec;

    void dumpFunc(Function f) throws Exception {
        println("\n########## " + f.getName() + " @" + f.getEntryPoint() + " ##########");
        DecompileResults dr = dec.decompileFunction(f, 120, monitor);
        if (dr != null && dr.getDecompiledFunction() != null)
            println(dr.getDecompiledFunction().getC());
        else
            println("  <decompile failed>");
    }

    public void run() throws Exception {
        dec = new DecompInterface();
        dec.openProgram(currentProgram);

        // 1) The ARP SETUP renderer
        Function setup = getFunctionAt(toAddr(0x40079d48L));
        if (setup == null) setup = getFunctionContaining(toAddr(0x40079d48L));
        if (setup != null) dumpFunc(setup);
        else println("!! no function at 40079d48");

        // 2) referrers of anchor strings
        long[] strs = { 0x400b7468L /*SCALE MODE*/, 0x400b4f66L /*CHROMATIC*/, 0x400b72c7L /*MIDI ARP SETUP*/ };
        ReferenceManager rm = currentProgram.getReferenceManager();
        java.util.LinkedHashSet<Function> funcs = new java.util.LinkedHashSet<>();
        for (long t : strs) {
            Address a = toAddr(t);
            println("\n=== refs to " + a + " ===");
            for (Reference r : rm.getReferencesTo(a)) {
                Function f = getFunctionContaining(r.getFromAddress());
                println("  from " + r.getFromAddress() + " in " + (f==null?"<none>":f.getName()+" @"+f.getEntryPoint()));
                if (f != null) funcs.add(f);
            }
        }
        for (Function f : funcs) dumpFunc(f);
    }
}
