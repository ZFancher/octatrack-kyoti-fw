//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraScene extends GhidraScript {
  DecompInterface dec; ConsoleTaskMonitor mon; FunctionManager fm; AddressSpace sp;
  Set<Long> seen=new HashSet<>();

  void dump(Function f, String tag) throws Exception {
    if(f==null)return;
    if(!seen.add(f.getEntryPoint().getOffset()))return;
    DecompileResults r=dec.decompileFunction(f,120,mon);
    println("\n#### "+f.getName()+" @ "+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" ("+tag+") ####");
    println(r!=null&&r.decompileCompleted()? r.getDecompiledFunction().getC():"  (no-C)");
  }

  // writers/readers of a data address
  void refs(long va, String label, boolean writeOnly) throws Exception {
    Address sa=sp.getAddress(va);
    println("\n===== refs to "+label+" @ "+sa+(writeOnly?" (WRITE)":"")+" =====");
    ReferenceIterator it=currentProgram.getReferenceManager().getReferencesTo(sa);
    List<Function> fns=new ArrayList<>();
    int n=0;
    while(it.hasNext()){
      Reference rf=it.next();
      boolean isW=rf.getReferenceType().isWrite();
      if(writeOnly && !isW) continue;
      Address from=rf.getFromAddress();
      Function f=fm.getFunctionContaining(from);
      println("  "+rf.getReferenceType()+" from "+from+" in "+(f!=null?f.getName():"?"));
      if(f!=null) fns.add(f);
      if(++n>60) break;
    }
    if(n==0) println("  (none)");
    for(Function f:fns) dump(f, "ref-"+label);
  }

  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    fm=currentProgram.getFunctionManager();
    dec=new DecompInterface(); dec.openProgram(currentProgram);
    mon=new ConsoleTaskMonitor();

    String tgt=System.getenv("SCENE_TGT");
    if(tgt==null) tgt="writers02";
    if(tgt.equals("writers02")){
      refs(0x80000002L, "active_part", true);
    } else if(tgt.equals("serialize")){
      // the function containing the SCENE_A_MUTE pea (0x40087f50)
      Function f=fm.getFunctionContaining(sp.getAddress(0x40087f50L));
      dump(f, "scene_serializer");
    }
    println("\n[END]");
  }
}
