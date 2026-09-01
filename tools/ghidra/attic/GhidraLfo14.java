//@category Octatrack
// Step 1-2: dump DAT_400a7280 remap table + param-id ranges, decompile the remaining
// CC-transmit siblings (FUN_400438fc, FUN_40055008, FUN_4009da20), and the FUN_400326d4
// caller for the LFO page to learn the exact paramId the LFO SETUP encoders feed FUN_40054cd8.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraLfo14 extends GhidraScript {
    byte[] img; long base = 0x40000400L;
    DecompInterface dec;
    Set<Long> done = new HashSet<>();
    void ensure(long e) throws Exception { Address a=toAddr(e); if(getInstructionAt(a)==null) disassemble(a); if(getFunctionAt(a)==null) createFunction(a,null); }
    void dump(long e) throws Exception {
        ensure(e);
        Function f = getFunctionAt(toAddr(e));
        if (f == null || !done.add(e)) return;
        println("\n##################### " + f.getName() + " @" + f.getEntryPoint()
            + "  (" + f.getBody().getNumAddresses() + "B) #####################");
        DecompileResults dr = dec.decompileFunction(f, 300, new ConsoleTaskMonitor());
        if (dr != null && dr.getDecompiledFunction() != null) println(dr.getDecompiledFunction().getC());
        else println("  <decompile failed>");
    }
    void hexrow(long a, int n) {
        for (long p=a; p<a+n; p+=16){
            StringBuilder h=new StringBuilder(String.format("  %08x  ",p)); StringBuilder s=new StringBuilder();
            for(int c=0;c<16&&p+c<a+n;c++){int b=img[(int)(p+c-base)]&0xff; h.append(String.format("%02x ",b)); s.append(b>=32&&b<127?(char)b:'.');}
            println(h+" "+s);
        }
    }
    public void run() throws Exception {
        Memory mem = currentProgram.getMemory();
        img = new byte[(int)(0x4010fdefL - base)];
        mem.getBytes(toAddr(base), img);
        dec = new DecompInterface(); dec.openProgram(currentProgram);

        println("==== DAT_400a7280 remap table (32 bytes) ====");
        hexrow(0x400a7280L, 0x20);
        println("\n==== bytes around 0x400a7280 (context) ====");
        hexrow(0x400a7260L, 0x60);

        dump(0x400438fcL);
        dump(0x40055008L);
        dump(0x4009da20L);
        // FUN_400a14f0 already have. Also the 'H'-case path FUN_40042158, FUN_4004f5f8 quick look
        dump(0x40042158L);
    }
}
