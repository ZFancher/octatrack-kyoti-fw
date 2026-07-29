//@category Octatrack
// Who calls FUN_40052e98, and is it the only encoder-edit path? The encoder patch
// never fires while the LED (same condition, different track source) works.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraEnc extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    var lst=currentProgram.getListing();
    var f=fm.getFunctionAt(sp.getAddress(0x40052e98L));
    println("=== llamadores de FUN_40052e98 ===");
    List<String> o=new ArrayList<>();
    for(Function c:f.getCallingFunctions(new ConsoleTaskMonitor()))
      o.add("  "+c.getName()+" @"+c.getEntryPoint()+" ("+c.getBody().getNumAddresses()+"B)");
    Collections.sort(o);
    if(o.isEmpty()) println("  (via tabla)");
    for(String s:o) println(s);
    println("\n=== quien ESCRIBE DAT_100b14cc (track actual) ===");
    var rm=currentProgram.getReferenceManager();
    var ri=rm.getReferencesTo(sp.getAddress(0x100b14ccL));
    int w=0,r=0;
    while(ri.hasNext()){
      var x=ri.next();
      var fn=fm.getFunctionContaining(x.getFromAddress());
      if(x.getReferenceType().isWrite()){
        w++; println("  W "+x.getFromAddress()+"  "+lst.getInstructionAt(x.getFromAddress())
                     +"  en "+(fn!=null?fn.getName():"?"));
      } else r++;
    }
    println("  ("+w+" escrituras, "+r+" lecturas)");
  }
}
