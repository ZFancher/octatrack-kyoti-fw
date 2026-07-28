//@category Octatrack
// Map the LED id space: every call site of the LED setters with an immediate id,
// grouped by calling function. BANK / PTN should surface among the key ids.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import java.util.*;
public class GhidraLedIds extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    var lst=currentProgram.getListing();
    var rm=currentProgram.getReferenceManager();
    long[] led={0x400131a0L,0x400131c8L,0x400132c4L,0x400135b0L};
    String[] ln={"setBit","clrBit","set2bit","setLevel"};
    Map<String,TreeSet<Long>> byFn=new TreeMap<>();
    for(int k=0;k<led.length;k++){
      var ri=rm.getReferencesTo(sp.getAddress(led[k]));
      while(ri.hasNext()){
        var r=ri.next(); if(!r.getReferenceType().isCall()) continue;
        var f=fm.getFunctionContaining(r.getFromAddress());
        var ins=lst.getInstructionAt(r.getFromAddress());
        // walk back a few instructions looking for `pea (id).w`
        var p=ins;
        for(int i=0;i<4&&p!=null;i++){
          p=p.getPrevious(); if(p==null) break;
          if(!p.toString().startsWith("pea")) continue;
          for(int o=0;o<p.getNumOperands();o++)
            for(Object ob:p.getOpObjects(o))
              if(ob instanceof Scalar){
                long v=((Scalar)ob).getUnsignedValue();
                if(v<0x100){
                  String key=(f!=null?f.getName():"?")+" ["+ln[k]+"]";
                  byFn.computeIfAbsent(key,x->new TreeSet<>()).add(v);
                }
              }
        }
      }
    }
    println("=== ids inmediatos por funcion ===");
    for(var e:byFn.entrySet()) println("  "+e.getKey()+" -> "+e.getValue());
    println("\n[END]");
  }
}
