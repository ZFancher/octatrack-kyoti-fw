// GhidraResolve41.java -- Session 5: prep to drive FUN_400a1eea (the per-step sequencer
// engine) in emu_trigbug.py. Need:
//   1. Every caller of FUN_400a1eea (0x400a1eea) with call-site context -> learn how a0 /
//      the other implicit inputs are set up (handoff: entry does `not.b (a0)`).
//   2. Raw disasm of FUN_400a1eea's first ~60 instructions (prologue + a0 use).
//   3. Every distinct jsr/bsr target inside FUN_400a1eea -> know what to stub in the emu.
//   4. Decompile FUN_40010bc8 (the MIDI-send primitive) -> its signature, so the harness
//      can decode note-on/off (the user's C / C# on steps 1 & 2).
//
// Run headless:
//   export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home
//   export PATH="$JAVA_HOME/bin:$PATH"
//   /opt/homebrew/Cellar/ghidra/12.1.2/libexec/support/analyzeHeadless \
//     ~/Documents/octamax/ghidra_project octamax -process "section_3_MAIN_OS.bin" -noanalysis \
//     -scriptPath ~/Documents/octamax/tools -postScript GhidraResolve41.java

import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.listing.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.*;

public class GhidraResolve41 extends GhidraScript {
    public void run() throws Exception {
        FunctionManager fm = currentProgram.getFunctionManager();
        Listing lst = currentProgram.getListing();

        Address tgt = currentProgram.getAddressFactory().getAddress("0x400a1eea");
        println("==================== callers of FUN_400a1eea ====================");
        ReferenceIterator refs = currentProgram.getReferenceManager().getReferencesTo(tgt);
        while (refs.hasNext()) {
            Reference r = refs.next();
            Address from = r.getFromAddress();
            Function cf = fm.getFunctionContaining(from);
            println("  from " + from + " in " + (cf!=null?cf.getName()+"@"+cf.getEntryPoint():"NOFUNC")
                + "  " + r.getReferenceType());
            Address cur = from;
            for (int i=0;i<20;i++){ Instruction p=getInstructionBefore(cur); if(p==null)break; cur=p.getAddress(); }
            for (int i=0;i<45;i++){
                Instruction cx=getInstructionAt(cur); if(cx==null)break;
                println((cx.getAddress().equals(from)?" >>> ":"     ")+cx.getAddress()+"  "+cx.toString());
                Instruction nx=getInstructionAfter(cur); if(nx==null)break;
                if(cx.getAddress().equals(from)){ for(int k=0;k<3;k++){ if(nx==null)break; println("     "+nx.getAddress()+"  "+nx.toString()); nx=getInstructionAfter(nx.getAddress()); } break; }
                cur=nx.getAddress();
            }
            println("");
        }

        println("\n==================== FUN_400a1eea prologue (first 64 insns) ====================");
        Function f = fm.getFunctionContaining(tgt);
        Instruction insn = getInstructionAt(f.getEntryPoint());
        for (int i=0;i<64 && insn!=null;i++){
            println("  "+insn.getAddress()+"  "+insn.toString());
            insn = getInstructionAfter(insn.getAddress());
        }

        println("\n==================== distinct call targets inside FUN_400a1eea ====================");
        TreeSet<String> targets = new TreeSet<>();
        insn = getInstructionAt(f.getEntryPoint());
        Address end = f.getBody().getMaxAddress();
        while (insn!=null && insn.getAddress().compareTo(end)<=0){
            String m = insn.getMnemonicString().toLowerCase();
            if (m.startsWith("jsr")||m.startsWith("bsr")){
                Address[] fl = insn.getFlows();
                if (fl!=null) for (Address t: fl){
                    Function tf = fm.getFunctionContaining(t);
                    targets.add(t + (tf!=null? " "+tf.getName() : " ?"));
                }
            }
            insn = getInstructionAfter(insn.getAddress());
        }
        for (String s: targets) println("  "+s);

        println("\n==================== decompile FUN_40010bc8 ====================");
        DecompInterface d = new DecompInterface(); d.openProgram(currentProgram);
        Function mf = fm.getFunctionContaining(currentProgram.getAddressFactory().getAddress("0x40010bc8"));
        if (mf!=null){
            println("Entry:"+mf.getEntryPoint()+" size:"+mf.getBody().getNumAddresses());
            DecompileResults res = d.decompileFunction(mf, 120, new ConsoleTaskMonitor());
            if (res!=null && res.decompileCompleted()) println(res.getDecompiledFunction().getC());
            else println("decompile failed");
        }
        d.dispose();
        println("\nDone.");
    }
}
