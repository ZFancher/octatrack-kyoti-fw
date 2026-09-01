//@category Octatrack
// Does moving an encoder trigger apply_part (FUN_40009094, which runs restore_stub)?
// The editors set dirty flags 0x9b332 and 0x100f8598. Find who READS those flags and
// whether that path reaches apply_part -- if it does, restore_stub re-runs after the
// encoder edit and fights any LED fix.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraDirty extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    var lst=currentProgram.getListing();
    var rm=currentProgram.getReferenceManager();
    // 0x100f8598 is a plain absolute; 0x9b332 is base-relative so scan instructions too
    println("=== quien lee/escribe _DAT_100f8598 (flag 'param editado, re-aplicar') ===");
    var ri=rm.getReferencesTo(sp.getAddress(0x100f8598L));
    Set<String> fns=new TreeSet<>();
    while(ri.hasNext()){
      var r=ri.next(); var f=fm.getFunctionContaining(r.getFromAddress());
      println(String.format("  %s %s  %-28s %s", r.getReferenceType().isWrite()?"W":"R",
              r.getFromAddress(), lst.getInstructionAt(r.getFromAddress()), f!=null?f.getName():"?"));
      if(f!=null && !r.getReferenceType().isWrite()) fns.add(f.getName()+"@"+f.getEntryPoint().getOffset());
    }
    println("\n=== los LECTORES del flag: ¿llaman a apply_part FUN_40009094? ===");
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var mon=new ConsoleTaskMonitor();
    for(String s:fns){
      long a=Long.parseLong(s.split("@")[1]);
      var f=fm.getFunctionAt(sp.getAddress(a));
      var r=d.decompileFunction(f,200,mon);
      String c=r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"";
      boolean callsApply = c.contains("FUN_40009094");
      println(String.format("  %-22s llama apply_part: %s", f.getName(), callsApply));
    }
  }
}
