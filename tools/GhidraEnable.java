//@category Octatrack
// FUN_40031200 writes the countdown-enable flag. What exactly does it write, and
// who calls it? That decides whether the timeout can be removed in one byte.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraEnable extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    var lst=currentProgram.getListing();
    DecompInterface d=new DecompInterface(); d.openProgram(currentProgram);
    var mon=new ConsoleTaskMonitor();
    for(long a: new long[]{0x40031200L,0x40031494L}){
      println("\n=== 0x"+Long.toHexString(a)+" crudo ===");
      var f=fm.getFunctionAt(sp.getAddress(a));
      long end=f!=null? f.getBody().getMaxAddress().getOffset() : a+0x30;
      var it=lst.getInstructions(new AddressSet(sp.getAddress(a),sp.getAddress(end)),true);
      while(it.hasNext()){
        var i=it.next();
        StringBuilder h=new StringBuilder();
        for(byte b:i.getBytes()) h.append(String.format("%02x",b));
        println(String.format("  %s  %-16s %s", i.getAddress(), h, i));
      }
      if(f!=null){
        List<String> out=new ArrayList<>();
        for(Function c:f.getCallingFunctions(mon))
          out.add("   "+c.getName()+" @"+c.getEntryPoint()+" ("+c.getBody().getNumAddresses()+"B)");
        Collections.sort(out);
        println("  llamadores ("+out.size()+"):");
        if(out.isEmpty()) println("   (via tabla)");
        for(String s:out) println(s);
      }
    }
    println("\n[END]");
  }
}
