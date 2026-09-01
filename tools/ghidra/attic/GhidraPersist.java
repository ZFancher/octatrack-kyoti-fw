//@category Octatrack
// Several settings words have a reference from code/data with no function ("?").
// If that is a {key,address} descriptor table used by the project serializer, a new
// setting can persist by adding an entry. Find out what those references are.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
public class GhidraPersist extends GhidraScript {
  public void run() throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    var rm=currentProgram.getReferenceManager();
    var lst=currentProgram.getListing();
    for(long a: new long[]{0x800000d0L,0x800000ccL,0x800000c0L,0x800000acL}){
      println("\n=== refs a 0x"+Long.toHexString(a)+" ===");
      var ri=rm.getReferencesTo(sp.getAddress(a));
      while(ri.hasNext()){
        var r=ri.next();
        var f=fm.getFunctionContaining(r.getFromAddress());
        var ins=lst.getInstructionAt(r.getFromAddress());
        println(String.format("  desde %s  %-28s %-10s fn=%s", r.getFromAddress(),
                ins!=null?ins.toString():"(DATO, no instruccion)",
                r.getReferenceType(), f!=null?f.getName():"(ninguna)"));
      }
    }
    // si es una tabla de datos, mirar la vecindad de una de las refs sin funcion
    println("\n=== volcado alrededor de las refs sin funcion ===");
    var ri=rm.getReferencesTo(sp.getAddress(0x800000d0L));
    while(ri.hasNext()){
      var r=ri.next();
      if(fm.getFunctionContaining(r.getFromAddress())!=null) continue;
      long base=r.getFromAddress().getOffset()&~0xF;
      println("  contexto de 0x"+Long.toHexString(r.getFromAddress().getOffset())+":");
      var mem=currentProgram.getMemory();
      for(long o=base-0x20;o<base+0x30;o+=4){
        try{
          int v=mem.getInt(sp.getAddress(o));
          String tag="";
          if((v&0xFFFFFF00L)==0x80000000L) tag=" <- variable de ajuste";
          if(v>=0x400b0000&&v<=0x400d0000){
            StringBuilder s=new StringBuilder();
            for(int i=0;i<24;i++){ byte b=mem.getByte(sp.getAddress(v+i)); if(b==0)break; s.append((char)b);}
            tag=" \""+s+"\"";
          }
          println(String.format("    0x%08x: 0x%08x%s", o, v, tag));
        }catch(Exception e){}
      }
      break;
    }
    println("\n[END]");
  }
}
