// Check the 0xab5 immediate site and any function doing scale-mask math.
// @category Octatrack
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
public class GhidraMask extends ghidra.app.script.GhidraScript {
    DecompInterface dec;
    void ctx(long a){
        Listing lst=currentProgram.getListing();
        println("=== disasm around "+Long.toHexString(a)+" ===");
        InstructionIterator it=lst.getInstructions(toAddr(a-0x20),true);
        int n=0;
        while(it.hasNext()){Instruction ins=it.next(); if(ins.getAddress().getOffset()>a+0x20)break;
            println(ins.getAddress()+":  "+ins.toString()); if(++n>40)break;}
        Function f=getFunctionContaining(toAddr(a));
        println("func: "+(f==null?"NONE":f.getName()+" @"+f.getEntryPoint()));
        if(f!=null){try{DecompileResults dr=dec.decompileFunction(f,90,monitor);
            if(dr!=null&&dr.getDecompiledFunction()!=null) println(dr.getDecompiledFunction().getC());}catch(Exception e){}}
    }
    public void run(){
        dec=new DecompInterface(); dec.openProgram(currentProgram);
        for(long a: new long[]{0x400ae3c8L}) ctx(a);
    }
}
