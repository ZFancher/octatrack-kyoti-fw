#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
# SPDX-FileCopyrightText: 2026 Zac-Kyoti
"""
emu_reload2 -- the same emulator checks as emu_reload.py, pointed at the
scaled-down build (patch_reload2.s / build_reload2.py -> out/mainos_reload2.bin).

Thin shim: swaps emu_reload's image path + symbol source, and overrides
COMBO_ITEMS to the 2-item picker.  All the logic (cmd_combo modal test,
--slice / --strd / --patched) lives in emu_reload.py.

  --combo    single-step the Session-44 OT-native picker: hold [PTN] opens
             (rl_ptn) -> arrows move G_SEL -> rl_yes executes / rl_no cancels.
  --patched  boot out/mainos_reload2.bin and drive rl_yes end to end (SEQ worker).

    python3 tools/emu_reload2.py --combo
    python3 tools/emu_reload2.py --patched
"""
import pathlib
import subprocess
import sys

import emu_reload as erl

ROOT = pathlib.Path(__file__).resolve().parent.parent
_ELF = ROOT / "out" / "patch_reload2.elf"

erl.RELOAD_IMAGE = ROOT / "out" / "mainos_reload2.bin"

# the 2-item picker: (G_SEL, label, want_G_KIND, want_FUN_4004aab4_calls, want_seq_job_post)
erl.COMBO_ITEMS = [
    (0, "SEQ DATA",        1, 0, True),
    (1, "PART + SEQ DATA", 1, 1, True),
]


def _sym(name):
    nm = subprocess.run(["m68k-elf-nm", str(_ELF)], capture_output=True, text=True).stdout
    for ln in nm.splitlines():
        p = ln.split()
        if len(p) == 3 and p[2] == name:
            return int(p[0], 16)
    raise KeyError(name)


erl._sym = _sym

if __name__ == "__main__":
    if not _ELF.exists():
        sys.exit(f"missing {_ELF} -- run python3 tools/build_reload2.py first")
    erl.main()
