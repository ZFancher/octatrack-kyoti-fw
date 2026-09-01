//@category Octatrack
// Which RAM the patches claim is free, actually is? DEST_SNAP 0x80006e00..0x80006fff
// and CLEAN_MASK 0x80007000 were chosen without checking.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
public class GhidraRamFree extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    var rm=currentProgram.getReferenceManager();
    var lst=currentProgram.getListing();
    println("=== referencias en 0x80006800..0x80007100 (paso de 4) ===");
    long firstUsed=-1;
    for(long a=0x80006800L;a<0x80007100L;a+=4){
      var ri=rm.getReferencesTo(sp.getAddress(a));
      int n=0; StringBuilder who=new StringBuilder();
      while(ri.hasNext()){
        var r=ri.next(); n++;
        var f=fm.getFunctionContaining(r.getFromAddress());
        if(n<=2) who.append(" ").append(f!=null?f.getName():"?")
                     .append(r.getReferenceType().isWrite()?"(W)":"");
      }
      if(n>0){
        println(String.format("  0x%08x  refs=%-3d%s", a, n, who));
        if(firstUsed<0) firstUsed=a;
      }
    }
    println(firstUsed<0 ? "\n  ninguna referencia en todo el rango"
                        : String.format("\n  primera direccion usada: 0x%08x", firstUsed));
  }
}
