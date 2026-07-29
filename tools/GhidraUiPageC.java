//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraUiPageC extends GhidraScript {
  DecompInterface d; ConsoleTaskMonitor mon; AddressSpace sp; FunctionManager fm; ReferenceManager rm; Listing lst;
  void callers(long a){ Function f=fm.getFunctionContaining(sp.getAddress(a));
    println("\n== callers/refs of "+(f!=null?f.getName():"?")+" @0x"+Long.toHexString(a)+" ==");
    for(Reference r: rm.getReferencesTo(sp.getAddress(a))){ Function c=fm.getFunctionContaining(r.getFromAddress()); var ins=lst.getInstructionAt(r.getFromAddress());
      println("   "+r.getFromAddress()+" "+(ins!=null?ins:"")+" ["+r.getReferenceType()+"] in "+(c!=null?c.getName()+" @"+c.getEntryPoint():"?")); } }
  void dec(long a){ Function f=fm.getFunctionContaining(sp.getAddress(a)); if(f==null){println("no func @0x"+Long.toHexString(a));return;}
    var r=d.decompileFunction(f,150,mon); String c=r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)";
    println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" (0x"+Long.toHexString(a)+") ####"); println(c.length()>3200?c.substring(0,3200)+"..(trunc)":c); }
  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace(); fm=currentProgram.getFunctionManager();
    rm=currentProgram.getReferenceManager(); lst=currentProgram.getListing();
    d=new DecompInterface(); d.openProgram(currentProgram); mon=new ConsoleTaskMonitor();
    callers(0x40048844L);  // page increment
    callers(0x400486ccL);  // page clear
    callers(0x4004c6e0L);  // page set
    // FUN_40048844 and FUN_400486cc entry funcs
    dec(0x40048844L);
    dec(0x400486ccL);
    println("\n[END]");
  }
}
