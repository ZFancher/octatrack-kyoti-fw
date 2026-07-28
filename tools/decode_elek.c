/* Harness: descomprime un contenedor ELEK crudo reutilizando ap_depack del
 * elektron-firmware-tool. Replica la llamada que hace main.c para CT_ELEK:
 *   seclen = be32(data + 0x12);  ap_depack(data + 0x12, seclen + 8, out, cap, 1)
 * Uso: decode_elek <elek_crudo.bin> <salida.bin> */
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>

size_t ap_depack(const uint8_t *src, size_t srclen, uint8_t *out, size_t outcap, int allow_trunc);

static uint32_t be32(const uint8_t *p){
    return ((uint32_t)p[0]<<24)|((uint32_t)p[1]<<16)|((uint32_t)p[2]<<8)|p[3];
}

#define ELEK_SECT_OFF 0x12
#define APLIB_SECT_HDR 8

int main(int argc, char **argv){
    if(argc<3){ fprintf(stderr,"uso: %s <elek.bin> <out.bin>\n",argv[0]); return 2; }
    FILE *f=fopen(argv[1],"rb");
    if(!f){ perror("open"); return 1; }
    fseek(f,0,SEEK_END); long n=ftell(f); fseek(f,0,SEEK_SET);
    uint8_t *buf=malloc(n);
    if(fread(buf,1,n,f)!=(size_t)n){ perror("read"); return 1; }
    fclose(f);

    if(buf[0]!='E'||buf[1]!='L'||buf[2]!='E'||buf[3]!='K'){
        fprintf(stderr,"[!] no empieza con 'ELEK'\n"); return 1;
    }
    uint32_t seclen=be32(buf+ELEK_SECT_OFF);
    size_t cap=64u<<20; uint8_t *out=malloc(cap);
    size_t dn=ap_depack(buf+ELEK_SECT_OFF, seclen+APLIB_SECT_HDR, out, cap, 1);
    fprintf(stderr,"seclen=%u  depacked=%zu bytes\n", seclen, dn);

    FILE *o=fopen(argv[2],"wb");
    fwrite(out,1,dn,o); fclose(o);
    return 0;
}
