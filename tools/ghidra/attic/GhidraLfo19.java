//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraLfo19 extends GhidraScript {
    byte[] img; long base = 0x40000400L;
    DecompInterface dec;
    Set<Long> done = new HashSet<>();
    long rd32(long va){ int i=(int)(va-base); return ((img[i]&0xffL)<<24)|((img[i+1]&0xffL)<<16)|((img[i+2]&0xffL)<<8)|(img[i+3]&0xffL); }
    void ensure(long e) throws Exception { Address a=toAddr(e); if(getInstructionAt(a)==null) disassemble(a); if(getFunctionAt(a)==null) createFunction(a,null); }
    void dump(long e) throws Exception {
        ensure(e);
        Function f = getFunctionAt(toAddr(e));
        if (f == null || !done.add(e)) return;
        println("\n##################### " + f.getName() + " @" + f.getEntryPoint()
            + "  (" + f.getBody().getNumAddresses() + "B) #####################");
        DecompileResults dr = dec.decompileFunction(f, 260, new ConsoleTaskMonitor());
        if (dr != null && dr.getDecompiledFunction() != null) println(dr.getDecompiledFunction().getC());
        else println("  <decompile failed>");
    }
    void anyRefInRange(long lo, long hi, String tag) throws Exception {
        println("\n==== any BE32 pointer into 0x"+Long.toHexString(lo)+"..0x"+Long.toHexString(hi)+" ("+tag+") ====");
        Set<Long> fns = new LinkedHashSet<>();
        for (int i = 0; i + 4 <= img.length; i++) {
            long v = rd32(base+i);
            if (v >= lo && v < hi) {
                long va = base+i;
                Function cf = getFunctionContaining(toAddr(va));
                println(String.format("  @0x%08x -> 0x%08x  %s", va, v, cf!=null?"in "+cf.getName():"(data)"));
                if (cf != null) fns.add(cf.getEntryPoint().getOffset());
            }
        }
        for (long e : fns) dump(e);
    }
    public void run() throws Exception {
        Memory mem = currentProgram.getMemory();
        img = new byte[(int)(0x4010fdefL - base)];
        mem.getBytes(toAddr(base), img);
        dec = new DecompInterface(); dec.openProgram(currentProgram);
        // the FUN_40055008 per-encoder table sits ~0x400c0844.. ; find code that loads its base
        anyRefInRange(0x400c0820L, 0x400c0848L, "FUN_40055008 encoder table head");
        // the MIDI-mode flag setter
        dump(0x400866c4L);
    }
}
