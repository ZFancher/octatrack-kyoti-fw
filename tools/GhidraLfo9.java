//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraLfo9 extends GhidraScript {
    byte[] img; long base = 0x40000400L;
    DecompInterface dec;
    Set<Long> done = new HashSet<>();
    long rd32(long va){ int i=(int)(va-base); return ((img[i]&0xffL)<<24)|((img[i+1]&0xffL)<<16)|((img[i+2]&0xffL)<<8)|(img[i+3]&0xffL); }
    void dump(long e) throws Exception {
        Function f = currentProgram.getFunctionManager().getFunctionAt(toAddr(e));
        if (f == null){ println("\n// no func @0x"+Long.toHexString(e)); return; }
        if (!done.add(e)) return;
        println("\n##################### " + f.getName() + " @" + f.getEntryPoint()
            + "  (" + f.getBody().getNumAddresses() + "B) #####################");
        DecompileResults dr = dec.decompileFunction(f, 220, new ConsoleTaskMonitor());
        if (dr != null && dr.getDecompiledFunction() != null) println(dr.getDecompiledFunction().getC());
        else println("  <decompile failed>");
    }
    void callers(long e) throws Exception {
        Function f = currentProgram.getFunctionManager().getFunctionAt(toAddr(e));
        println("\n=== callers of " + (f!=null?f.getName():Long.toHexString(e)) + " ===");
        if (f==null) return;
        for (Function c : f.getCallingFunctions(new ConsoleTaskMonitor()))
            println("  " + c.getName() + " @" + c.getEntryPoint() + " (" + c.getBody().getNumAddresses() + "B)");
    }
    public void run() throws Exception {
        Memory mem = currentProgram.getMemory();
        img = new byte[(int)(0x4010fdefL - base)];
        mem.getBytes(toAddr(base), img);
        dec = new DecompInterface(); dec.openProgram(currentProgram);

        // full LFO SETUP page descriptor dump 0x400bbfe0..0x400bc0a0
        println("==== LFO SETUP page descriptor region ====");
        for (long p = 0x400bc02cL; p <= 0x400bc084L; p += 4) {
            long v = rd32(p);
            Function tf = currentProgram.getFunctionManager().getFunctionAt(toAddr(v));
            println(String.format("  [0x%08x] = 0x%08x  %s", p, v, tf!=null?tf.getName():""));
        }
        dump(0x40058390L);
        dump(0x40055de0L);
        dump(0x4003ad8cL);
        callers(0x40058390L);
        callers(0x40055de0L);
        // also the ARP setup two handlers for comparison (from MIDI page +0x18/+0x1c were 0x40079c28/abc)
        dump(0x40079abcL);
        dump(0x40079c28L);
    }
}
