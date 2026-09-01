// GhidraMute8.java -- the low-level audio silencing gate + per-track main/send level assembly.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraMute8 extends GhidraScript {
    DecompInterface dec; ConsoleTaskMonitor mon = new ConsoleTaskMonitor();
    FunctionManager fm; AddressSpace sp;
    LinkedHashSet<Long> dumped = new LinkedHashSet<>();
    String decomp(Function f){ try{ DecompileResults dr=dec.decompileFunction(f,200,mon);
        if(dr!=null&&dr.getDecompiledFunction()!=null) return dr.getDecompiledFunction().getC(); }catch(Exception e){} return "  <fail>"; }
    void ensure(long a){ Address ad=sp.getAddress(a); if(fm.getFunctionContaining(ad)==null){
        try{ disassemble(ad); createFunction(ad,null); }catch(Exception e){} } }
    void dumpAt(long a,String tag){
        ensure(a);
        Function f=fm.getFunctionContaining(sp.getAddress(a));
        if(f==null){println("\n// no fn @0x"+Long.toHexString(a)+" ("+tag+")");return;}
        if(!dumped.add(f.getEntryPoint().getOffset())){println("// (dup "+f.getName()+" via "+Long.toHexString(a)+")");return;}
        println("\n########## "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" (via 0x"+Long.toHexString(a)+" "+tag+") ##########");
        println(decomp(f));
    }
    void callers(long a,String tag){
        println("\n===== callers of 0x"+Long.toHexString(a)+" ("+tag+") =====");
        ReferenceIterator it=currentProgram.getReferenceManager().getReferencesTo(sp.getAddress(a));
        TreeSet<Long> s=new TreeSet<>();
        while(it.hasNext()){ Reference r=it.next(); Function cf=fm.getFunctionContaining(r.getFromAddress());
            println("  from "+r.getFromAddress()+" in "+(cf!=null?cf.getName():"NOFUNC")+" "+r.getReferenceType());
            if(cf!=null) s.add(cf.getEntryPoint().getOffset()); }
        for(long e:s) dumpAt(e,"caller "+tag);
    }
    public void run() throws Exception {
        fm=currentProgram.getFunctionManager();
        sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
        dec=new DecompInterface(); dec.openProgram(currentProgram);

        // the silencing-mask arithmetic sites
        dumpAt(0x4000ac18L,"46c803d4/8000184e delta apply");
        dumpAt(0x4000b8f0L,"46c7ff64 voice-cmd gate A");
        dumpAt(0x4000ede8L,"46c803d4 reader");
        // per-track main + send level assembler (writes vframe, region 0x40084b..0x40086xxx)
        for(long a: new long[]{0x40084b92L,0x40084d0aL,0x40085058L,0x40085386L,0x400854f2L,0x40086278L})
            dumpAt(a,"mixer/level asm");
        // who calls FUN_4000432c (writes framelvl_46c938d4 + 100d3a1c)
        callers(0x4000432cL,"FUN_4000432c");
        // FUN_40095ee0 internals
        dumpAt(0x40099090L,"FUN_40099090 (amp lerp)");
        dumpAt(0x40098fa8L,"FUN_40098fa8");
        dumpAt(0x4009c708L,"FUN_4009c708");

        dec.dispose(); println("\n[GhidraMute8] done.");
    }
}
