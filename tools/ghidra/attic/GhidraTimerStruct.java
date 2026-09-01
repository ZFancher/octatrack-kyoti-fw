//@category Octatrack
// The SELECT-window timer struct lives around 0x460d1e4c..0x460d1e60 and is reached
// through a base register, so an exact-address scan misses writers. Sweep the range,
// find who ticks FUN_40056ab8, and decompile the third 0xf0 call site FUN_4007b26c.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraTimerStruct extends GhidraScript {
  DecompInterface d; ConsoleTaskMonitor mon; FunctionManager fm;
  void dump(long a,String tag) throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var f=fm.getFunctionAt(sp.getAddress(a));
    if(f==null){println("\n#### sin funcion @0x"+Long.toHexString(a)+" ####");return;}
    var r=d.decompileFunction(f,300,mon);
    println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ("+tag+") ####");
    println(r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)");
  }
  public void run() throws Exception {
    fm=currentProgram.getFunctionManager();
    d=new DecompInterface(); d.openProgram(currentProgram);
    mon=new ConsoleTaskMonitor();
    var lst=currentProgram.getListing();
    println("=== escalares en 0x460d1e40..0x460d1e70 (la struct del timer) ===");
    var it=lst.getInstructions(true);
    while(it.hasNext()){
      var ins=it.next();
      for(int o=0;o<ins.getNumOperands();o++)
        for(Object ob:ins.getOpObjects(o))
          if(ob instanceof Scalar){
            long v=((Scalar)ob).getUnsignedValue();
            if(v>=0x460d1e40L&&v<=0x460d1e70L){
              var f=fm.getFunctionContaining(ins.getAddress());
              println(String.format("  0x%x @%s  %-32s en %s",v,ins.getAddress(),ins,f!=null?f.getName():"?"));
            }
          }
    }
    println("\n=== llamadores de FUN_40056ab8 (el tick del contador) ===");
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var f=fm.getFunctionAt(sp.getAddress(0x40056ab8L));
    List<String> out=new ArrayList<>();
    for(Function c:f.getCallingFunctions(mon)) out.add("  "+c.getName()+" @"+c.getEntryPoint()+" ("+c.getBody().getNumAddresses()+"B)");
    Collections.sort(out);
    if(out.isEmpty()) println("  (via tabla)");
    for(String s:out) println(s);
    dump(0x4007b26cL,"tercer call site 0xf0");
    println("\n[END]");
  }
}
