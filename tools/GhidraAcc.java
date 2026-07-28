//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraAcc extends GhidraScript {
  public void run() throws Exception {
    AddressSpace sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    FunctionManager fm=currentProgram.getFunctionManager();
    DecompInterface dec=new DecompInterface(); dec.openProgram(currentProgram);
    ConsoleTaskMonitor mon=new ConsoleTaskMonitor();
    Address a=sp.getAddress(0x40031ee0L);
    Function f=fm.getFunctionContaining(a);
    if(f==null){disassemble(a); f=createFunction(a,"track_param_accessor");}
    DecompileResults r=dec.decompileFunction(f,90,mon);
    println("#### "+f.getName()+" @ "+f.getEntryPoint()+" (size "+f.getBody().getNumAddresses()+") ####");
    println(r!=null&&r.decompileCompleted()? r.getDecompiledFunction().getC():"(no-C)");
    println("[END]");
  }
}
