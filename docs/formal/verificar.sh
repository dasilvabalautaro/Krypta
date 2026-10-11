#!/bin/sh
# Corre los modelos de Tamarin y comprueba que cada lema da lo que tiene que dar. Sale con error si
# alguno cambia: un modelo que de pronto «verifica» lo que antes falsificaba es tan sospechoso como
# uno que deja de verificar. Ver README.md.
set -u
cd "$(dirname "$0")"
command -v tamarin-prover >/dev/null || { echo "falta tamarin-prover en el PATH (ver README.md)"; exit 2; }

fallos=0
comprobar() { # modelo, flags, lema, esperado (verified|falsified)
    salida=$(tamarin-prover --prove="$3" $2 "$1" 2>&1 | grep -E "^  $3 \(" | head -1)
    case "$salida" in
        *"$4"*) echo "ok     $1 $2 $3: $4" ;;
        *) echo "FALLO  $1 $2 $3: esperaba $4, salió: ${salida:-nada}"; fallos=$((fallos + 1)) ;;
    esac
}

# M1, derivaciones v3: el secreto hacia adelante dentro de la época tiene que caer (W-15).
for l in ejecutable secreto propuesta_autentica secreto_dh acuerdo; do comprobar krypta_m1.spthy "" "$l" verified; done
comprobar krypta_m1.spthy "" fs_epoca_vigente falsified

# M1, derivaciones v4: el mismo lema tiene que sostenerse.
for l in ejecutable secreto propuesta_autentica secreto_dh fs_epoca_vigente acuerdo; do comprobar krypta_m1.spthy "-DV4" "$l" verified; done

# M2, regla del linaje: con S, el secuestro no tiene vuelta atrás (H-4).
comprobar krypta_m2.spthy "" ejecutable verified
comprobar krypta_m2.spthy "" recuperacion_con_S falsified
comprobar krypta_m2.spthy "" linaje_de_B_adoptado verified
comprobar krypta_m2.spthy "" recuperacion_honrada verified

# M2b, regla del linaje nueva (tope y relevo). sin_S se verifica suponiendo su auxiliar, que no cierra
# (ver README); el auxiliar no se comprueba aquí.
comprobar krypta_m2b.spthy "" ejecutable verified
comprobar krypta_m2b.spthy "" tope verified
comprobar krypta_m2b.spthy "" sin_S_solo_linajes_honrados verified

if [ "$fallos" -eq 0 ]; then echo "todo como se esperaba"; else echo "$fallos resultado(s) inesperado(s)"; exit 1; fi
