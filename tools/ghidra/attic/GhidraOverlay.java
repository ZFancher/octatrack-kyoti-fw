//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
public class GhidraOverlay extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var lst=currentProgram.getListing();
    println("=== FUN_40034a44 : overlay de la seleccion de scene ===");
    var it=lst.getInstructions(new AddressSet(sp.getAddress(0x40034ae0L),sp.getAddress(0x40034b78L)),true);
    while(it.hasNext()){
      var i=it.next();
      StringBuilder h=new StringBuilder();
      for(byte b:i.getBytes()) h.append(String.format("%02x",b));
      println(String.format("  %s  %-16s %s", i.getAddress(), h, i));
    }
    println("=== prologo ===");
    var it2=lst.getInstructions(new AddressSet(sp.getAddress(0x40034a44L),sp.getAddress(0x40034a58L)),true);
    while(it2.hasNext()){
      var i=it2.next();
      StringBuilder h=new StringBuilder();
      for(byte b:i.getBytes()) h.append(String.format("%02x",b));
      println(String.format("  %s  %-16s %s", i.getAddress(), h, i));
    }
  }
}
