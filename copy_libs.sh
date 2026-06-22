#!/bin/sh
L=$PREFIX/lib
cp $L/libabsl_flags_internal.so /sdcard/
cp $L/libprotobuf.so /sdcard/
cp $L/libbrotlidec.so /sdcard/
cp $L/libbrotlienc.so /sdcard/
cp $L/liblz4.so /sdcard/
cp $L/libc++_shared.so /sdcard/
cp $L/libz.so.1 /sdcard/libz_so_1
cp $L/libzstd.so.1 /sdcard/libzstd_so_1
echo "Listo"
