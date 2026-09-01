//@category Octatrack
// The PTN key handler FUN_4005a044 calls FUN_4004346c and FUN_40027de4.
// One of them likely paints the key LED. Also FUN_4002ebb0 touches ids 56/58/60/62.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraPtnKey extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var mon=new ConsoleTaskMonitor();
    for(long a: new long[]{0x4004346cL,0x40027de4L,0x4002ebb0L}){
      var f=fm.getFunctionAt(sp.getAddress(a));
      if(f==null){println("no fn @"+Long.toHexString(a));continue;}
      var r=d.decompileFunction(f,300,mon);
      String c=r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)";
      println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ####");
      println(c.length()>2200? c.substring(0,2200)+"\n   ...(truncado)":c);
    }
    println("\n[END]");
  }
}
