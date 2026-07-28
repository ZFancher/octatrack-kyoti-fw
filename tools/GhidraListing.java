//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
public class GhidraListing extends GhidraScript {
  public void run() throws Exception {
    AddressSpace sp = currentProgram.getAddressFactory().getDefaultAddressSpace();
    Listing lst = currentProgram.getListing();
    long start=0x4000c8c0L, end=0x4000ca40L;
    int good=0, bad=0;
    for (long a=start; a<end; ) {
      Address ad = sp.getAddress(a);
      Instruction ins = lst.getInstructionAt(ad);
      if (ins != null) {
        println(String.format("%08x  %-30s %s", a, ins.toString(), ins.getMnemonicString()));
        a += ins.getLength(); good++;
      } else {
        Data dat = lst.getDataAt(ad);
        println(String.format("%08x  [no-instr/data]", a));
        a += (dat!=null?dat.getLength():1); bad++;
      }
    }
    println("\n== instrucciones OK: "+good+" | huecos: "+bad+" ==");
  }
}
