//@category Octatrack
// Proper search: use the reference manager (absolute-long operands are references,
// not scalars -- that is why the earlier scalar sweep missed them).
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraTimerRefs extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    var lst=currentProgram.getListing();
    var rm=currentProgram.getReferenceManager();
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var mon=new ConsoleTaskMonitor();
    long[] flds={0x460d1e4cL,0x460d1e50L,0x460d1e54L,0x460d1e58L,0x460d1e5cL,0x460d1e60L};
    String[] nm={"ENABLE","tick_ctr","box_ctr","tick_reload","window","callback"};
    Set<Long> fns=new LinkedHashSet<>();
    for(int i=0;i<flds.length;i++){
      println("\n=== 0x"+Long.toHexString(flds[i])+"  ("+nm[i]+") ===");
      var ri=rm.getReferencesTo(sp.getAddress(flds[i]));
      int n=0;
      while(ri.hasNext()){
        var r=ri.next();
        var f=fm.getFunctionContaining(r.getFromAddress());
        var ins=lst.getInstructionAt(r.getFromAddress());
        println(String.format("  %s  %-34s %-14s en %s", r.getFromAddress(),
                ins!=null?ins.toString():"?", r.getReferenceType(), f!=null?f.getName():"?"));
        if(f!=null) fns.add(f.getEntryPoint().getOffset());
        n++;
      }
      if(n==0) println("  (ninguna)");
    }
    println("\n=== funciones implicadas ===");
    for(long a:fns){
      var f=fm.getFunctionAt(sp.getAddress(a));
      println("  "+f.getName()+" @"+f.getEntryPoint()+" ("+f.getBody().getNumAddresses()+"B)");
    }
    println("\n[END]");
  }
}
