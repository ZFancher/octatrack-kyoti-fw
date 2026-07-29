//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.util.task.ConsoleTaskMonitor;
import ghidra.program.model.listing.*;
import java.util.*;
public class GhidraVoiceRel extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var mon=new ConsoleTaskMonitor();
    var f=fm.getFunctionAt(sp.getAddress(0x40004768L));
    var r=d.decompileFunction(f,240,mon);
    println("#### FUN_40004768 ("+f.getBody().getNumAddresses()+"B) ####");
    println(r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)");
    List<String> o=new ArrayList<>();
    for(Function c:f.getCallingFunctions(mon))
      o.add(String.format("   %-16s @%s (%dB)",c.getName(),c.getEntryPoint(),c.getBody().getNumAddresses()));
    Collections.sort(o);
    println("\n-- llamadores --");
    if(o.isEmpty()) println("   (via tabla)");
    for(String s:o) println(s);
  }
}
