import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.*;
import ghidra.program.model.listing.Function;
public class GhidraRef1 extends ghidra.app.script.GhidraScript {
  long[] T = { 0x400b5e45L, 0x400b39b7L };  // "RELOAD CUR BANK", "RELOAD BANK"
  public void run() throws Exception {
    ReferenceManager rm = currentProgram.getReferenceManager();
    for (long t: T){
      Address a=toAddr(t);
      println("=== refs to "+a+" ===");
      for (Reference r: rm.getReferencesTo(a)){
        Function f=getFunctionContaining(r.getFromAddress());
        println("  from "+r.getFromAddress()+" in "+(f==null?"<none>":f.getName()));
      }
      for (Address da: findBytes(null,String.format("%08x",t),8))
        println("  ptr-literal at "+da+"  in "+ (getFunctionContaining(da)==null?"<data>":getFunctionContaining(da).getName()));
    }
  }
}
