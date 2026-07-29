// @category Octatrack
import ghidra.program.model.listing.*;
public class GhidraArpTbl3 extends ghidra.app.script.GhidraScript {
    public void run(){
        Listing lst=currentProgram.getListing();
        InstructionIterator it=lst.getInstructions(toAddr(0x4009fa80L),true);
        while(it.hasNext()){Instruction ins=it.next(); if(ins.getAddress().getOffset()>0x4009fb02L)break;
            println(ins.getAddress()+":  "+ins.toString());}
    }
}
