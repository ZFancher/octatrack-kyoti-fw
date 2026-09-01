//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
public class GhidraUiPageId extends GhidraScript {
  DecompInterface d; ConsoleTaskMonitor mon; AddressSpace sp; FunctionManager fm; ReferenceManager rm; Listing lst; Memory mem;
  Function ensure(long a){ Address ad=sp.getAddress(a); Function f=fm.getFunctionContaining(ad);
    if(f==null){ try{ disassemble(ad); f=createFunction(ad,null);}catch(Exception e){} } return f; }
  void dec(long a,int limit){ Function f=ensure(a);
    if(f==null){println("\n#### @0x"+Long.toHexString(a)+": no func ####");return;}
    var r=d.decompileFunction(f,120,mon);
    String c=r!=null&&r.decompileCompleted()?r.getDecompiledFunction().getC():"(no-C)";
    println("\n#### "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" (0x"+Long.toHexString(a)+") ####");
    println(limit>0&&c.length()>limit? c.substring(0,limit)+"\n  ...(trunc)":c); }
  void refs(long a,String tag){ println("-- refs to "+tag+" 0x"+Long.toHexString(a)+" --");
    for(Reference r: rm.getReferencesTo(sp.getAddress(a))){ Function f=fm.getFunctionContaining(r.getFromAddress()); var ins=lst.getInstructionAt(r.getFromAddress());
      println("   "+r.getFromAddress()+" "+(ins!=null?ins:"(d)")+" in "+(f!=null?f.getName()+" @"+f.getEntryPoint():"?")); } }
  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    fm=currentProgram.getFunctionManager(); rm=currentProgram.getReferenceManager(); lst=currentProgram.getListing(); mem=currentProgram.getMemory();
    d=new DecompInterface(); d.openProgram(currentProgram); mon=new ConsoleTaskMonitor();

    println("=== scancode map around 0x400abcc0..0x400abd00 ===");
    StringBuilder sb=new StringBuilder();
    for(long a=0x400abcc0L;a<0x400abd00L;a++) sb.append(String.format("%02x ",mem.getByte(sp.getAddress(a))&0xff));
    println("  "+sb);

    // find who references "COPY PAGE"/"CLEAR PAGE" strings (menu builders)
    refs(0x400b48d8L,"COPY PAGE"); refs(0x400b48c2L,"CLEAR PAGE"); refs(0x400b489dL,"RELOAD PAGE");

    // remaining candidate handlers to fingerprint
    println("\n=== fingerprint remaining handlers ===");
    for(long a: new long[]{0x40030a6cL,0x4005578cL,0x4002e7c8L,0x40040250L})
      dec(a,1400);
    println("\n[END]");
  }
}
