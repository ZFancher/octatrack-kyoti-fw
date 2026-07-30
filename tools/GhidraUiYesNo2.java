//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraUiYesNo2 extends GhidraScript {
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
    println("===== popup accept/cancel dispatch =====");
    dec(0x4006d128L,1500); dec(0x4006d118L,1500);
    dec(0x40060f84L,1500); dec(0x4007eb18L,600);
    refs(0x4006d128L,"accept");
    // trace: who calls FUN_40060f84 / FUN_4007eb18 (the NO wrappers)
    refs(0x40060f84L,"FUN_40060f84"); refs(0x4007eb18L,"FUN_4007eb18");
    println("\n===== overlay FUN_400808bc + 'RELOADING BANK' 0x400b3898 =====");
    println("  str@0x400b3898 = '"+str(0x400b3898L)+"'");
    dec(0x400808bcL,3000);
    refs(0x400808bcL,"FUN_400808bc");
    println("\n===== project load: FUN_4008445c + writers of 0x100f8378 =====");
    refs(0x100f8378L,"projname 0x100f8378");
    println("[END]");
  }
}
