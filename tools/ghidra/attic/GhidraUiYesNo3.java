//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraUiYesNo3 extends GhidraScript {
  DecompInterface d; ConsoleTaskMonitor mon; AddressSpace sp; FunctionManager fm; ReferenceManager rm; Listing lst; Memory mem;
  void refs(long a,String tag){ println("\n== refs to "+tag+" 0x"+Long.toHexString(a)+" ==");
    for(Reference r: rm.getReferencesTo(sp.getAddress(a))){ Function f=fm.getFunctionContaining(r.getFromAddress()); var ins=lst.getInstructionAt(r.getFromAddress());
      println("   "+(r.getReferenceType().isWrite()?"W":"r/c")+" "+r.getFromAddress()+" "+(ins!=null?ins:"")+" in "+(f!=null?f.getName()+" @"+f.getEntryPoint():"?")); } }
  void dec(long a,int lim){ Function f=fm.getFunctionContaining(sp.getAddress(a)); if(f==null){println("no func @0x"+Long.toHexString(a));return;}
    var r=d.decompileFunction(f,150,mon); String c=r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)";
    println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" (0x"+Long.toHexString(a)+") ####"); println(lim>0&&c.length()>lim?c.substring(0,lim)+"..(trunc)":c); }
  String str(long a){ try{ StringBuilder b=new StringBuilder(); for(int i=0;i<48;i++){int c=mem.getByte(sp.getAddress(a+i))&0xff; if(c==0)break; if(c<9||c>126)return "<bin>"; b.append((char)c);} return b.toString(); }catch(Exception e){return "<err>";} }
  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace(); fm=currentProgram.getFunctionManager();
    rm=currentProgram.getReferenceManager(); lst=currentProgram.getListing(); mem=currentProgram.getMemory();
    d=new DecompInterface(); d.openProgram(currentProgram); mon=new ConsoleTaskMonitor();
    println("===== big UI handler FUN_40061a94 (calls NO->popup-cancel, reads projname) =====");
    dec(0x40061a94L,4500);
    refs(0x40061a94L,"FUN_40061a94");
    println("\n===== FUN_40061778 (keycode 0x28) =====");
    dec(0x40061778L,3500);
    println("\n===== overlay hide/tick: FUN_40080844 (close cb), FUN_400807a0 (tick) =====");
    dec(0x40080844L,1500); dec(0x400807a0L,2500);
    refs(0x460f790cL,"overlay handle 0x460f790c");
    println("\n===== overlay callers' strings =====");
    for(long c: new long[]{0x40023230L,0x400233e8L}) dec(c,1200);
    println("[END]");
  }
}
