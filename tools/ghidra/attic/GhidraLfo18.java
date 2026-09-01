//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraLfo18 extends GhidraScript {
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
    void be32(long ndl, String tag) throws Exception {
        println("\n==== BE32 refs to " + tag + " 0x" + Long.toHexString(ndl) + " ====");
        for (int i = 0; i + 4 <= img.length; i++) {
            if (rd32(base+i) == ndl) {
                long va = base + i;
                Function cf = getFunctionContaining(toAddr(va));
                println("  @0x" + Long.toHexString(va) + (cf!=null?"  in "+cf.getName():"  (table/data)"));
                if (cf == null) {
                    for (long p=va-0x18; p<=va+0x18; p+=4) {
                        long v=rd32(p);
                        Function tf=currentProgram.getFunctionManager().getFunctionAt(toAddr(v));
                        String s=""; if(v>=base&&v<0x4010f000L){int j=(int)(v-base);StringBuilder sb=new StringBuilder();for(int k=0;k<18&&img[j+k]!=0;k++)sb.append((char)img[j+k]);s=sb.toString();}
                        println(String.format("      [0x%08x]=0x%08x %s %s", p, v, tf!=null?tf.getName():"", s));
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
        be32(0x40055008L, "FUN_40055008 (fine encoder editor / CC enqueue)");
        be32(0x40054cd8L, "FUN_40054cd8 (generic param apply)");
        be32(0x40052ae8L, "FUN_40052ae8");
        dump(0x40052ae8L);
        // what is _DAT_460d169c ? refs
        println("\n==== _DAT_460d169c writers ====");
        for (var r : currentProgram.getReferenceManager().getReferencesTo(toAddr(0x460d169cL))) {
            Function cf = getFunctionContaining(r.getFromAddress());
            Instruction ix = getInstructionAt(r.getFromAddress());
            println("  " + r.getFromAddress() + " " + r.getReferenceType() + " " + (cf!=null?cf.getName():"?") + "  " + (ix!=null?ix:""));
        }
        // _DAT_80000012 writers (the "MIDI mode" flag) -- confirm semantics
        println("\n==== _DAT_80000012 writers ====");
        for (var r : currentProgram.getReferenceManager().getReferencesTo(toAddr(0x80000012L))) {
            if (!r.getReferenceType().isWrite()) continue;
            Function cf = getFunctionContaining(r.getFromAddress());
            Instruction ix = getInstructionAt(r.getFromAddress());
            println("  " + r.getFromAddress() + " " + (cf!=null?cf.getName():"?") + "  " + (ix!=null?ix:""));
        }
    }
}
