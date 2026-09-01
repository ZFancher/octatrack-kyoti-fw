// Decompile project-name accessors + map FS vtable slot usage across binary.
// @category Octatrack
import ghidra.app.decompiler.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;

public class GhidraFsName extends ghidra.app.script.GhidraScript {
    long[] FUNCS = { 0x40025848L, 0x400255ecL, 0x40024eecL, 0x4002574cL, 0x40025230L };
    // FS vtable slots -> find every referencing instruction (which fn uses which slot)
    long[] VTAB = { 0x46c823faL, 0x46c82402L, 0x46c82406L, 0x46c8240aL, 0x46c8240eL,
                    0x46c82412L, 0x46c82416L, 0x46c8241aL, 0x46c8241eL, 0x46c82422L,
                    0x46c82426L, 0x46c8242aL, 0x46c8242eL, 0x46c82432L, 0x46c82436L };

    public void run() throws Exception {
        DecompInterface dec = new DecompInterface();
        dec.openProgram(currentProgram);
        for (long fa : FUNCS) {
            Function f = getFunctionAt(toAddr(fa));
            if (f == null) { println("NO FUNC @" + toAddr(fa)); continue; }
            println("\n##### " + f.getName() + " @" + f.getEntryPoint() + " #####");
            DecompileResults dr = dec.decompileFunction(f, 90, monitor);
            if (dr != null && dr.getDecompiledFunction() != null)
                println(dr.getDecompiledFunction().getC());
        }
        ReferenceManager rm = currentProgram.getReferenceManager();
        println("\n===== FS vtable slot references =====");
        for (long v : VTAB) {
            Address a = toAddr(v);
            println("--- slot " + a + " ---");
            for (Reference r : rm.getReferencesTo(a)) {
                Function f = getFunctionContaining(r.getFromAddress());
                println("  " + r.getFromAddress() + " [" + r.getReferenceType() + "] "
                        + (f==null?"<none>":f.getName()+"@"+f.getEntryPoint()));
            }
        }
    }
}
