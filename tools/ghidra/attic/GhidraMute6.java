// GhidraMute6.java -- pin down the mixer-level gate and the QUICK-MUTE / arranger path.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraMute6 extends GhidraScript {
    DecompInterface dec; ConsoleTaskMonitor mon = new ConsoleTaskMonitor();
    FunctionManager fm; AddressSpace sp;
    LinkedHashSet<Long> dumped = new LinkedHashSet<>();
    String decomp(Function f){ try{ DecompileResults dr=dec.decompileFunction(f,150,mon);
        if(dr!=null&&dr.getDecompiledFunction()!=null) return dr.getDecompiledFunction().getC(); }catch(Exception e){} return "  <fail>"; }
    void dumpFunc(Function f,String tag){ if(f==null){println("  (no fn -- "+tag+")");return;}
        if(!dumped.add(f.getEntryPoint().getOffset())){println("// (dup "+f.getName()+")");return;}
        println("\n########## "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ("+tag+") ##########");
        println(decomp(f)); }
    void callersOf(long addr,String label){
        println("\n========== callers of "+label+" @0x"+Long.toHexString(addr)+" ==========");
        ReferenceIterator it=currentProgram.getReferenceManager().getReferencesTo(sp.getAddress(addr));
        TreeSet<Long> fns=new TreeSet<>();
        while(it.hasNext()){ Reference r=it.next(); Function cf=fm.getFunctionContaining(r.getFromAddress());
            println("  from "+r.getFromAddress()+" in "+(cf!=null?cf.getName():"NOFUNC")+" "+r.getReferenceType());
            if(cf!=null) fns.add(cf.getEntryPoint().getOffset()); }
        for(Long a:fns) dumpFunc(fm.getFunctionContaining(sp.getAddress(a)),"caller of "+label);
    }
    void rawDisasm(long start,int n,String tag){
        println("\n---------- raw disasm "+tag+" @0x"+Long.toHexString(start)+" ----------");
        Address a=sp.getAddress(start);
        for(int i=0;i<n;i++){ Instruction ins=getInstructionAt(a); if(ins==null){ println("  "+a+"  (no insn)"); a=a.next(); continue; }
            StringBuilder sb=new StringBuilder();
            for(Reference r: ins.getReferencesFrom()) sb.append(" ->").append(r.getToAddress());
            println("  "+a+"  "+ins+sb);
            a=ins.getMaxAddress().next(); }
    }
    public void run() throws Exception {
        fm=currentProgram.getFunctionManager();
        sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
        dec=new DecompInterface(); dec.openProgram(currentProgram);

        callersOf(0x400834d8L,"FUN_400834d8 ff64|=mask<<8");
        callersOf(0x4005a2b8L,"FUN_4005a2b8");
        // does anything read _DAT_46c7ff64 / _DAT_46c803d4 in the audio path? (already saw frame_builder)
        dumpFunc(fm.getFunctionContaining(sp.getAddress(0x4000432cL)),"FUN_4000432c");
        dumpFunc(fm.getFunctionContaining(sp.getAddress(0x40095ee0L)),"FUN_40095ee0 amp-writer");

        // the big FUN_40061a94 -- just the part around FUN_40083544 call
        Function big=fm.getFunctionContaining(sp.getAddress(0x40061a94L));
        println("\n########## FUN_40061a94 (full) ##########");
        println(decomp(big));

        // raw disasm of frame builder region + the misdecompiled spots
        rawDisasm(0x4000c8a4L,180,"frame_builder");

        dec.dispose(); println("\n[GhidraMute6] done.");
    }
}
