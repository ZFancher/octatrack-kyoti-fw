//@category Octatrack
// Something reverts per_track_part[] after the encoder patch sets it. Who writes the
// array at 0x8000182a (and per_track_pattern at 0x80001832)?
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
public class GhidraTrkPart extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    var lst=currentProgram.getListing();
    var rm=currentProgram.getReferenceManager();
    for(long base: new long[]{0x8000182aL,0x80001832L}){
      println("\n=== escrituras en el arreglo @0x"+Long.toHexString(base)+" (8 bytes) ===");
      for(long a=base;a<base+8;a++){
        var ri=rm.getReferencesTo(sp.getAddress(a));
        while(ri.hasNext()){
          var r=ri.next();
          if(!r.getReferenceType().isWrite()) continue;
          var f=fm.getFunctionContaining(r.getFromAddress());
          println(String.format("  +%d  %s  %-30s en %s", a-base, r.getFromAddress(),
                  lst.getInstructionAt(r.getFromAddress()), f!=null?f.getName():"(sin funcion)"));
        }
      }
      // accesos indexados: lea del base y luego store
      println("  -- lea/adda del base (acceso indexado) --");
      var ri=rm.getReferencesTo(sp.getAddress(base));
      while(ri.hasNext()){
        var r=ri.next();
        var ins=lst.getInstructionAt(r.getFromAddress());
        if(ins==null) continue;
        String s=ins.toString();
        if(s.startsWith("lea")||s.startsWith("adda")||s.startsWith("pea")||s.startsWith("movea")){
          var f=fm.getFunctionContaining(r.getFromAddress());
          println(String.format("     %s  %-30s en %s", r.getFromAddress(), s,
                  f!=null?f.getName():"(sin funcion)"));
        }
      }
    }
  }
}
