//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
public class GhidraPartApply extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var mon=new ghidra.util.task.ConsoleTaskMonitor();
    for(long a: new long[]{0x40009094L}){
      Function f=fm.getFunctionAt(sp.getAddress(a));
      if(f==null){println("no function @"+Long.toHexString(a));continue;}
      var r=d.decompileFunction(f,300,mon);
      println("#### "+f.getName()+" @ "+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ####");
      println(r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)");
    }
    println("[END]");
  }
}
