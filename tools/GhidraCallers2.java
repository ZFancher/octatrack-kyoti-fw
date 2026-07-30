import ghidra.program.model.address.Address; import ghidra.program.model.symbol.*; import ghidra.program.model.listing.Function;
public class GhidraCallers2 extends ghidra.app.script.GhidraScript { public void run() throws Exception {
  ReferenceManager rm=currentProgram.getReferenceManager();
  for (long t: new long[]{0x4006d128L,0x4006d118L}) { Address a=toAddr(t); println("-> "+a+":");
    for (Reference r: rm.getReferencesTo(a)) if(r.getReferenceType().isCall()){ Function f=getFunctionContaining(r.getFromAddress()); println("   call @"+r.getFromAddress()+" in "+(f==null?"?":f.getName())); } } } }
