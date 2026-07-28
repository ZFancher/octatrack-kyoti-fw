//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
public class GhidraLed2 extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var lst=currentProgram.getListing();
    AddressSet set=new AddressSet(sp.getAddress(0x40062bf0L), sp.getAddress(0x40062cb0L));
    println("=== FUN_40061a94 : scene display block ===");
    var it=lst.getInstructions(set,true);
    while(it.hasNext()){ var i=it.next(); println("  "+i.getAddress()+"  "+i); }
    println("[END]");
  }
}
