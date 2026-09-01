//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraLfo11 extends GhidraScript {
    byte[] img; long base = 0x40000400L;
    DecompInterface dec;
    Set<Long> done = new HashSet<>();
    long rd32(long va){ int i=(int)(va-base); return ((img[i]&0xffL)<<24)|((img[i+1]&0xffL)<<16)|((img[i+2]&0xffL)<<8)|(img[i+3]&0xffL); }
    void ensure(long e) throws Exception { Address a=toAddr(e); if(getInstructionAt(a)==null) disassemble(a); if(getFunctionAt(a)==null) createFunction(a,null); }
    void dump(long e) throws Exception {
        ensure(e);
        Function f = getFunctionAt(toAddr(e));
        if (f == null || !done.add(e)){ if(f==null) println("// no func @0x"+Long.toHexString(e)); return; }
        println("\n##################### " + f.getName() + " @" + f.getEntryPoint()
            + "  (" + f.getBody().getNumAddresses() + "B) #####################");
        DecompileResults dr = dec.decompileFunction(f, 240, new ConsoleTaskMonitor());
        if (dr != null && dr.getDecompiledFunction() != null) println(dr.getDecompiledFunction().getC());
        else println("  <decompile failed>");
    }
    void callers(long e) throws Exception {
        ensure(e);
        Function f = getFunctionAt(toAddr(e));
        println("\n=== callers of " + (f!=null?f.getName():Long.toHexString(e)) + " ===");
        if (f==null) return;
        for (Function c : f.getCallingFunctions(new ConsoleTaskMonitor()))
            println("  " + c.getName() + " @" + c.getEntryPoint() + " (" + c.getBody().getNumAddresses() + "B)");
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

        println("==== param descriptor table: audio LFO setup @0x400d37f6 ====");
        hexrow(0x400d37d0L, 0x80);
        println("\n==== param descriptor table: MIDI LFO setup @0x400d4162 ====");
        hexrow(0x400d4140L, 0x80);

        dump(0x400326d4L);       // installs page param table
        callers(0x400326d4L);
        dump(0x40031da4L);       // read param value for display
        dump(0x4003bf64L);
        callers(0x40031da4L);

        // The generic encoder handler should reference the installed table var written by FUN_400326d4.
    }
}
