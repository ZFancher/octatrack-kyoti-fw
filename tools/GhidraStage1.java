//@category Octatrack
// Stage-1 groundwork:
//  A) which sites STORE to the scene A byte (offset 0 after `adda.l #0x8ed90,An`)
//     vs the scene B byte (offset 1) -- to find the manual scene-A writer
//  B) who WRITES DAT_80000003 (active pattern) -- the real pattern-change commit
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.scalar.Scalar;
import java.util.*;
public class GhidraStage1 extends GhidraScript {
  public void run() throws Exception {
    var fm=currentProgram.getFunctionManager();
    var lst=currentProgram.getListing();

    println("=== A) stores near `adda.l #0x8ed90,An` ===");
    var it=lst.getInstructions(true);
    while(it.hasNext()){
      Instruction ins=it.next();
      boolean hit=false;
      for(int o=0;o<ins.getNumOperands();o++)
        for(Object ob:ins.getOpObjects(o))
          if(ob instanceof Scalar && ((Scalar)ob).getUnsignedValue()==0x8ed90L) hit=true;
      if(!hit) continue;
      var f=fm.getFunctionContaining(ins.getAddress());
      // look ahead a few instructions for a byte store through the same register
      StringBuilder sb=new StringBuilder();
      Instruction p=ins;
      for(int i=0;i<6;i++){
        p=p.getNext(); if(p==null) break;
        String s=p.toString();
        if(s.startsWith("move.b")&&s.contains("(A")) sb.append("  >> ").append(p.getAddress()).append(" ").append(s);
      }
      if(sb.length()>0)
        println("  "+ins.getAddress()+" ["+ins+"] in "+(f!=null?f.getName():"?")+sb);
    }

    println("\n=== B) writes to DAT_80000003 (active pattern) ===");
    it=lst.getInstructions(true);
    int n=0;
    while(it.hasNext()){
      Instruction ins=it.next();
      String s=ins.toString();
      if(!s.contains("0x80000003")) continue;
      // a write has the address as destination operand
      boolean isWrite=s.matches("^(move|clr|st|sf|mvz|mvs)\\S*\\s+.*,\\s*\\(0x80000003\\)\\.l$")
                      || s.contains(",(0x80000003).l");
      var f=fm.getFunctionContaining(ins.getAddress());
      println("  "+(isWrite?"W":"r")+"  "+ins.getAddress()+"  "+s+"   in "+(f!=null?f.getName():"?"));
      if(++n>80){println("  ...");break;}
    }
    println("\n[END]");
  }
}
