// Decompile the prime quantizer candidates.
// @category Octatrack
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
public class GhidraArpQuant2 extends ghidra.app.script.GhidraScript {
    DecompInterface dec;
    void dump(long e) throws Exception {
        Function f=getFunctionAt(toAddr(e));
        if(f==null)f=getFunctionContaining(toAddr(e));
        println("\n########## "+(f==null?"?":f.getName())+" @"+Long.toHexString(e)+" ##########");
        if(f==null){println("no func");return;}
        DecompileResults dr=dec.decompileFunction(f,120,monitor);
        if(dr!=null&&dr.getDecompiledFunction()!=null) println(dr.getDecompiledFunction().getC());
        // print callers
        println("--- callers ---");
        for(Reference r: currentProgram.getReferenceManager().getReferencesTo(f.getEntryPoint())){
            Function c=getFunctionContaining(r.getFromAddress());
            println("  from "+r.getFromAddress()+" ("+r.getReferenceType()+") "+(c==null?"?":c.getName()));
        }
    }
    public void run() throws Exception {
        dec=new DecompInterface(); dec.openProgram(currentProgram);
        for(long e: new long[]{0x4009f794L,0x4003f71cL,0x4003f80cL,0x4003f8e8L}) dump(e);
    }
}
