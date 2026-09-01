// Full-program scan: find functions doing mod-12 / div-12 pitch-class math,
// or the scale-decode signature ((v-1)>>1 with &1), or reading the scale field.
// @category Octatrack
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;

public class GhidraArpScan12 extends ghidra.app.script.GhidraScript {
    DecompInterface dec;
    String decomp(Function f){
        try{ DecompileResults dr=dec.decompileFunction(f,60,monitor);
            if(dr!=null&&dr.getDecompiledFunction()!=null) return dr.getDecompiledFunction().getC(); }catch(Exception e){}
        return "";
    }
    public void run() throws Exception {
        dec=new DecompInterface(); dec.openProgram(currentProgram);
        FunctionIterator it=currentProgram.getFunctionManager().getFunctions(true);
        int total=0,hits=0;
        while(it.hasNext()){
            Function f=it.next(); total++;
            String c=decomp(f);
            boolean mod12 = c.contains("% 0xc")||c.contains("/ 0xc")||c.contains("% 12")||c.contains("* 0xc +")||c.contains("0xc)");
            boolean decode = c.contains(">> 1")&&(c.contains("& 1")||c.contains("& 0x1)"));
            boolean sc = c.contains("0x8f273")||c.contains("0x100a53c");
            if(mod12||sc){
                hits++;
                StringBuilder tags=new StringBuilder();
                if(c.contains("% 0xc"))tags.append("MOD12 ");
                if(c.contains("/ 0xc"))tags.append("DIV12 ");
                if(decode)tags.append("DECODE ");
                if(sc)tags.append("SCALEFIELD ");
                println("HIT "+f.getName()+" @"+f.getEntryPoint()+"  ["+tags+"]");
            }
        }
        println("scanned "+total+" funcs, "+hits+" hits");
    }
}
