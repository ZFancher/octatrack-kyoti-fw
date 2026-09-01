//@category Octatrack
// Who reads the setter array at 0x400b2ac0 (the [YES]/[NO] + arrow input handler)?
// Also confirm the item-count variable _DAT_460e4678.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraSetterTbl extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    var lst=currentProgram.getListing();
    var rm=currentProgram.getReferenceManager();
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var mon=new ConsoleTaskMonitor();
    Set<Long> fns=new LinkedHashSet<>();
    for(long a: new long[]{0x400b2ac0L,0x460e4678L,0x460e4670L}){
      println("\n=== refs a 0x"+Long.toHexString(a)+" ===");
      var ri=rm.getReferencesTo(sp.getAddress(a));
      int n=0;
      while(ri.hasNext()){
        var r=ri.next();
        var f=fm.getFunctionContaining(r.getFromAddress());
        var ins=lst.getInstructionAt(r.getFromAddress());
        println(String.format("  %s  %-32s %-10s en %s", r.getFromAddress(),
                ins!=null?ins.toString():"(dato)", r.getReferenceType(), f!=null?f.getName():"?"));
        if(f!=null&&a==0x400b2ac0L) fns.add(f.getEntryPoint().getOffset());
        n++;
      }
      if(n==0) println("  (ninguna)");
    }
    for(long a:fns){
      var f=fm.getFunctionAt(sp.getAddress(a));
      var r=d.decompileFunction(f,300,mon);
      String c=r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)";
      println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ####");
      println(c.length()>1800? c.substring(0,1800)+"\n   ...(truncado)":c);
    }
    println("\n[END]");
  }
}
