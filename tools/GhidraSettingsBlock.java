//@category Octatrack
// The PERSONALIZE flags are individual 32-bit words around 0x8000008c..0x800000d0.
// Which words in that block are referenced (in use) and which are dead? A dead word
// inside an already-serialized block would give persistence for free.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import java.util.*;
public class GhidraSettingsBlock extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    var rm=currentProgram.getReferenceManager();
    println("=== uso de cada palabra en 0x80000080..0x80000110 ===");
    for(long a=0x80000080L; a<=0x80000110L; a+=4){
      var ri=rm.getReferencesTo(sp.getAddress(a));
      List<String> f=new ArrayList<>();
      int n=0;
      while(ri.hasNext()){
        var r=ri.next(); n++;
        var fn=fm.getFunctionContaining(r.getFromAddress());
        String s=(fn!=null?fn.getName():"?")+(r.getReferenceType().isWrite()?"(W)":"");
        if(!f.contains(s)&&f.size()<4) f.add(s);
      }
      println(String.format("  0x%08x  refs=%-3d %s", a, n, n==0?"<<< LIBRE >>>":String.join(", ",f)));
    }
    println("\n[END]");
  }
}
