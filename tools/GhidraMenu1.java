// GhidraMenu1.java -- deep dive on the PERSONALIZE menu: arrays, renderer, input
// handler, and especially the multi-value LED BRIGHTNESS getter/setter (the model
// for a MUTE MODE OT / OT+FX / ... value list).
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraMenu1 extends GhidraScript {
    DecompInterface dec; ConsoleTaskMonitor mon = new ConsoleTaskMonitor();
    FunctionManager fm; AddressSpace sp; Memory mem;
    LinkedHashSet<Long> dumped = new LinkedHashSet<>();

    long u32(long a) throws Exception { return mem.getInt(sp.getAddress(a)) & 0xffffffffL; }
    String cstr(long a){ try{ StringBuilder b=new StringBuilder(); for(int i=0;i<48;i++){ int c=mem.getByte(sp.getAddress(a+i))&0xff; if(c==0)break; b.append((char)c);} return b.toString(); }catch(Exception e){ return "<?>"; } }
    String decomp(Function f){ try{ DecompileResults dr=dec.decompileFunction(f,200,mon);
        if(dr!=null&&dr.getDecompiledFunction()!=null) return dr.getDecompiledFunction().getC(); }catch(Exception e){} return "  <fail>"; }
    void ensure(long a){ Address ad=sp.getAddress(a); if(fm.getFunctionContaining(ad)==null){
        try{ disassemble(ad); createFunction(ad,null); }catch(Exception e){} } }
    void dumpAt(long a,String tag){
        ensure(a);
        Function f=fm.getFunctionContaining(sp.getAddress(a));
        if(f==null){println("\n// no fn @0x"+Long.toHexString(a)+" ("+tag+")");return;}
        if(!dumped.add(f.getEntryPoint().getOffset())){println("// (dup "+f.getName()+" via "+Long.toHexString(a)+" "+tag+")");return;}
        println("\n########## "+f.getName()+" @"+f.getEntryPoint()+" size="+f.getBody().getNumAddresses()+" (via 0x"+Long.toHexString(a)+" "+tag+") ##########");
        println(decomp(f));
    }
    void disRange(long lo,long hi){
        println("\n----- disasm 0x"+Long.toHexString(lo)+" .. 0x"+Long.toHexString(hi)+" -----");
        try{ disassemble(sp.getAddress(lo)); }catch(Exception e){}
        Listing l=currentProgram.getListing();
        InstructionIterator it=l.getInstructions(sp.getAddress(lo),true);
        while(it.hasNext()){ Instruction in=it.next(); if(in.getAddress().getOffset()>hi)break;
            StringBuilder bs=new StringBuilder();
            try{ for(byte b: in.getBytes()) bs.append(String.format("%02x",b&0xff)); }catch(Exception e){}
            println(String.format("  %08x  %-20s %s", in.getAddress().getOffset(), bs.toString(), in.toString())); }
    }
    void dumpArray(long base,int n,String tag){
        println("\n===== array "+tag+" @0x"+Long.toHexString(base)+"  ("+n+" entries) =====");
        for(int i=0;i<n;i++){ try{ long v=u32(base+i*4L);
            String extra=""; Function f=fm.getFunctionContaining(sp.getAddress(v));
            if(f!=null) extra=" fn "+f.getName();
            println(String.format("  [%2d] 0x%08x  \"%s\"%s", i, v, cstr(v), extra));
        }catch(Exception e){ println("  ["+i+"] <err>"); } }
    }

    public void run() throws Exception {
        fm=currentProgram.getFunctionManager();
        sp=currentProgram.getAddressFactory().getDefaultAddressSpace();
        mem=currentProgram.getMemory();
        dec=new DecompInterface(); dec.openProgram(currentProgram);

        long LBL=0x400b2a34L, GET=0x400b2a74L, LEDV=0x400b2ab4L, SET=0x400b2ac0L;
        dumpArray(LBL,17,"labels");
        dumpArray(GET,16,"getters");
        dumpArray(LEDV,3,"LED BRIGHTNESS values");
        dumpArray(SET,16,"setters");

        // glyph area
        println("\n===== glyph strings =====");
        for(long a: new long[]{0x400b5e8eL,0x400b5e90L,0x400b5e63L})
            println(String.format("  0x%08x  \"%s\"", a, cstr(a)));

        // the menu machinery
        disRange(0x40068ee0L, 0x40069070L);
        dumpAt(0x40068fa8L,"list init FUN_40068fa8");
        dumpAt(0x40068e00L,"renderer FUN_40068e00");
        dumpAt(0x40068fd0L,"input handler FUN_40068fd0");
        dumpAt(0x400685ccL,"enter/init FUN_400685cc");

        // multi-value getter/setter: LED BRIGHTNESS is entry [15]
        long ledGet=u32(GET+15*4L), ledSet=u32(SET+15*4L);
        dumpAt(ledGet,"LED BRIGHTNESS getter");
        dumpAt(ledSet,"LED BRIGHTNESS setter");
        // a couple of plain checkbox ones for contrast
        dumpAt(u32(GET+0*4L),"getter[0]");
        dumpAt(u32(SET+0*4L),"setter[0]");
        dumpAt(u32(GET+1*4L),"getter[1]");
        dumpAt(u32(SET+1*4L),"setter[1]");

        // list helper used by init
        dumpAt(0x4007ec60L,"list ctor FUN_4007ec60");

        dec.dispose(); println("\n[GhidraMenu1] done.");
    }
}
