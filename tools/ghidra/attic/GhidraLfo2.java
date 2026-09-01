//@category Octatrack
// LFO-CC bug pass 2. BE32-scan the image for pointers to the LFO SETUP anchors, then
// decompile the referencing functions and FUN_400572e8 (references "MIDI LFO SETUP").
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraLfo2 extends GhidraScript {
    byte[] img; long base = 0x40000400L;
    DecompInterface dec;

    List<Long> be32(long needle) {
        List<Long> out = new ArrayList<>();
        for (int i = 0; i + 4 <= img.length; i++) {
            long v = ((img[i]&0xffL)<<24)|((img[i+1]&0xffL)<<16)|((img[i+2]&0xffL)<<8)|(img[i+3]&0xffL);
            if (v == needle) out.add(base + i);
        }
        return out;
    }
    void scan(String label, long ndl) {
        println("\n==== BE32 refs to " + label + " 0x" + Long.toHexString(ndl) + " ====");
        for (long va : be32(ndl)) {
            Address at = toAddr(va);
            Function cf = getFunctionContaining(at);
            println("  @0x" + Long.toHexString(va) + (cf != null ? "  in " + cf.getName() + "@" + cf.getEntryPoint() : "  (no func)"));
        }
    }
    void dump(long entry) throws Exception {
        Function f = currentProgram.getFunctionManager().getFunctionAt(toAddr(entry));
        if (f == null) { println("\n// no function at 0x" + Long.toHexString(entry)); return; }
        println("\n##################### " + f.getName() + " @" + f.getEntryPoint()
            + "  (" + f.getBody().getNumAddresses() + "B) #####################");
        DecompileResults dr = dec.decompileFunction(f, 180, new ConsoleTaskMonitor());
        if (dr != null && dr.getDecompiledFunction() != null) println(dr.getDecompiledFunction().getC());
        else println("  <decompile failed>");
    }

    public void run() throws Exception {
        Memory mem = currentProgram.getMemory();
        img = new byte[(int)(0x4010fdefL - base)];
        mem.getBytes(toAddr(base), img);
        dec = new DecompInterface(); dec.openProgram(currentProgram);

        scan("\"MIDI LFO SETUP\"", 0x400b47d5L);
        scan("\"LFO SETUP\"", 0x400b47daL);
        scan("SPD1 cluster A", 0x400d380cL);
        scan("SPD1 cluster B", 0x400d4178L);
        scan("PMTR cluster A", 0x400d3830L);
        scan("PMTR cluster B", 0x400d419cL);

        // functions of interest
        dump(0x400572e8L);
        println("\n=== callers of FUN_400572e8 ===");
        Function f = currentProgram.getFunctionManager().getFunctionAt(toAddr(0x400572e8L));
        for (Function c : f.getCallingFunctions(new ConsoleTaskMonitor()))
            println("  " + c.getName() + " @" + c.getEntryPoint() + " (" + c.getBody().getNumAddresses() + "B)");
    }
}
