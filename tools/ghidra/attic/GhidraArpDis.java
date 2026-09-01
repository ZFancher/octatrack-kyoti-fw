// Disassemble undefined regions that access the scale field.
// @category Octatrack
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
public class GhidraArpDis extends ghidra.app.script.GhidraScript {
    void listRange(long start,long end){
        Listing lst=currentProgram.getListing();
        InstructionIterator it=lst.getInstructions(toAddr(start),true);
        int n=0;
        while(it.hasNext()){
            Instruction ins=it.next();
            if(ins.getAddress().getOffset()>end) break;
            println(ins.getAddress()+":  "+ins.toString());
            if(++n>90) break;
        }
    }
    public void run(){
        println("=== 0x4007a7f0..0x4007a880 ===");
        listRange(0x4007a7f0L,0x4007a880L);
        println("\n=== 0x4007ae00..0x4007aec0 ===");
        listRange(0x4007ae00L,0x4007aec0L);
    }
}
