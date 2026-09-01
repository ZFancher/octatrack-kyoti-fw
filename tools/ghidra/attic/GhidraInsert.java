//@category Octatrack
// Register/stack detail at the two stage-2 insertion points:
//  - FUN_40083eb0 loop tail (LAB_40083fa0): track LED id / state / index registers
//  - FUN_40034a44 tail: where local_80[]/local_100[] are pushed to the LED family
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
public class GhidraInsert extends GhidraScript {
  void listing(long lo,long hi,String tag) throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var lst=currentProgram.getListing();
    println("\n=== "+tag+" 0x"+Long.toHexString(lo)+"..0x"+Long.toHexString(hi)+" ===");
    var it=lst.getInstructions(new AddressSet(sp.getAddress(lo),sp.getAddress(hi)),true);
    while(it.hasNext()){
      var i=it.next();
      StringBuilder hex=new StringBuilder();
      for(byte b:i.getBytes()) hex.append(String.format("%02x",b));
      println(String.format("  %s  %-16s %s", i.getAddress(), hex, i));
    }
  }
  public void run() throws Exception {
    listing(0x40083f90L,0x40083fd8L,"FUN_40083eb0 loop tail");
    listing(0x40034b70L,0x40034bd4L,"FUN_40034a44 tail");
  }
}
