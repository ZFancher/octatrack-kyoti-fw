//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraSceneA extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var mon=new ConsoleTaskMonitor();
    long[] cand={0x40052ae8L,0x40052474L,0x400526e4L,0x4004d5b8L,0x40048afcL};
    for(long a:cand){
      var f=fm.getFunctionAt(sp.getAddress(a));
      if(f==null){println("no fn @"+Long.toHexString(a));continue;}
      var r=d.decompileFunction(f,300,mon);
      String c=(r!=null&&r.decompileCompleted())?r.getDecompiledFunction().getC():"(no-C)";
      println("#### "+f.getName()+" @ "+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ####");
      for(String l: c.split("\n"))
        if(l.contains("8ed90")||l.contains("8ed91")||l.contains("100a4ed")||l.contains("40033e3c")||l.contains("0x37")||l.contains("0x38"))
          println("   "+l.trim());
      println("");
    }
    println("[END]");
  }
}
