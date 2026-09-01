//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraScene4 extends GhidraScript {
  DecompInterface dec; ConsoleTaskMonitor mon; FunctionManager fm; AddressSpace sp;
  Set<Long> seen=new HashSet<>();
  void dump(Function f,String tag)throws Exception{
    if(f==null)return; if(!seen.add(f.getEntryPoint().getOffset()))return;
    DecompileResults r=dec.decompileFunction(f,150,mon);
    println("\n#### "+f.getName()+" @ "+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ("+tag+") ####");
    println(r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"  (no-C)");
  }
  void refs(long va,String label,boolean wo)throws Exception{
    Address sa=sp.getAddress(va);
    println("\n===== refs to "+label+" @ "+sa+(wo?" WRITE":"")+" =====");
    ReferenceIterator it=currentProgram.getReferenceManager().getReferencesTo(sa);
    List<Function> fns=new ArrayList<>(); int n=0;
    while(it.hasNext()){Reference rf=it.next(); if(wo&&!rf.getReferenceType().isWrite())continue;
      Address fr=rf.getFromAddress(); Function f=fm.getFunctionContaining(fr);
      println("  "+rf.getReferenceType()+" from "+fr+" in "+(f!=null?f.getName():"?"));
      if(f!=null)fns.add(f); if(++n>80)break;}
    if(n==0)println("  (none)");
    for(Function f:fns) dump(f,"w-"+label);
  }
  public void run()throws Exception{
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    fm=currentProgram.getFunctionManager();
    dec=new DecompInterface(); dec.openProgram(currentProgram); mon=new ConsoleTaskMonitor();
    refs(0x80000003L,"active_pattern",true);
    println("\n[END]");
  }
}
