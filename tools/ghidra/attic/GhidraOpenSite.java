import ghidra.program.model.address.Address; import ghidra.program.model.listing.*;
public class GhidraOpenSite extends ghidra.app.script.GhidraScript { public void run() throws Exception {
  Listing l=currentProgram.getListing();
  Address a=toAddr(0x400906a0L);
  for(int i=0;i<14;i++){ Instruction ins=l.getInstructionAt(a); if(ins==null)break; println(a+"  "+ins); a=ins.getMaxAddress().next(); } } }
