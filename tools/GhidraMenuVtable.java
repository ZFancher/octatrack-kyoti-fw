//@category Octatrack
// PERSONALIZE screen vtable at 0x400cbe8c. FUN_400685cc is the likely enter/init --
// that is where the list count (offset 0x10 of the 0x460e4668 struct) should be set.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraMenuVtable extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var mon=new ConsoleTaskMonitor();
    for(long a: new long[]{0x400685ccL,0x40068828L,0x40068fa8L}){
      var f=fm.getFunctionAt(sp.getAddress(a));
      if(f==null){println("no fn @"+Long.toHexString(a));continue;}
      var r=d.decompileFunction(f,300,mon);
      String c=r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)";
      println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ####");
      println(c.length()>2400? c.substring(0,2400)+"\n   ...(truncado)":c);
    }
    println("\n[END]");
  }
}
