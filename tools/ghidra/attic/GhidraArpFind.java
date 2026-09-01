// Identify functions at the remaining scale-field accessors and the arp hub.
// Also bulk-scan arp/midi range for the scale decode (mod/div 12, *7).
// @category Octatrack
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.*;

public class GhidraArpFind extends ghidra.app.script.GhidraScript {
    DecompInterface dec;
    java.util.LinkedHashSet<Long> done = new java.util.LinkedHashSet<>();
    String decomp(Function f){
        try{ DecompileResults dr=dec.decompileFunction(f,90,monitor);
            if(dr!=null&&dr.getDecompiledFunction()!=null) return dr.getDecompiledFunction().getC(); }catch(Exception e){}
        return "";
    }
    void dumpAt(long a) throws Exception {
        Function f=getFunctionContaining(toAddr(a));
        println("addr "+Long.toHexString(a)+" -> "+(f==null?"NONE":f.getName()+" @"+f.getEntryPoint()));
        if(f!=null&&done.add(f.getEntryPoint().getOffset())){
            println("\n########## "+f.getName()+" @"+f.getEntryPoint()+" ##########");
            println(decomp(f));
        }
    }
    public void run() throws Exception {
        dec=new DecompInterface(); dec.openProgram(currentProgram);
        for(long a: new long[]{0x4007a824L,0x4007ae28L,0x4007aea6L,0x40029b9cL}) dumpAt(a);

        // Bulk scan: every function in 0x40029000..0x4002d000; report ones whose C
        // contains scale-decode hints combined with a small-table/mod.
        println("\n===== BULK SCAN arp engine 0x40029000-0x4002d000 =====");
        FunctionIterator it=currentProgram.getFunctionManager().getFunctions(toAddr(0x40029000L),true);
        while(it.hasNext()){
            Function f=it.next();
            long off=f.getEntryPoint().getOffset();
            if(off>=0x4002d000L) break;
            String c=decomp(f);
            boolean hit = c.contains("% 0xc")||c.contains("/ 0xc")||c.contains("% 7")||c.contains("* 7")
                        ||c.contains("0xab5")||c.contains("0x5ad")||c.contains(">> 1 & 1")
                        ||c.contains("% 0x7")||c.contains("0x460c8138")||c.contains("0x460bf22e");
            if(hit) println("HIT "+f.getName()+" @"+f.getEntryPoint());
        }
        println("===== bulk scan done =====");
    }
}
