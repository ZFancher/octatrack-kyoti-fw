//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraLedTrig extends GhidraScript {
  DecompInterface d; ConsoleTaskMonitor mon; FunctionManager fm;
  void up(long a,String tag){
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var f=fm.getFunctionAt(sp.getAddress(a));
    println("\n-- llamadores de "+tag+" --");
    if(f==null){println("   (sin funcion)");return;}
    List<String> o=new ArrayList<>();
    for(Function c:f.getCallingFunctions(mon))
      o.add(String.format("   %-16s @%s (%dB)",c.getName(),c.getEntryPoint(),c.getBody().getNumAddresses()));
    Collections.sort(o);
    if(o.isEmpty()) println("   (via tabla / raiz)");
    for(String s:o) println(s);
  }
  void dump(long a,String tag) throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var f=fm.getFunctionAt(sp.getAddress(a));
    if(f==null){println("no fn");return;}
    var r=d.decompileFunction(f,240,mon);
    println("\n#### "+f.getName()+" ("+f.getBody().getNumAddresses()+"B) "+tag+" ####");
    println(r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)");
  }
  public void run() throws Exception {
    fm=currentProgram.getFunctionManager(); mon=new ConsoleTaskMonitor();
    d=new DecompInterface(); d.openProgram(currentProgram);
    dump(0x40083fdcL,"llama al pintor");
    up(0x40083fdcL,"FUN_40083fdc");
  }
}
