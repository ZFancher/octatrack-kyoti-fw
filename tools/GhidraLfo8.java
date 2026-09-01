//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraLfo8 extends GhidraScript {
    byte[] img; long base = 0x40000400L;
    DecompInterface dec;
    Set<Long> done = new HashSet<>();
    long rd32(long va){ int i=(int)(va-base); return ((img[i]&0xffL)<<24)|((img[i+1]&0xffL)<<16)|((img[i+2]&0xffL)<<8)|(img[i+3]&0xffL); }
    void dump(Function f) throws Exception {
        if (f == null || !done.add(f.getEntryPoint().getOffset())) return;
        println("\n##################### " + f.getName() + " @" + f.getEntryPoint()
            + "  (" + f.getBody().getNumAddresses() + "B) #####################");
        DecompileResults dr = dec.decompileFunction(f, 200, new ConsoleTaskMonitor());
        if (dr != null && dr.getDecompiledFunction() != null) println(dr.getDecompiledFunction().getC());
        else println("  <decompile failed>");
    }
    void be32refs(long ndl, String tag) throws Exception {
        println("\n==== BE32 refs to " + tag + " 0x" + Long.toHexString(ndl) + " ====");
        for (int i = 0; i + 4 <= img.length; i++) {
            if (rd32(base+i) == ndl) {
                long va = base + i;
                Function cf = getFunctionContaining(toAddr(va));
                println("  @0x" + Long.toHexString(va) + (cf!=null?"  in "+cf.getName():"  (data/table)"));
                if (cf == null) {
                    // dump the table neighborhood: 16 dwords around
                    for (long p = va - 0x20; p <= va + 0x20; p += 4) {
                        long v = rd32(p);
                        Function tf = currentProgram.getFunctionManager().getFunctionAt(toAddr(v));
                        println(String.format("      [0x%08x] = 0x%08x %s", p, v, tf!=null?tf.getName():""));
                    }
                }
            }
        }
    }
    public void run() throws Exception {
        Memory mem = currentProgram.getMemory();
        img = new byte[(int)(0x4010fdefL - base)];
        mem.getBytes(toAddr(base), img);
        dec = new DecompInterface(); dec.openProgram(currentProgram);
        FunctionManager fm = currentProgram.getFunctionManager();

        be32refs(0x4007a2ecL, "FUN_4007a2ec (ARP SETUP enc handler)");
        be32refs(0x40079d48L, "FUN_40079d48 (MIDI setup renderer)");
        be32refs(0x400572e8L, "FUN_400572e8 (LFO SETUP renderer)");

        // functions around the LFO designer handlers 0x40037000-0x40039000
        println("\n==== functions 0x40037000..0x40039400 ====");
        for (Function f : fm.getFunctions(true)) {
            long e = f.getEntryPoint().getOffset();
            if (e >= 0x40037000L && e < 0x40039400L)
                println("  " + f.getEntryPoint() + "  " + f.getName() + "  (" + f.getBody().getNumAddresses() + "B)");
        }
    }
}
