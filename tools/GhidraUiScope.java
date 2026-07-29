//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraUiScope extends GhidraScript {
  DecompInterface d; ConsoleTaskMonitor mon; AddressSpace sp; FunctionManager fm; ReferenceManager rm; Listing lst;
  void dec(long a,int limit){ Function f=fm.getFunctionContaining(sp.getAddress(a));
    if(f==null){println("\n#### @0x"+Long.toHexString(a)+": no func ####");return;}
    var r=d.decompileFunction(f,120,mon);
    String c=r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)";
    println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" (0x"+Long.toHexString(a)+") ####");
    println(limit>0&&c.length()>limit? c.substring(0,limit)+"\n  ...(trunc)":c); }
  void callers(long a){ Function f=fm.getFunctionContaining(sp.getAddress(a));
    println("-- callers of "+(f!=null?f.getName():"?")+" @0x"+Long.toHexString(a)+" --");
    if(f==null)return;
    for(Function c: f.getCallingFunctions(mon)) println("   "+c.getName()+" @"+c.getEntryPoint());
    for(Reference r: rm.getReferencesTo(f.getEntryPoint())){ Function c=fm.getFunctionContaining(r.getFromAddress());
      println("   ref "+r.getFromAddress()+" ["+r.getReferenceType()+"] in "+(c!=null?c.getName()+" @"+c.getEntryPoint():"?")); } }
  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    fm=currentProgram.getFunctionManager(); rm=currentProgram.getReferenceManager(); lst=currentProgram.getListing();
    d=new DecompInterface(); d.openProgram(currentProgram); mon=new ConsoleTaskMonitor();
    callers(0x4005e728L); // CLEAR PAGE
    callers(0x4005e94cL); // COPY PAGE
    callers(0x4005a918L); // RELOAD PAGE engine
    println("\n=== dispatcher(s) that call the PAGE ops (decompile) ===");
    // decompile the common caller once found; guess the copy/paste command dispatcher
    for(long a: new long[]{0x4005cbd8L}) dec(a, 5000);
    println("\n[END]");
  }
}
