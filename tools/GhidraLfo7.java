//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraLfo7 extends GhidraScript {
    byte[] img; long base = 0x40000400L;
    DecompInterface dec;
    Set<Long> done = new HashSet<>();
    void dump(Function f) throws Exception {
        if (f == null || !done.add(f.getEntryPoint().getOffset())) return;
        println("\n##################### " + f.getName() + " @" + f.getEntryPoint()
            + "  (" + f.getBody().getNumAddresses() + "B) #####################");
        DecompileResults dr = dec.decompileFunction(f, 200, new ConsoleTaskMonitor());
        if (dr != null && dr.getDecompiledFunction() != null) println(dr.getDecompiledFunction().getC());
        else println("  <decompile failed>");
    }
    public void run() throws Exception {
        Memory mem = currentProgram.getMemory();
        img = new byte[(int)(0x4010fdefL - base)];
        mem.getBytes(toAddr(base), img);
        dec = new DecompInterface(); dec.openProgram(currentProgram);
        FunctionManager fm = currentProgram.getFunctionManager();

        // any BE32 pointer landing in the LFO-setup label/descriptor windows
        long[][] windows = { {0x400d37f0L, 0x400d3860L}, {0x400d4150L, 0x400d41c0L} };
        Set<Long> hitFns = new LinkedHashSet<>();
        for (long[] w : windows) {
            println("\n==== BE32 pointers into 0x" + Long.toHexString(w[0]) + "..0x" + Long.toHexString(w[1]) + " ====");
            for (int i = 0; i + 4 <= img.length; i++) {
                long v = ((img[i]&0xffL)<<24)|((img[i+1]&0xffL)<<16)|((img[i+2]&0xffL)<<8)|(img[i+3]&0xffL);
                if (v >= w[0] && v < w[1]) {
                    long va = base + i;
                    Function cf = getFunctionContaining(toAddr(va));
                    println("  @0x" + Long.toHexString(va) + " -> 0x" + Long.toHexString(v)
                        + (cf != null ? "  in " + cf.getName() : "  (data)"));
                    if (cf != null) hitFns.add(cf.getEntryPoint().getOffset());
                }
            }
        }

        // FUN_4007a2ec (ARP SETUP encoder handler) + its function-list neighbors in 0x40079000-0x4007b800
        dump(fm.getFunctionAt(toAddr(0x4007a2ecL)));
        println("\n==== functions in 0x40079800..0x4007b800 ====");
        for (Function f : fm.getFunctions(true)) {
            long e = f.getEntryPoint().getOffset();
            if (e >= 0x40079800L && e < 0x4007b800L)
                println("  " + f.getEntryPoint() + "  " + f.getName() + "  (" + f.getBody().getNumAddresses() + "B)");
        }
        for (long e : hitFns) dump(fm.getFunctionAt(toAddr(e)));
    }
}
