@ Process entry for QNX 6.5 ARM. These programs have no constructors, so only libc setup runs.
    .syntax unified
    .arch armv7-a
    .arm
    .text
    .global _start
    .type _start, %function
_start:
    mov   r4, r0              @ the loader's exit hook
    ldr   r0, [sp]            @ argc
    add   r1, sp, #4          @ argv
    add   r2, r1, r0, lsl #2
    add   r2, r2, #4          @ envp
    mov   r3, r2
1:  ldr   ip, [r3], #4
    teq   ip, #0
    bne   1b                  @ r3 ends on auxv
    mov   r5, r0
    mov   r6, r1
    mov   r7, r2
    sub   sp, sp, #8
    str   r4, [sp]            @ fifth argument of _init_libc
    bl    _init_libc
    add   sp, sp, #8
    mov   r0, r5
    mov   r1, r6
    mov   r2, r7
    blx   main
    bl    exit
2:  b     2b
    .size _start, . - _start

@ Names the compiler uses for block copies; each lands in the program only if something needs it.
    .section .text.aeabi_memcpy, "ax", %progbits
    .global __aeabi_memcpy, __aeabi_memcpy4, __aeabi_memcpy8
    .type __aeabi_memcpy, %function
    .type __aeabi_memcpy4, %function
    .type __aeabi_memcpy8, %function
__aeabi_memcpy:
__aeabi_memcpy4:
__aeabi_memcpy8:
    b     memcpy

    .section .text.aeabi_memclr, "ax", %progbits
    .global __aeabi_memclr, __aeabi_memclr4, __aeabi_memclr8
    .type __aeabi_memclr, %function
    .type __aeabi_memclr4, %function
    .type __aeabi_memclr8, %function
__aeabi_memclr:
__aeabi_memclr4:
__aeabi_memclr8:
    mov   r2, r1
    mov   r1, #0
    b     memset
