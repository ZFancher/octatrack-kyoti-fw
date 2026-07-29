//@category Octatrack
// The five hooked functions were picked by prologue shape. Verify each is actually an
// ENCODER EDITOR (reads a delta argument, writes a parameter) rather than something the
// firmware also calls during a pattern change.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import ghidra.program.model.listing.*;
import java.util.*;
public class GhidraEncCheck extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var mon=new ConsoleTaskMonitor();
    long[] c={0x40052e98L,0x40052ae8L,0x40053498L,0x40053a68L,0x4005435cL};
    for(long a:c){
      var f=fm.getFunctionAt(sp.getAddress(a));
      var r=d.decompileFunction(f,240,mon);
      String s=r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"";
      String sig=s.contains("(")? s.substring(s.indexOf("\n\n")+2, Math.min(s.length(), s.indexOf("{"))).trim():"?";
      boolean writesParam = s.contains("0x8f3e2");
      boolean dirtyFlag   = s.contains("0x9b332");
      List<String> callers=new ArrayList<>();
      for(Function cf:f.getCallingFunctions(mon)) callers.add(cf.getName());
      Collections.sort(callers);
      println(String.format("\n#### 0x%08x  (%dB) ####", a, f.getBody().getNumAddresses()));
      println("  firma      : "+sig.replaceAll("\\s+"," "));
      println("  escribe param (0x8f3e2): "+writesParam+"   dirty flag (0x9b332): "+dirtyFlag);
      println("  llamadores : "+(callers.isEmpty()?"(via tabla)":callers));
    }
  }
}
