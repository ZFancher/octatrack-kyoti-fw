//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraOne extends GhidraScript {
  public void run() throws Exception {
    DecompInterface dec=new DecompInterface(); dec.openProgram(currentProgram);
    ConsoleTaskMonitor mon=new ConsoleTaskMonitor();
    for(String s:getScriptArgs()){
      long t=Long.parseLong(s.replace("0x",""),16);
      Function f=getFunctionContaining(toAddr(t));
      if(f==null){ println("no fn "+s); continue;}
      DecompileResults dr=dec.decompileFunction(f,180,mon);
      println("\n===== "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" =====");
      if(dr!=null&&dr.decompileCompleted()) println(dr.getDecompiledFunction().getC());
      else println("(decomp fail)");
    }
  }
}
