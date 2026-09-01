//@category Octatrack
// Intersection of {reads scene selection 0x8ed90} and {calls the LED family}:
// FUN_400346a4, FUN_40034880, FUN_40034a44 -- prime suspects for the scene trig painter.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraTrigPainter extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var mon=new ConsoleTaskMonitor();
    for(long a: new long[]{0x400346a4L,0x40034880L,0x40034a44L,0x40034350L}){
      var f=fm.getFunctionAt(sp.getAddress(a));
      if(f==null){println("no fn @"+Long.toHexString(a));continue;}
      var r=d.decompileFunction(f,300,mon);
      println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ####");
      println(r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)");
    }
    println("[END]");
  }
}
