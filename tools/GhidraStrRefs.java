//@category Octatrack
// Redo the SELECT-string search with the reference manager (the scalar sweep has a
// blind spot for absolute-long operands and may have missed call sites).
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import java.util.*;
public class GhidraStrRefs extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    var lst=currentProgram.getListing();
    var rm=currentProgram.getReferenceManager();
    long[] a={0x400b7302L,0x400b484eL,0x400b730eL,0x400b7317L};
    String[] n={"SELECT BANK","SELECT PATTERN","BANK %c: SELECT PTN","SELECT PTN"};
    for(int i=0;i<a.length;i++){
      println("\n=== \""+n[i]+"\" @0x"+Long.toHexString(a[i])+" ===");
      var ri=rm.getReferencesTo(sp.getAddress(a[i]));
      int c=0;
      while(ri.hasNext()){
        var r=ri.next();
        var f=fm.getFunctionContaining(r.getFromAddress());
        var ins=lst.getInstructionAt(r.getFromAddress());
        println(String.format("  %s  %-30s %-12s en %s", r.getFromAddress(),
                ins!=null?ins.toString():"?", r.getReferenceType(), f!=null?f.getName():"(sin funcion)"));
        c++;
      }
      if(c==0) println("  (ninguna)");
    }
    println("\n[END]");
  }
}
