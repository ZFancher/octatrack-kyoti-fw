//@category Octatrack
// Live bank paging RE. Answers:
//  - function containing 0x4007af4c (SELECT BANK window opener) + its callers (BANK key)
//  - FUN_4006d57c confirm popup + FUN_40063590 / FUN_40063bf8 (RELOAD BANK example)
//  - LED painter FUN_40083fb4
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraUiBankPage extends GhidraScript {
  DecompInterface d; ConsoleTaskMonitor mon; AddressSpace sp; FunctionManager fm; Listing lst; ReferenceManager rm;
  void dec(Function f, int limit){
    if(f==null){println("  <null func>");return;}
    var r=d.decompileFunction(f,120,mon);
    String c=r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)";
    println("\n######## "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ########");
    println(limit>0&&c.length()>limit? c.substring(0,limit)+"\n  ...(truncated)":c);
  }
  Function fat(long a){ return fm.getFunctionAt(sp.getAddress(a)); }
  Function fcont(long a){ return fm.getFunctionContaining(sp.getAddress(a)); }
  void callers(Function f){
    if(f==null)return;
    println("-- callers of "+f.getName()+" @"+f.getEntryPoint()+" --");
    Set<String> seen=new LinkedHashSet<>();
    for(Reference r: rm.getReferencesTo(f.getEntryPoint())){
      Function c=fcont(r.getFromAddress().getOffset());
      seen.add("   "+r.getFromAddress()+" ("+r.getReferenceType()+") in "+(c!=null?c.getName()+" @"+c.getEntryPoint():"<none>"));
    }
    for(String s:seen) println(s);
  }
  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing(); rm=currentProgram.getReferenceManager();
    d=new DecompInterface(); d.openProgram(currentProgram); mon=new ConsoleTaskMonitor();

    println("===== (1) SELECT BANK window opener: function containing 0x4007af4c =====");
    Function opener=fcont(0x4007af4cL);
    println("containing func = "+(opener!=null?opener.getName()+" @"+opener.getEntryPoint():"<NONE>"));
    dec(opener, 4000);
    if(opener!=null) callers(opener);

    println("\n===== (2) confirm popup FUN_4006d57c =====");
    dec(fat(0x4006d57cL), 4000);
    callers(fat(0x4006d57cL));

    println("\n===== (3) RELOAD BANK example builder FUN_40063590 + handler FUN_40063bf8 =====");
    dec(fat(0x40063590L), 3000);
    dec(fat(0x40063bf8L), 3000);

    println("\n===== (4) LED painter FUN_40083fb4 =====");
    dec(fat(0x40083fb4L), 3000);

    println("\n[END]");
  }
}
