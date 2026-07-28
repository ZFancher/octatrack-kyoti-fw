//@category Octatrack
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
public class GhidraCrash extends GhidraScript {
  void around(long a,String tag) throws Exception {
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var lst=currentProgram.getListing();
    var fm=currentProgram.getFunctionManager();
    println("\n=== "+tag+" 0x"+Long.toHexString(a)+" ===");
    try{
      var f=fm.getFunctionContaining(sp.getAddress(a));
      println("  funcion: "+(f!=null?f.getName()+" @"+f.getEntryPoint():"(ninguna)"));
      var it=lst.getInstructions(new AddressSet(sp.getAddress(a-0x18),sp.getAddress(a+0x18)),true);
      while(it.hasNext()){
        var i=it.next();
        StringBuilder h=new StringBuilder();
        for(byte b:i.getBytes()) h.append(String.format("%02x",b));
        println(String.format("  %s%s  %-14s %s", i.getAddress().getOffset()==a?">>":"  ", i.getAddress(), h, i));
      }
    }catch(Exception e){ println("  (fuera de rango: "+e.getMessage()+")"); }
  }
  public void run() throws Exception {
    around(0x400c94caL,"si ADDR es absoluta con el 0x40 truncado");
    around(0x400c98caL,"si ADDR es offset en el archivo (base 0x40000400)");
    // quien salta a la zona que pise el detour del crossfader
    var sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
    var fm=currentProgram.getFunctionManager();
    println("\n=== referencias a las direcciones que pisa el detour del crossfader ===");
    for(long a: new long[]{0x4003f1b4L,0x4003f1b6L,0x4003f1b8L,0x4003f1baL,0x4003f1bcL}){
      var ri=currentProgram.getReferenceManager().getReferencesTo(sp.getAddress(a));
      int n=0;
      while(ri.hasNext()){
        var r=ri.next();
        var f=fm.getFunctionContaining(r.getFromAddress());
        println(String.format("  0x%x <- %s (%s) en %s",a,r.getFromAddress(),r.getReferenceType(),f!=null?f.getName():"?"));
        n++;
      }
      if(n==0) println(String.format("  0x%x <- (ninguna)",a));
    }
  }
}
