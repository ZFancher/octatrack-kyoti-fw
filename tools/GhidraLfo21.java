//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraLfo21 extends GhidraScript {
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
        DecompileResults dr = dec.decompileFunction(f, 240, new ConsoleTaskMonitor());
        if (dr != null && dr.getDecompiledFunction() != null) println(dr.getDecompiledFunction().getC());
        else println("  <decompile failed>");
    }
    void words(long a, int n){
        for (long p=a; p<a+n; p+=4) {
            long v=rd32(p);
            Function tf=currentProgram.getFunctionManager().getFunctionAt(toAddr(v));
            String s=""; if(v>=base&&v<0x4010f000L){int j=(int)(v-base);StringBuilder sb=new StringBuilder();for(int k=0;k<20&&img[j+k]>=0x20&&img[j+k]<0x7f;k++)sb.append((char)img[j+k]);s=sb.toString();}
            println(String.format("  [0x%08x] = 0x%08x  %s %s", p, v, tf!=null?tf.getName():"", s));
        }
    }
    public void run() throws Exception {
        Memory mem = currentProgram.getMemory();
        img = new byte[(int)(0x4010fdefL - base)];
        mem.getBytes(toAddr(base), img);
        dec = new DecompInterface(); dec.openProgram(currentProgram);
        println("==== 0x400c07c0..0x400c08f0 (region around the FUN_40055008 handler table) ====");
        words(0x400c07c0L, 0x130);
        // FUN_40055008 handler-table entries reference DAT_400a72a8 via _DAT_460d1684.
        // Find the page whose descriptor points at 0x400c07c0-ish. Scan any pointer 0x400c0770..0x400c0850
        println("\n==== pointers into 0x400c0770..0x400c0850 ====");
        Set<Long> fns = new LinkedHashSet<>();
        for (int i=0;i+4<=img.length;i++){ long v=rd32(base+i); if(v>=0x400c0770L&&v<0x400c0850L){ long va=base+i; Function cf=getFunctionContaining(toAddr(va)); println(String.format("  @0x%08x -> 0x%08x %s",va,v,cf!=null?"in "+cf.getName():"(data)")); if(cf!=null)fns.add(cf.getEntryPoint().getOffset()); }}
        for (long e: fns) dump(e);
        // also the LEVEL-encoder handler + neighbours that call FUN_40033e3c
        dump(0x4004eb24L);
        dump(0x40052944L);
        dump(0x4005e0e8L);
        dump(0x4005e294L);
    }
}
