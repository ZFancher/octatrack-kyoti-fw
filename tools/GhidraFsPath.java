// Decompile + listing for the project-path / bank-filename builder chain.
// @category Octatrack
import ghidra.app.decompiler.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.MemoryAccessException;

public class GhidraFsPath extends ghidra.app.script.GhidraScript {
    long[] FUNCS = {
        0x40025230L, // project dir builder
        0x400905d4L, // type-6 handler opens bankNN.work
        0x4008f0b0L, // type-0x14 copy strd->work
        0x4008ded0L, // deserialize into RAM
    };

    public void run() throws Exception {
        DecompInterface dec = new DecompInterface();
        dec.openProgram(currentProgram);
        Listing lst = currentProgram.getListing();
        for (long fa : FUNCS) {
            Function f = getFunctionAt(toAddr(fa));
            if (f == null) { println("NO FUNC @" + toAddr(fa)); continue; }
            println("\n================= " + f.getName() + " @" + f.getEntryPoint()
                    + "  body=" + f.getBody().getMinAddress() + ".." + f.getBody().getMaxAddress()
                    + "  frame=" + f.getStackFrame().getFrameSize() + " =================");
            InstructionIterator it = lst.getInstructions(f.getBody(), true);
            while (it.hasNext()) {
                Instruction ins = it.next();
                Address a = ins.getAddress();
                StringBuilder b = new StringBuilder();
                try { for (byte x : ins.getBytes()) b.append(String.format("%02x", x)); }
                catch (MemoryAccessException e) { b.append("??"); }
                // annotate referenced data / call targets
                String note = "";
                for (ghidra.program.model.symbol.Reference r : ins.getReferencesFrom()) {
                    Address ta = r.getToAddress();
                    ghidra.program.model.symbol.Symbol s = getSymbolAt(ta);
                    Data d = getDataAt(ta);
                    String extra = "";
                    if (d != null && d.hasStringValue()) extra = " \"" + d.getValue() + "\"";
                    note += "  -> " + ta + (s != null ? "(" + s.getName() + ")" : "") + extra;
                }
                println(String.format("%s  %-14s  %-28s%s", a, b.toString(), ins.toString(), note));
            }
            println("\n----- decompile " + f.getName() + " -----");
            DecompileResults dr = dec.decompileFunction(f, 90, monitor);
            if (dr != null && dr.getDecompiledFunction() != null)
                println(dr.getDecompiledFunction().getC());
        }
    }
}
