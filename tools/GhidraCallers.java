import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.*;
import ghidra.program.model.listing.Function;
public class GhidraCallers extends ghidra.app.script.GhidraScript {
  long[] T = { 0x40022778L, 0x400a10c8L };
  public void run() throws Exception {
    ReferenceManager rm = currentProgram.getReferenceManager();
    for (long t: T){
      Address a=toAddr(t); int n=0;
      println("=== callers/refs to "+a+" ("+(getFunctionAt(a)!=null?getFunctionAt(a).getName():"?")+") ===");
      for (Reference r: rm.getReferencesTo(a)){
        if(!r.getReferenceType().isCall() && !r.getReferenceType().isJump() && !r.getReferenceType().isFlow()) continue;
        Function f=getFunctionContaining(r.getFromAddress());
        println("  "+r.getReferenceType()+" from "+r.getFromAddress()+" in "+(f==null?"<none>":f.getName()));
        n++;
      }
      println("  total flow refs: "+n);
    }
  }
}
