# ECG Releases — Distribución interna BioGW

App Android de distribución interna para el proyecto **BioGW** (Universidad de Jaén). Permite consultar la última versión publicada en GitHub Releases, descargar los APKs (con la versión en el nombre del archivo) e instalar el APK del reloj directamente desde el móvil vía **ADB WiFi**, sin necesidad de ninguna app externa.

---

## Características

- Consulta automática de la última versión en [GitHub Releases](https://github.com/linares2002/ECG-arteria/releases)
- Descarga de `biogw-<versión>.apk` (teléfono) y `watch-<versión>.apk` (reloj) con `DownloadManager`
- Instalación del APK en **Samsung Galaxy Watch** vía ADB WiFi embebido
- Descubrimiento automático del reloj por **mDNS** (`_adb-tls-pairing._tcp` / `_adb-tls-connect._tcp`)
- Conexión directa si ya existe un emparejamiento previo (sin pedir código)
- Entrada manual de IP / puerto / código como fallback
- Panel de log en tiempo real con toggle y botón limpiar
- Sin dependencias externas: el binario ADB va embebido en el APK

---

## Requisitos

| Componente | Versión mínima |
|---|---|
| Android | 8.0 (API 26) |
| Samsung Galaxy Watch | One UI Watch con **Depuración inalámbrica** disponible |
| Arquitectura dispositivo | ARM64 (arm64-v8a) |

---

## Flujo de instalación en el reloj

```
Móvil y reloj en la misma red WiFi
        ↓
Reloj: Ajustes → Opciones de desarrollador
       → Depuración inalámbrica → Emparejar nuevo dispositivo
        ↓
App detecta el reloj por mDNS
        ↓
  ¿Emparejamiento previo?
    Sí → conecta directamente
    No → introduce código de 6 dígitos → adb pair → adb connect
        ↓
adb install -r watch.apk
```

---

## Arquitectura del proyecto

```
app/src/main/
├── java/com/samsung/versiones/
│   ├── MainActivity.java          # Pestañas Teléfono/Reloj, descarga APKs
│   ├── WatchInstallActivity.java  # Flujo ADB WiFi completo
│   ├── AdbWifiManager.java        # Wrapper ProcessBuilder sobre libadb.so
│   ├── AppState.java              # Estados del flujo de instalación
│   └── AdbResult.java             # DTO {success, output} de cada comando ADB
├── jniLibs/arm64-v8a/
│   ├── libadb.so                  # Binario ADB ARM64 (compilado con bionic)
│   └── lib*.so                    # Dependencias dinámicas de libadb.so
└── AndroidManifest.xml
```

### ADB embebido

El binario ADB se distribuye como `libadb.so` en `jniLibs/arm64-v8a/`. Android lo extrae automáticamente a `nativeLibraryDir` (que es ejecutable). El `ProcessBuilder` lo invoca con:

- `HOME` → `filesDir` para persistir las claves RSA en `~/.android/adbkey`
- `LD_LIBRARY_PATH` → `nativeLibraryDir` para que el linker encuentre las dependencias

> **Nota**: el binario debe estar compilado con **bionic** (linker `/system/bin/linker64`), no con glibc. Un binario compilado para Linux de escritorio (`/lib/ld-linux-aarch64.so.1`) no funciona en Android.

---

## Obtener el binario ADB para Android

El binario incluido en el repositorio proviene de **Termux** (`android-tools`), compilado nativamente para Android ARM64:

```bash
# En Termux (F-Droid):
pkg install android-tools
termux-setup-storage

# Copiar libs al sdcard y traerlas al PC:
sh /sdcard/resolve_deps.sh   # script incluido en el repositorio
adb pull /sdcard/adb_deps/. app/src/main/jniLibs/arm64-v8a/
```

---

## Compilar

```bash
git clone https://github.com/linares2002/versiones.git
cd versiones
./gradlew assembleDebug
```

O directamente desde **Android Studio**: `Build → Make Project`.

---

## Permisos

| Permiso | Uso |
|---|---|
| `INTERNET` | Consultar GitHub API y descargar APKs |
| `WRITE_EXTERNAL_STORAGE` | Guardar APK en Descargas (solo API ≤ 28) |

---

## Licencia

Uso interno — Proyecto BioGW, Universidad de Jaén.
