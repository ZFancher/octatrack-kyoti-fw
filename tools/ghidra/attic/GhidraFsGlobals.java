// Investigate project-name / base-path globals, FS vtable, dir-exists + enumeration primitives.
// @category Octatrack
import ghidra.app.decompiler.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.data.StringDataType;

public class GhidraFsGlobals extends ghidra.app.script.GhidraScript {
    // globals to find refs to (who reads/writes them)
    long[] DATA = { 0x100f8378L, 0x100f8480L, 0x460bf112L };
    // FS vtable slots (from context + decomp): print the pointer values stored there
    long[] VTAB = { 0x46c823faL, 0x46c8242aL, 0x46c82426L, 0x46c82402L, 0x46c8242eL,
                    0x46c82406L, 0x46c8240aL, 0x46c8240eL, 0x46c82412L, 0x46c82416L,
                    0x46c8241aL, 0x46c8241eL, 0x46c82422L, 0x46c82432L, 0x46c82436L };

    public void run() throws Exception {
        ReferenceManager rm = currentProgram.getReferenceManager();
        for (long d : DATA) {
            Address a = toAddr(d);
            println("=== refs to " + a + " ===");
            for (Reference r : rm.getReferencesTo(a)) {
                Function f = getFunctionContaining(r.getFromAddress());
                println("  " + r.getFromAddress() + " [" + r.getReferenceType() + "] "
                        + (f==null?"<none>":f.getName()+"@"+f.getEntryPoint()));
            }
            // pointer-literal search (indirect)
            String pat = String.format("%08x", d);
            for (Address da : findBytes(null, pat, 30)) {
                for (Reference r : rm.getReferencesTo(da)) {
                    Function f = getFunctionContaining(r.getFromAddress());
                    println("  (via ptr@"+da+") " + r.getFromAddress() + " "
                            + (f==null?"<none>":f.getName()+"@"+f.getEntryPoint()));
                }
            }
        }
        // Print what the vtable slots point to (these are RAM 0x46c8xxxx; may be uninit in binary)
        println("\n=== FS vtable slot contents (BE 4-byte) ===");
        for (long v : VTAB) {
            Address a = toAddr(v);
            try {
                int val = getInt(a);
                Symbol s = getSymbolAt(toAddr(val & 0xffffffffL));
                println(String.format("  [%08x] = %08x  %s", v, val & 0xffffffffL,
                        s==null?"":s.getName()));
            } catch (Exception e) { println(String.format("  [%08x] = <unreadable>", v)); }
        }
    }
}
