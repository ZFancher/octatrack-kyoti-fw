//@category Octatrack
// PERSONALIZE menu: 16 string pointers at 0x400b2a34, then what look like handler
// pointers at 0x400b2a74. Who reads the table, and where does the item count live?
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraPersonalize extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    var lst=currentProgram.getListing();
    var rm=currentProgram.getReferenceManager();
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var mon=new ConsoleTaskMonitor();
    Set<Long> fns=new LinkedHashSet<>();
    for(long a: new long[]{0x400b2a34L,0x400b2a74L,0x400b5e63L}){
      println("\n=== referencias a 0x"+Long.toHexString(a)+" ===");
      var ri=rm.getReferencesTo(sp.getAddress(a));
      int n=0;
      while(ri.hasNext()){
        var r=ri.next();
        var f=fm.getFunctionContaining(r.getFromAddress());
        var ins=lst.getInstructionAt(r.getFromAddress());
        println(String.format("  %s  %-30s %-10s en %s", r.getFromAddress(),
                ins!=null?ins.toString():"?", r.getReferenceType(), f!=null?f.getName():"?"));
        if(f!=null) fns.add(f.getEntryPoint().getOffset());
        n++;
      }
      if(n==0) println("  (ninguna)");
    }
    println("\n=== decompilacion de los consumidores ===");
    for(long a:fns){
      var f=fm.getFunctionAt(sp.getAddress(a));
      var r=d.decompileFunction(f,300,mon);
      String c=r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)";
      println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ####");
      println(c.length()>2000? c.substring(0,2000)+"\n   ...(truncado)":c);
    }
    println("\n[END]");
  }
}
