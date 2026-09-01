//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
public class GhidraFull extends GhidraScript {
  public void run() throws Exception {
    AddressSpace sp = currentProgram.getAddressFactory().getDefaultAddressSpace();
    FunctionManager fm = currentProgram.getFunctionManager();
    Listing lst = currentProgram.getListing();
    String[] args = getScriptArgs();
    long start = Long.parseLong(args[0],16), end = Long.parseLong(args[1],16);
    for (long a=start; a<end; ) {
      Address ad=sp.getAddress(a);
      Instruction i=lst.getInstructionAt(ad);
      if (i!=null){ println(String.format("%08x  %s", a, i.toString())); a+=i.getLength(); }
      else { println(String.format("%08x  .db", a)); a+=2; }
    }
    println("[END]");
  }
}
