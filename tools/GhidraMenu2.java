import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraMenu2 extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    var lst=currentProgram.getListing();
    var rm=currentProgram.getReferenceManager();
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var mon=new ConsoleTaskMonitor();
    for(long a: new long[]{0x46c8d18cL, 0x800000dcL, 0x800000d8L, 0x800000d4L, 0x800000a8L}){
      println("\n=== refs to 0x"+Long.toHexString(a)+" ===");
      var ri=rm.getReferencesTo(sp.getAddress(a)); int n=0;
      Set<Long> fns=new LinkedHashSet<>();
      while(ri.hasNext()){ var r=ri.next(); n++;
        var f=fm.getFunctionContaining(r.getFromAddress());
        var ins=lst.getInstructionAt(r.getFromAddress());
        println(String.format("  %s  %-30s %-9s in %s", r.getFromAddress(),
          ins!=null?ins.toString():"?", r.getReferenceType(), f!=null?f.getName():"?"));
        if(f!=null&&r.getReferenceType().isWrite()) fns.add(f.getEntryPoint().getOffset());
      }
      if(n==0) println("  (none)");
      for(long fa:fns){ var f=fm.getFunctionAt(sp.getAddress(fa));
        var rr=d.decompileFunction(f,120,mon);
        String c=rr!=null&&rr.decompileCompleted()?rr.getDecompiledFunction().getC():"(no-C)";
        println("  --- writer "+f.getName()+" ---");
        println(c.length()>1400?c.substring(0,1400):c);
      }
    }
    d.dispose(); println("\n[done]");
  }
}
