// Dump full instruction listing (addr: bytes: mnemonic) + decompile for the
// arp scale quantizer and its caller, to design the patch against real code.
// @category Octatrack
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.MemoryAccessException;

public class GhidraArpDump extends ghidra.app.script.GhidraScript {
    long[] FUNCS = { 0x4003b790L, 0x4007a2ecL };

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
                println(String.format("%s  %-16s  %s", a, b.toString(), ins.toString()));
            }
            println("\n----- decompile " + f.getName() + " -----");
            DecompileResults dr = dec.decompileFunction(f, 60, monitor);
            if (dr != null && dr.getDecompiledFunction() != null)
                println(dr.getDecompiledFunction().getC());
        }
    }
}
