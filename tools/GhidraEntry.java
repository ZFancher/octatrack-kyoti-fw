//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
public class GhidraEntry extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var lst=currentProgram.getListing();
    long[] at={0x4003f1b4L,0x40009094L};
    for(long a:at){
      println("=== entry @"+Long.toHexString(a)+" ===");
      AddressSet s=new AddressSet(sp.getAddress(a), sp.getAddress(a+40));
      var it=lst.getInstructions(s,true);
      int tot=0;
      while(it.hasNext()){
        var i=it.next();
        StringBuilder hex=new StringBuilder();
        for(byte b:i.getBytes()) hex.append(String.format("%02x",b));
        println(String.format("  %s  %-24s %s", i.getAddress(), hex, i));
        tot+=i.getLength();
        if(tot>=14) { println("  (cumulative "+tot+" bytes)"); break; }
      }
    }
    println("[END]");
  }
}
