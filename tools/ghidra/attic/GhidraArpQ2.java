// Decompile FUN_4002cef0 (bulk-scan quantizer candidate).
// @category Octatrack
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
public class GhidraArpQ2 extends ghidra.app.script.GhidraScript {
    public void run() throws Exception {
        DecompInterface dec=new DecompInterface(); dec.openProgram(currentProgram);
        Function f=getFunctionAt(toAddr(0x4002cef0L));
        DecompileResults dr=dec.decompileFunction(f,120,monitor);
        println(dr.getDecompiledFunction().getC());
    }
}
