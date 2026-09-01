//@category Octatrack
// Map the LED subsystem: the accessor family around 0x460ba98c, who calls each one,
// and which caller loops over 16 trigs (the trig-LED painter) vs 8 tracks.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraLedMap extends GhidraScript {
  DecompInterface d; ConsoleTaskMonitor mon; FunctionManager fm;
  void callers(long a){
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var f=fm.getFunctionAt(sp.getAddress(a));
    if(f==null){println("  0x"+Long.toHexString(a)+" : (no fn)");return;}
    List<String> out=new ArrayList<>();
    for(Function c:f.getCallingFunctions(mon)) out.add(c.getName()+"("+c.getBody().getNumAddresses()+"B)");
    Collections.sort(out);
    println("  "+f.getName()+" @"+f.getEntryPoint()+" ["+f.getBody().getNumAddresses()+"B] <- "+
            (out.isEmpty()?"(table)":String.join(", ",out)));
  }
  void dump(long a,String tag) throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var f=fm.getFunctionAt(sp.getAddress(a));
    if(f==null){println("no fn @"+Long.toHexString(a));return;}
    var r=d.decompileFunction(f,300,mon);
    println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ("+tag+") ####");
    println(r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)");
  }
  public void run() throws Exception {
    fm=currentProgram.getFunctionManager();
    d=new DecompInterface(); d.openProgram(currentProgram);
    mon=new ConsoleTaskMonitor();
    println("=== LED accessor family (buffer 0x460ba98c) and their callers ===");
    for(long a: new long[]{0x400131a0L,0x400131c8L,0x400131f4L,0x4001321cL,0x40013248L,
                           0x400132c4L,0x40013304L,0x4001332cL,0x40013634L,0x400136a8L,0x40013810L})
      callers(a);
    dump(0x40083eb0L,"track LED painter (8 tracks)");
    println("\n[END]");
  }
}
