//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
public class GhidraRaw56ab8 extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var lst=currentProgram.getListing();
    println("=== FUN_40056ab8 crudo (el tick del contador) ===");
    var it=lst.getInstructions(new AddressSet(sp.getAddress(0x40056ab8L),sp.getAddress(0x40056b00L)),true);
    while(it.hasNext()){
      var i=it.next();
      StringBuilder h=new StringBuilder();
      for(byte b:i.getBytes()) h.append(String.format("%02x",b));
      println(String.format("  %s  %-16s %s", i.getAddress(), h, i));
    }
    println("\n=== FUN_40059f8c crudo: donde se escriben los campos ===");
    var i2=lst.getInstructions(new AddressSet(sp.getAddress(0x40059fe0L),sp.getAddress(0x4005a044L)),true);
    while(i2.hasNext()){
      var i=i2.next();
      StringBuilder h=new StringBuilder();
      for(byte b:i.getBytes()) h.append(String.format("%02x",b));
      println(String.format("  %s  %-16s %s", i.getAddress(), h, i));
    }
  }
}
