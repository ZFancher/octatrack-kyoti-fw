//@category Octatrack
// Stage 2: map the trig LEDs.
//  - FUN_4004d5b8 compares a trig index against the scene A/B selection -> scene trig lighting
//  - FUN_400132c4(id,state) writes 2-bit states into the LED buffer 0x460ba98c
// Find the painter that drives the 16 trig LEDs and the id range it uses.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraTrigLed extends GhidraScript {
  DecompInterface d; ConsoleTaskMonitor mon; FunctionManager fm;
  void dump(long a,String tag) throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var f=fm.getFunctionAt(sp.getAddress(a));
    if(f==null){println("no fn @"+Long.toHexString(a));return;}
    var r=d.decompileFunction(f,300,mon);
    println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ("+tag+") ####");
    println(r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)");
  }
  void callers(long a,String tag){
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var f=fm.getFunctionAt(sp.getAddress(a));
    println("\n-- callers of "+tag+" --");
    if(f==null){println("   (none)");return;}
    for(Function c:new TreeSet<>(f.getCallingFunctions(mon)))
      println("   "+c.getName()+" @"+c.getEntryPoint()+" size="+c.getBody().getNumAddresses());
  }
  public void run() throws Exception {
    fm=currentProgram.getFunctionManager();
    d=new DecompInterface(); d.openProgram(currentProgram);
    mon=new ConsoleTaskMonitor();
    dump(0x4004d5b8L,"scene-trig comparator");
    callers(0x4004d5b8L,"FUN_4004d5b8");
    // the LED accessor family - small ones reveal the id encoding
    for(long a: new long[]{0x400131a0L,0x40013248L,0x40013304L,0x40013634L})
      dump(a,"LED accessor");
    println("\n[END]");
  }
}
