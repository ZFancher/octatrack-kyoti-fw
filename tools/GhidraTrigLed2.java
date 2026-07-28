//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraTrigLed2 extends GhidraScript {
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
    List<String> out=new ArrayList<>();
    for(Function c:f.getCallingFunctions(mon))
      out.add("   "+c.getName()+" @"+c.getEntryPoint()+" size="+c.getBody().getNumAddresses());
    Collections.sort(out);
    if(out.isEmpty()) println("   (none / via table)");
    for(String s:out) println(s);
  }
  public void run() throws Exception {
    fm=currentProgram.getFunctionManager();
    d=new DecompInterface(); d.openProgram(currentProgram);
    mon=new ConsoleTaskMonitor();
    dump(0x40002df4L,"trig LED setter for scenes");
    callers(0x40002df4L,"FUN_40002df4");
    callers(0x4004d5b8L,"FUN_4004d5b8");
    println("\n[END]");
  }
}
