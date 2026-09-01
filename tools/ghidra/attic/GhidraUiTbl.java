//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.symbol.*;
import java.util.*;
public class GhidraUiTbl extends GhidraScript {
  AddressSpace sp; Memory mem; ReferenceManager rm; FunctionManager fm; Listing lst;
  int u16(long a) throws Exception { return mem.getShort(sp.getAddress(a))&0xffff; }
  long u32(long a) throws Exception { return mem.getInt(sp.getAddress(a))&0xffffffffL; }
  String str(long a){ try{ StringBuilder b=new StringBuilder(); for(int i=0;i<24;i++){int c=mem.getByte(sp.getAddress(a+i))&0xff; if(c==0)break; if(c<32||c>126){return null;} b.append((char)c);} return b.toString(); }catch(Exception e){return null;} }
  public void run() throws Exception {
    sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    mem=currentProgram.getMemory(); rm=currentProgram.getReferenceManager();
    fm=currentProgram.getFunctionManager(); lst=currentProgram.getListing();

    // Walk 0x1a-stride descriptors. handlers are the trailing 12 bytes of each 26-byte record.
    // Determine record start: desc0 handlers at 0x400c008a => record0 handler block start.
    println("=== descriptor array walk (stride 0x1a), from 0x400c0086 ===");
    for(long base=0x400c0086L; base<0x400c0200L; base+=0x1a){
      StringBuilder sb=new StringBuilder(String.format("@0x%08x: ",base));
      // dump 26 bytes hex
      for(int i=0;i<0x1a;i++){ sb.append(String.format("%02x",mem.getByte(sp.getAddress(base+i))&0xff)); if(i%2==1)sb.append(' '); }
      // find any embedded pointer to a string (0x400b0000..0x400d0000) within record
      for(int i=0;i<=0x1a-4;i++){ long v=u32(base+i); if(0x400b0000L<=v&&v<0x400d0000L){ String s=str(v); if(s!=null&&s.length()>1) sb.append(" str@+"+Integer.toHexString(i)+"='"+s+"'"); } }
      println(sb.toString());
    }

    println("\n=== all references pointing into 0x400c0080..0x400c0220 (dispatcher hunt) ===");
    AddressSet setr=new AddressSet(sp.getAddress(0x400c0080L),sp.getAddress(0x400c0220L));
    for(Address a: setr.getAddresses(true)){
      for(Reference r: rm.getReferencesTo(a)){
        Function f=fm.getFunctionContaining(r.getFromAddress());
        var ins=lst.getInstructionAt(r.getFromAddress());
        println("  ->"+a+" from "+r.getFromAddress()+" "+(ins!=null?ins:"(d)")+" ["+r.getReferenceType()+"] in "+(f!=null?f.getName()+" @"+f.getEntryPoint():"?"));
      }
    }
    println("\n[END]");
  }
}
