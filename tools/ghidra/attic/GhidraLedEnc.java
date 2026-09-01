//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraLedEnc extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var mon=new ConsoleTaskMonitor();
    for(long a: new long[]{0x400131a0L,0x400131c8L,0x40013810L,0x40083eb0L}){
      var f=fm.getFunctionAt(sp.getAddress(a));
      if(f==null){println("no fn");continue;}
      var r=d.decompileFunction(f,300,mon);
      println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ####");
      println(r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)");
    }
    println("[END]");
  }
}
