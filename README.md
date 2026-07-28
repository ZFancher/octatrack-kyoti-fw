# Octatrack Firmware RE — Workspace (educational)

Reverse engineering for educational purposes of the **Elektron Octatrack** (MKI/MKII) firmware.
Goal: understand the OS format and the code that runs inside it. Do **not** redistribute Elektron binaries.

## State of the art (from prior research)

- **CPU:** Freescale/NXP **ColdFire** (prob. MCF54454, 32-bit, ~266 MHz) — derived from the **68000, big-endian**. It is NOT ARM.
- **Audio DSP:** Freescale **DSP56xxx** (processing separate from control).
- **Storage:** CompactFlash (FAT16/32); the OS boots/installs from the CF.
- **Firmware:** Official ZIP with `.bin` **and** `.syx` of the same OS. Internal payload = **compressed + checksums**, proprietary format (`ELE3/ELE2/ELEK/AD/pre-ELE` containers), **not strongly encrypted**.
- **Key tool:** [`mischa85/elektron-firmware-tool`](https://github.com/mischa85/elektron-firmware-tool) unpacks the `.syx` files.
- **Data formats already documented:** [`snugsound/OctaLib`](https://github.com/snugsound/OctaLib) (`Research.md`), `ot-tools-io` (Rust).

## Flow

```
./setup.sh      # installs binwalk, radare2; clones+compiles elektron-firmware-tool
./fetch-os.sh   # downloads the official OS 1.40C and extracts it
./analyze.sh    # entropy + binwalk + strings + elektron-firmware-tool -> out/
```

## Structure

```
tools/entropy.py   entropy scanner (numpy) — compressed/encrypted vs code
vendor/            third-party repos (elektron-firmware-tool)
downloads/         official OS downloaded + extracted (do NOT version)
out/               analysis results
NOTES.md           log of findings and next phase (Ghidra/radare2)
```

## Risk and legality

- Phase 0 (cold analysis of the public OS) = **zero risk to the hardware**, all static.
- Touching the PCB (UART/BDM/flash dump) = real risk of bricking; only if the static approach falls short.
- EU: Directive 2009/24/EC Art. 5 (observe/study/test) and Art. 6 (decompile only for interoperability).
- Review Elektron's EULA (anti-RE clauses are a contractual matter separate from copyright).
- Private and educational use: low risk. Publishing/redistributing changes the calculus. This is not legal advice.
