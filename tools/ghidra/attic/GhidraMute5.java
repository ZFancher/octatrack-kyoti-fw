// GhidraMute5.java -- who invokes the soft-release primitives vs the mute-mask flip.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraMute5 extends GhidraScript {
    DecompInterface dec; ConsoleTaskMonitor mon = new ConsoleTaskMonitor();
    FunctionManager fm; AddressSpace sp;
    LinkedHashSet<Long> dumped = new LinkedHashSet<>();
    String decomp(Function f){ try{ DecompileResults dr=dec.decompileFunction(f,150,mon);
        if(dr!=null&&dr.getDecompiledFunction()!=null) return dr.getDecompiledFunction().getC(); }catch(Exception e){} return "  <fail>"; }
    void dumpFunc(Function f,String tag){ if(f==null){println("  (no fn -- "+tag+")");return;}
        if(!dumped.add(f.getEntryPoint().getOffset())){println("// (dup "+f.getName()+" -- "+tag+")");return;}
        println("\n########## "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ("+tag+") ##########");
        println(decomp(f)); }
    void callersOf(long addr,String label){
        println("\n========== callers of "+label+" @0x"+Long.toHexString(addr)+" ==========");
        ReferenceIterator it=currentProgram.getReferenceManager().getReferencesTo(sp.getAddress(addr));
        TreeSet<Long> fns=new TreeSet<>();
        while(it.hasNext()){ Reference r=it.next(); Function cf=fm.getFunctionContaining(r.getFromAddress());
            Address cur=r.getFromAddress();
            println("  from "+cur+" in "+(cf!=null?cf.getName():"NOFUNC")+" "+r.getReferenceType());
            if(cf!=null) fns.add(cf.getEntryPoint().getOffset()); }
        for(Long a:fns) dumpFunc(fm.getFunctionContaining(sp.getAddress(a)),"caller of "+label);
    }
    public void run() throws Exception {
        fm=currentProgram.getFunctionManager();
        sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
        dec=new DecompInterface(); dec.openProgram(currentProgram);
        // soft-release primitives
        callersOf(0x40008f84L,"FUN_40008f84 soft-release-voice");
        callersOf(0x40008fe4L,"FUN_40008fe4 soft-release+184c");
        callersOf(0x40083a7cL,"FUN_40083a7c release-all-muted");
        callersOf(0x400836d8L,"FUN_400836d8 apply-mute-mask");
        callersOf(0x40083544L,"FUN_40083544 apply-mute-1trk");
        // the release/envelope engine
        dumpFunc(fm.getFunctionContaining(sp.getAddress(0x40005c7cL)),"FUN_40005c7c env/fade");
        dumpFunc(fm.getFunctionContaining(sp.getAddress(0x4000672cL)),"FUN_4000672c");
        dec.dispose(); println("\n[GhidraMute5] done.");
    }
}
