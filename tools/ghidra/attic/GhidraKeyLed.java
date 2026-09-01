//@category Octatrack
// (a) Does closing the SELECT window on a trig press go through a path independent
//     of the countdown? -> callers of FUN_40056a70 / FUN_40056b00
// (b) LED id tables: tracks use 0x400a9670 (8 entries), trigs 0x400a75f2.
//     Dump the neighbourhood to locate the BANK / PTN key ids.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraKeyLed extends GhidraScript {
  void callers(long a,String tag){
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    var f=fm.getFunctionAt(sp.getAddress(a));
    println("\n-- llamadores de "+tag+" --");
    if(f==null){println("   (sin funcion)");return;}
    List<String> o=new ArrayList<>();
    for(Function c:f.getCallingFunctions(new ConsoleTaskMonitor()))
      o.add("   "+c.getName()+" @"+c.getEntryPoint()+" ("+c.getBody().getNumAddresses()+"B)");
    Collections.sort(o);
    if(o.isEmpty()) println("   (via tabla)");
    for(String s:o) println(s);
  }
  void tbl(long a,int n,String tag) throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var mem=currentProgram.getMemory();
    println("\n=== tabla "+tag+" @0x"+Long.toHexString(a)+" ===");
    StringBuilder s=new StringBuilder("  ");
    for(int i=0;i<n;i++){
      try{ s.append(String.format("%d ", mem.getInt(sp.getAddress(a+i*4L)))); }
      catch(Exception e){ s.append("? "); }
    }
    println(s.toString());
  }
  public void run() throws Exception {
    callers(0x40056a70L,"FUN_40056a70 (cierra ventana + callback)");
    callers(0x40056b00L,"FUN_40056b00 (cierra ventana sin callback)");
    tbl(0x400a9670L,8,"LEDs de track");
    tbl(0x400a75f2L,20,"ids usados por el pintor de trigs");
    tbl(0x400a72a8L,10,"tabla vecina 0x400a72a8");
    tbl(0x400a758aL,10,"tabla vecina 0x400a758a");
    println("\n[END]");
  }
}
