// Disassemble the quantizer decode+lookup and dump the snap table.
// @category Octatrack
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
public class GhidraArpTbl extends ghidra.app.script.GhidraScript {
    void listRange(long start,long end){
        Listing lst=currentProgram.getListing();
        InstructionIterator it=lst.getInstructions(toAddr(start),true);
        while(it.hasNext()){Instruction ins=it.next(); if(ins.getAddress().getOffset()>end)break;
            println(ins.getAddress()+":  "+ins.toString());}
    }
    public void run(){
        // decode region + lookup: find within FUN_4009f794 ~0x4009f9a0..0x4009fa30
        println("=== quantizer decode+lookup 0x4009f9a0..0x4009fa40 ===");
        listRange(0x4009f9a0L,0x4009fa40L);
    }
}
