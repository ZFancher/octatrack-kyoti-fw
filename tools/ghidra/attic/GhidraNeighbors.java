//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraNeighbors extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var mon=new ConsoleTaskMonitor();
    println("=== functions in 0x40052400..0x40052b00 ===");
    var fi=fm.getFunctions(sp.getAddress(0x40052400L),true);
    while(fi.hasNext()){
      var f=fi.next();
      if(f.getEntryPoint().getOffset()>0x40052b00L) break;
      println("  "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses());
    }
    println("\n=== decompile of the ones just before FUN_40052944 ===");
    var fi2=fm.getFunctions(sp.getAddress(0x40052400L),true);
    while(fi2.hasNext()){
      var f=fi2.next();
      long off=f.getEntryPoint().getOffset();
      if(off>=0x40052944L) break;
      if(off<0x400527f0L) continue;
      var r=d.decompileFunction(f,300,mon);
      println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ####");
      println(r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)");
    }
    println("[END]");
  }
}
