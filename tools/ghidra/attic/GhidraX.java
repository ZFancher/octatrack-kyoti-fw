//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraX extends GhidraScript {
  public void run() throws Exception {
    AddressSpace sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    FunctionManager fm=currentProgram.getFunctionManager();
    DecompInterface dec=new DecompInterface(); dec.openProgram(currentProgram);
    ConsoleTaskMonitor mon=new ConsoleTaskMonitor();
    for(long s: new long[]{0x4005a918L}){
      Address a=sp.getAddress(s); Function f=fm.getFunctionContaining(a);
      if(f==null){disassemble(a); f=createFunction(a,null);}
      DecompileResults r=dec.decompileFunction(f,90,mon);
      println("#### "+f.getName()+" @ "+f.getEntryPoint()+" (size "+f.getBody().getNumAddresses()+") ####");
      println(r!=null&&r.decompileCompleted()? r.getDecompiledFunction().getC():"(no-C)");
    }
    println("[END]");
  }
}
