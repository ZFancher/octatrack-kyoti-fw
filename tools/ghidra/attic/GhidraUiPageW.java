//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraUiPageW extends GhidraScript {
  DecompInterface d; ConsoleTaskMonitor mon; AddressSpace sp; FunctionManager fm; ReferenceManager rm; Listing lst;
  void refsAll(long g){ println("\n== refs to 0x"+Long.toHexString(g)+" ==");
    for(Reference r: rm.getReferencesTo(sp.getAddress(g))){ Function f=fm.getFunctionContaining(r.getFromAddress()); var ins=lst.getInstructionAt(r.getFromAddress());
      println("   "+(r.getReferenceType().isWrite()?"W":"r")+" "+r.getFromAddress()+" "+(ins!=null?ins:"")+" in "+(f!=null?f.getName()+" @"+f.getEntryPoint():"?")); } }
  void dec(long a){ Function f=fm.getFunctionContaining(sp.getAddress(a)); if(f==null){println("no func @0x"+Long.toHexString(a));return;}
    var r=d.decompileFunction(f,150,mon); String c=r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)";
    println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" (0x"+Long.toHexString(a)+") ####"); println(c.length()>3000?c.substring(0,3000)+"..(trunc)":c); }
  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace(); fm=currentProgram.getFunctionManager();
    rm=currentProgram.getReferenceManager(); lst=currentProgram.getListing();
    d=new DecompInterface(); d.openProgram(currentProgram); mon=new ConsoleTaskMonitor();
    for(long g: new long[]{0x460d1e04L, 0x460d16fcL, 0x460d16f8L, 0x460d16f4L}) refsAll(g);
    println("\n===== decompile the writer(s) of 0x460d1e04 that look like key handlers =====");
    // fill in after seeing refs; also dump handlers 0x40030a6c family already known.
    println("[END]");
  }
}
