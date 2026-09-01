//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;
public class GhidraUiPage extends GhidraScript {
  DecompInterface d; ConsoleTaskMonitor mon; AddressSpace sp; FunctionManager fm; Listing lst; ReferenceManager rm; Memory mem;
  Function ensure(long a){ Address ad=sp.getAddress(a); Function f=fm.getFunctionContaining(ad);
    if(f==null){ try{ disassemble(ad); f=createFunction(ad,null);}catch(Exception e){} } return f; }
  void dec(long a,int limit){ Function f=ensure(a);
    if(f==null){println("\n#### @0x"+Long.toHexString(a)+": no func ####");return;}
    var r=d.decompileFunction(f,120,mon);
    String c=r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)";
    println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" (0x"+Long.toHexString(a)+") ####");
    println(limit>0&&c.length()>limit? c.substring(0,limit)+"\n  ...(trunc)":c); }
  String str(long a){ try{ StringBuilder b=new StringBuilder(); for(int i=0;i<40;i++){int c=mem.getByte(sp.getAddress(a+i))&0xff; if(c==0)break; if(c<9||c>126)return "<bin>"; b.append((char)c);} return b.toString(); }catch(Exception e){return "<err>";} }
  void refs(long a){ println("-- refs to 0x"+Long.toHexString(a)+" --");
    for(Reference r: rm.getReferencesTo(sp.getAddress(a))){ Function f=fm.getFunctionContaining(r.getFromAddress()); var ins=lst.getInstructionAt(r.getFromAddress());
      println("   "+r.getFromAddress()+" "+(ins!=null?ins:"(d)")+" ["+r.getReferenceType()+"] in "+(f!=null?f.getName()+" @"+f.getEntryPoint():"?")); } }
  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing(); rm=currentProgram.getReferenceManager(); mem=currentProgram.getMemory();
    d=new DecompInterface(); d.openProgram(currentProgram); mon=new ConsoleTaskMonitor();

    println("=== resolve descriptor data pointers ===");
    for(long p: new long[]{0x400b9e02L,0x400bfbfcL,0x400bfa32L})
      println(String.format("  0x%08x -> '%s'",p,str(p)));

    println("\n=== keycode 0x2d handlers (PAGE candidate) ===");
    dec(0x4004e954L, 6000);
    dec(0x400568e4L, 1500);

    println("\n=== keycode 0x34/0x21 handler FUN_4004b970 (full) ===");
    dec(0x4004b970L, 6000);

    println("\n=== dispatcher hunt via 0x400c0500 chain ===");
    refs(0x400c0500L); refs(0x400f1b10L); refs(0x400c0922L); refs(0x400c01f4L);
    println("  bytes @0x400c04f0..0x400c0520:");
    StringBuilder sb=new StringBuilder();
    for(long a=0x400c04f0L;a<0x400c0520L;a++){ sb.append(String.format("%02x",mem.getByte(sp.getAddress(a))&0xff)); if((a&1)==1)sb.append(' ');}
    println("   "+sb);
    println("\n[END]");
  }
}
