//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import java.util.*;
public class GhidraScan extends GhidraScript {
  public void run() throws Exception {
    AddressSpace sp = currentProgram.getAddressFactory().getDefaultAddressSpace();
    Listing lst = currentProgram.getListing();
    // recorre el core de audio 0x40009000-0x4000d000 y tally de mnemónicos "raros"
    Map<String,Integer> m = new TreeMap<>();
    long gaps=0, ins=0;
    for (long a=0x40009000L; a<0x4000d000L; ) {
      Address ad=sp.getAddress(a);
      Instruction i=lst.getInstructionAt(ad);
      if (i!=null){ String mn=i.getMnemonicString(); m.merge(mn,1,Integer::sum); a+=i.getLength(); ins++; }
      else { gaps++; a+=2; }
    }
    println("instrucciones: "+ins+"  huecos(2b): "+gaps);
    for (String k: new String[]{"mac","msac","mvz","mvs","mov3q","mvz.w:","mvs.w:","muls.l","mulu.l"})
      if(m.containsKey(k)) println("  "+k+": "+m.get(k));
    println("--- mnemónicos totales distintos: "+m.size());
  }
}
