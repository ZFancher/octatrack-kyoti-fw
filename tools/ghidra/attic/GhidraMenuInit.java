//@category Octatrack
// Where is the PERSONALIZE item count (_DAT_460e4678) initialised? Also the scroll
// (_DAT_460e4668) and window-rows (_DAT_460e4674) siblings -- they are probably set
// together when the menu opens.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraMenuInit extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    var lst=currentProgram.getListing();
    var rm=currentProgram.getReferenceManager();
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var mon=new ConsoleTaskMonitor();
    Set<Long> fns=new LinkedHashSet<>();
    for(long a: new long[]{0x460e4678L,0x460e4674L,0x460e4668L,0x460e4670L}){
      println("\n=== refs a 0x"+Long.toHexString(a)+" ===");
      var ri=rm.getReferencesTo(sp.getAddress(a));
      int n=0;
      while(ri.hasNext()){
        var r=ri.next();
        var f=fm.getFunctionContaining(r.getFromAddress());
        var ins=lst.getInstructionAt(r.getFromAddress());
        println(String.format("  %s  %-34s %-10s en %s", r.getFromAddress(),
                ins!=null?ins.toString():"(dato)", r.getReferenceType(), f!=null?f.getName():"?"));
        if(f!=null&&r.getReferenceType().isWrite()) fns.add(f.getEntryPoint().getOffset());
        n++;
      }
      if(n==0) println("  (ninguna)");
    }
    println("\n=== escritores decompilados ===");
    for(long a:fns){
      var f=fm.getFunctionAt(sp.getAddress(a));
      var r=d.decompileFunction(f,300,mon);
      String c=r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)";
      println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ####");
      println(c.length()>1600? c.substring(0,1600)+"\n   ...(truncado)":c);
    }
    println("\n[END]");
  }
}
