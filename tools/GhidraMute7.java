// GhidraMute7.java -- decompile the audio-engine sites that read the derived mute masks
// _DAT_46c7ff64 / _DAT_46c803d4 / _DAT_8000184e, and find refs to the mute-commit ptr table.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraMute7 extends GhidraScript {
    DecompInterface dec; ConsoleTaskMonitor mon = new ConsoleTaskMonitor();
    FunctionManager fm; AddressSpace sp;
    LinkedHashSet<Long> dumped = new LinkedHashSet<>();
    String decomp(Function f){ try{ DecompileResults dr=dec.decompileFunction(f,180,mon);
        if(dr!=null&&dr.getDecompiledFunction()!=null) return dr.getDecompiledFunction().getC(); }catch(Exception e){} return "  <fail>"; }
    void dumpAt(long a,String tag){
        Function f=fm.getFunctionContaining(sp.getAddress(a));
        if(f==null){println("\n// no fn @0x"+Long.toHexString(a)+" ("+tag+")");return;}
        if(!dumped.add(f.getEntryPoint().getOffset())){println("// (dup "+f.getName()+" via "+Long.toHexString(a)+")");return;}
        println("\n########## "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" (via 0x"+Long.toHexString(a)+" "+tag+") ##########");
        println(decomp(f));
    }
    void refsTo(long a,String tag){
        println("\n===== refs to 0x"+Long.toHexString(a)+" ("+tag+") =====");
        ReferenceIterator it=currentProgram.getReferenceManager().getReferencesTo(sp.getAddress(a));
        while(it.hasNext()){ Reference r=it.next(); Function cf=fm.getFunctionContaining(r.getFromAddress());
            println("  from "+r.getFromAddress()+" in "+(cf!=null?cf.getName()+"@"+cf.getEntryPoint():"NOFUNC")+" "+r.getReferenceType()); }
    }
    public void run() throws Exception {
        fm=currentProgram.getFunctionManager();
        sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
        dec=new DecompInterface(); dec.openProgram(currentProgram);
        long[] sites = {0x4000ac46L,0x4000b936L,0x4000c93eL,0x4000ede8L,0x4000ee34L,0x4005e294L,0x40030634L,0x40060a30L,0x40034286L,0x40051930L};
        for(long s: sites) dumpAt(s,"mask reader");
        refsTo(0x400d15e4L,"mute-commit ptr table");
        refsTo(0x400d15e8L,"mute-commit ptr table+4");
        // also: does anything ref the mute module entry? find refs to FUN_40083ab4/e40 as data (dispatch table loc)
        refsTo(0x40083ab4L,"FUN_40083ab4");
        refsTo(0x40083e40L,"FUN_40083e40");
        dec.dispose(); println("\n[GhidraMute7] done.");
    }
}
