//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraDspInit extends GhidraScript {
  public void run() throws Exception {
    AddressSpace sp = currentProgram.getAddressFactory().getDefaultAddressSpace();
    FunctionManager fm = currentProgram.getFunctionManager();
    DecompInterface dec = new DecompInterface(); dec.openProgram(currentProgram);
    ConsoleTaskMonitor mon = new ConsoleTaskMonitor();
    Address a = sp.getAddress(0x40001e1aL);
    Function f = fm.getFunctionContaining(a);
    if (f==null){ disassemble(a); f=createFunction(a,"dsp_init"); }
    DecompileResults r = dec.decompileFunction(f, 120, mon);
    println("\n############ dsp_init @ "+f.getEntryPoint()+" (size "+f.getBody().getNumAddresses()+") ############");
    println(r!=null&&r.decompileCompleted()? r.getDecompiledFunction().getC() : "  (fallo: "+(r!=null?r.getErrorMessage():"null")+")");
    println("[fin]");
  }
}
