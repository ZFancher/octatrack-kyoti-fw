//@category Octatrack
// Hunt the deferred "audio CC out" transmitter that emits CC 28-33 for LFO speed/depth.
//  (a) refs (R+W) to the marker region ~0x80000d80..0x80000e80 that FUN_40054cd8/FUN_40055008
//      write 0xa0 into for enc<6 edits.
//  (b) any function referencing the immediate 0x1c AND calling FUN_40010bc8 (or a wrapper).
//  (c) decompile the CONTROL submenu around the "AUDIO CC OUT" menu-string table entry.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraLfo15 extends GhidraScript {
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
        DecompileResults dr = dec.decompileFunction(f, 260, new ConsoleTaskMonitor());
        if (dr != null && dr.getDecompiledFunction() != null) println(dr.getDecompiledFunction().getC());
        else println("  <decompile failed>");
    }
    public void run() throws Exception {
        Memory mem = currentProgram.getMemory();
        img = new byte[(int)(0x4010fdefL - base)];
        mem.getBytes(toAddr(base), img);
        dec = new DecompInterface(); dec.openProgram(currentProgram);
        FunctionManager fm = currentProgram.getFunctionManager();
        ReferenceManager rm = currentProgram.getReferenceManager();

        println("==== refs into 0x80000d00..0x80000f00 ====");
        Set<Long> touch = new LinkedHashSet<>();
        for (long a = 0x80000d00L; a < 0x80000f00L; a += 1) {
            for (Reference r : rm.getReferencesTo(toAddr(a))) {
                Function cf = fm.getFunctionContaining(r.getFromAddress());
                Instruction ix = getInstructionAt(r.getFromAddress());
                println(String.format("  0x%08x  %-6s  %-22s  %s", a, r.getReferenceType(),
                    cf!=null?cf.getName():"?", ix!=null?ix.toString():""));
                if (cf != null) touch.add(cf.getEntryPoint().getOffset());
            }
        }

        // (b) functions that reference 0x1c immediate AND (transitively) FUN_40010bc8
        println("\n==== funcs with #0x1c/#0x1b immediate that also call a MIDI-send ====");
        Set<Long> senders = new HashSet<>();
        senders.add(0x40010bc8L);
        for (Function f : fm.getFunctions(true)) {
            boolean callsSend=false, hasImm=false;
            var it = currentProgram.getListing().getInstructions(f.getBody(), true);
            while (it.hasNext()) {
                Instruction ins = it.next();
                String s = ins.toString();
                if (s.contains("#0x1c") || s.contains("#0x1b")) hasImm = true;
                String m = ins.getMnemonicString().toLowerCase();
                if (m.startsWith("jsr")||m.startsWith("bsr")) {
                    Address[] fl = ins.getFlows();
                    if (fl!=null) for (Address t: fl) if (senders.contains(t.getOffset())) callsSend=true;
                }
            }
            if (hasImm && callsSend) { println("  " + f.getName() + " @" + f.getEntryPoint()); touch.add(f.getEntryPoint().getOffset()); }
        }

        // (c) menu string table around AUDIO CC OUT / AUDIO CC IN
        println("\n==== bytes 0x400b29c0..0x400b2a20 (CONTROL submenu string table) ====");
        for (long p=0x400b29c0L; p<0x400b2a20L; p+=4) {
            int i=(int)(p-base);
            long v=((img[i]&0xffL)<<24)|((img[i+1]&0xffL)<<16)|((img[i+2]&0xffL)<<8)|(img[i+3]&0xffL);
            String str="";
            if (v>=0x40000400L && v<0x4010f000L) { int j=(int)(v-base); StringBuilder sb=new StringBuilder(); for(int k=0;k<20&&img[j+k]!=0;k++) sb.append((char)img[j+k]); str=sb.toString(); }
            println(String.format("  [0x%08x] = 0x%08x  %s", p, v, str));
        }

        for (long e : touch) dump(e);
    }
}
