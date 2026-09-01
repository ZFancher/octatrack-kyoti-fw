//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraUiYesNo6 extends GhidraScript {
  DecompInterface d; ConsoleTaskMonitor mon; AddressSpace sp; FunctionManager fm; ReferenceManager rm; Listing lst;
  void refs(long a,String tag){ println("\n== refs to "+tag+" 0x"+Long.toHexString(a)+" ==");
    for(Reference r: rm.getReferencesTo(sp.getAddress(a))){ Function f=fm.getFunctionContaining(r.getFromAddress()); var ins=lst.getInstructionAt(r.getFromAddress());
      println("   "+(r.getReferenceType().isWrite()?"W":"r/c")+" "+r.getFromAddress()+" "+(ins!=null?ins:"")+" in "+(f!=null?f.getName()+" @"+f.getEntryPoint():"?")); } }
  void dec(long a,int lim){ Function f=fm.getFunctionContaining(sp.getAddress(a)); if(f==null){println("no func @0x"+Long.toHexString(a));return;}
    var r=d.decompileFunction(f,150,mon); String c=r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)";
    println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" (0x"+Long.toHexString(a)+") ####"); println(lim>0&&c.length()>lim?c.substring(0,lim)+"..(trunc)":c); }
  void raw(long s,long e){ println("\n== raw "+Long.toHexString(s)+".."+Long.toHexString(e)+" ==");
    var it=lst.getInstructions(new AddressSet(sp.getAddress(s),sp.getAddress(e)),true);
    while(it.hasNext()){ var i=it.next(); StringBuilder h=new StringBuilder();
      try{for(byte b:i.getBytes())h.append(String.format("%02x",b));}catch(Exception e2){}
      println(String.format("  %s  %-16s %s",i.getAddress(),h,i)); } }
  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace(); fm=currentProgram.getFunctionManager();
    rm=currentProgram.getReferenceManager(); lst=currentProgram.getListing();
    d=new DecompInterface(); d.openProgram(currentProgram); mon=new ConsoleTaskMonitor();
    raw(0x4006d4a8L,0x4006d4c8L);
    refs(0x460e5cd8L,"sel _DAT_460e5cd8"); refs(0x460e5cdcL,"sel _DAT_460e5cdc");
    println("\n===== project load complete callbacks =====");
    dec(0x40023420L,1800); dec(0x40023ab8L,1800); dec(0x4002574cL,2200); dec(0x40023510L,1500);
    println("[END]");
  }
}
