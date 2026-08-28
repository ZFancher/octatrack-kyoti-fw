// GhidraResolve40.java -- Session 5: list every privileged / SR / CCR / interrupt-mask
// instruction (and any TRAP/STOP/RTE) in the functions the emu_trigbug.py harness executes,
// so the harness can NOP exactly those addresses instead of pattern-scanning a range.
//
// Run headless:
//   export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.12/libexec/openjdk.jdk/Contents/Home
//   export PATH="$JAVA_HOME/bin:$PATH"
//   /opt/homebrew/Cellar/ghidra/12.1.2/libexec/support/analyzeHeadless \
//     ~/Documents/octamax/ghidra_project octamax -process "section_3_MAIN_OS.bin" -noanalysis \
//     -scriptPath ~/Documents/octamax/tools -postScript GhidraResolve40.java

import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.address.Address;

public class GhidraResolve40 extends GhidraScript {
    public void run() throws Exception {
        long[][] ranges = {
            {0x40044584L, 0x40044804L},
            {0x4009b280L, 0x4009b294L},   // FUN_4009b290
            {0x4009b5c8L, 0x4009b95cL},   // FUN_4009b5c8 + split tail
            {0x4009f2f8L, 0x4009f5c0L},   // FUN_4009f2f8 + FUN_4009f3a4 + split tail
            {0x400a539cL, 0x400a5400L},   // FUN_400a539c
            {0x400a1eeaL, 0x400a4200L},   // FUN_400a1eea (step engine)
        };
        for (long[] r : ranges) {
            Address a = currentProgram.getAddressFactory().getAddress(Long.toHexString(r[0]));
            Address end = currentProgram.getAddressFactory().getAddress(Long.toHexString(r[1]));
            println(String.format("---- %08x .. %08x ----", r[0], r[1]));
            Instruction insn = getInstructionAt(a);
            if (insn == null) insn = getInstructionAfter(a);
            while (insn != null && insn.getAddress().compareTo(end) < 0) {
                String s = insn.toString();
                String m = insn.getMnemonicString().toLowerCase();
                boolean hit = s.contains("SR") || s.contains("CCR")
                    || m.equals("stop") || m.equals("rte") || m.equals("trap")
                    || m.equals("trapf") || m.equals("halt") || m.startsWith("move")
                       && (s.contains(",SR") || s.contains("SR,") || s.contains(",CCR") || s.contains("CCR,"));
                if (hit) {
                    byte[] b = insn.getBytes();
                    StringBuilder hex = new StringBuilder();
                    for (byte x : b) hex.append(String.format("%02x", x & 0xff));
                    println(String.format("  %s  len=%d  %-24s  bytes=%s",
                        insn.getAddress(), insn.getLength(), s, hex));
                }
                insn = getInstructionAfter(insn.getAddress());
            }
        }
        println("Done.");
    }
}
