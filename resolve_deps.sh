#!/bin/sh
# Resuelve recursivamente todas las dependencias de adb y las copia a /sdcard/adb_deps
DEST=/sdcard/adb_deps
mkdir -p "$DEST"

# Libs del sistema Android — no necesitan copiarse
skip() {
    case "$1" in
        libc.so|libm.so|libdl.so|liblog.so|libandroid.so|libc++.so) return 0 ;;
    esac
    return 1
}

copy_deps() {
    local src="$1"
    [ -f "$src" ] || return
    for dep in $(readelf -d "$src" 2>/dev/null | grep NEEDED | sed 's/.*\[\(.*\)\]/\1/'); do
        skip "$dep" && continue
        # sonames versionados: copiar con nombre seguro
        case "$dep" in
            *.so.*)
                safe=$(echo "$dep" | tr '.' '_')
                dest_file="$DEST/${safe}"
                src_file="$PREFIX/lib/$dep"
                ;;
            *)
                dest_file="$DEST/$dep"
                src_file="$PREFIX/lib/$dep"
                ;;
        esac
        [ -f "$dest_file" ] && continue   # ya procesado
        [ -f "$src_file" ]  || continue   # no existe en Termux
        cp "$src_file" "$dest_file"
        copy_deps "$src_file"             # recursión
    done
}

copy_deps "$PREFIX/bin/adb"
echo "Listo: $(ls $DEST | wc -l) librerias"
ls "$DEST"
