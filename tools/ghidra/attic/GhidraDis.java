//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
public class GhidraDis extends GhidraScript {
  public void run() throws Exception {
    String[] a=getScriptArgs();
    long s=Long.parseLong(a[0].replace("0x",""),16), e=Long.parseLong(a[1].replace("0x",""),16);
    Listing l=currentProgram.getListing();
    InstructionIterator it=l.getInstructions(toAddr(s),true);
    while(it.hasNext()){ Instruction i=it.next(); if(i.getAddress().getOffset()>e) break;
      println(i.getAddress()+": "+i.toString()); }
  }
}
