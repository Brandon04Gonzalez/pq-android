# Persona Q for Android (native port)

> [!WARNING]
> **Maintenance of this port has been stopped.** This repository is no longer actively maintained.

An Android app that runs **your own copy** of Persona Q: Shadow of the Labyrinth (Nintendo 3DS, US) on an arm64 phone
or tablet. The game's code is translated to native ARM64 code **on your phone**, the first time you play: the APK
holds no game code or data, only the engine and a C compiler.

> [!IMPORTANT]
> **No game files are included, here or in the APK.** This repository and its releases hold no game code, assets,
> keys or firmware. You need your own legally dumped copy of the game (a **decrypted** `.cia` or `.3ds`). How to dump
> it from your console is **not** covered here. Please do not ask for game files.

Persona Q is © ATLUS / © SEGA. This is an unofficial fan project, not affiliated with or endorsed by ATLUS, SEGA or
Nintendo.

## Community

- **Discord** (English and Spanish channels), for news, help and testing: https://discord.gg/rMgRneBTCe

## Why not a native (NCE) build like the Switch ports?

The Switch ports ([P5R](https://github.com/Brandon04Gonzalez/p5r-android),
[P4G](https://github.com/Brandon04Gonzalez/p4g-android), [P4AU](https://github.com/Brandon04Gonzalez/p4au-android),
[NieR](https://github.com/Brandon04Gonzalez/nier-android)) run the game's own code directly on the phone's CPU (native
code execution, NCE): the Switch and Android phones share the same 64-bit ARM architecture (AArch64).

The 3DS does not: its CPU is an ARM11, a **32-bit** ARM (ARMv6K). An Android app is a 64-bit process, and a 64-bit
process cannot run 32-bit ARM code; many recent phones cannot run 32-bit code at all, their CPUs dropped it. So the
3DS game's code cannot run as it is: it has to be **translated**. That is what this port does, once, on your phone.

## How it works

- **[3dsrecomp](3dsrecomp/)** translates the game's ARM11 code into C, ahead of time ("static recompilation").
- **[Zig](https://ziglang.org/)'s C compiler** (`zig cc`, clang inside; 0.17.0 for aarch64 Linux, shipped in the APK)
  compiles that C into a native library for your phone. This happens in the app, the first time you play, with the
  progress on screen; later starts load the library right away.
- **[Zakuro](zakuro/)** is the 3DS runtime: the operating system services, the GPU on Vulkan, audio, input and touch
  controls. It runs the translated code and loads the library.

## Install

1. Copy to the phone your decrypted Persona Q `.cia` (or `.3ds`). Optional: its DLC `.cia` files, a Spanish patch
   folder (the game's title id folder with `code.bin` and `romfs`) and an HD texture pack. The launcher finds them
   wherever they are in the phone's storage (it scans for them, or you pick the folder).
2. Install **PersonaQ.apk** from the [Releases](../../releases) page and open it; allow "All files access".
3. Press **JUGAR / PLAY**. The first time, the app unpacks its compiler and then **translates the game for your phone**:
   keep the app open until it ends (about 40 minutes on a Lenovo Tab M11, Snapdragon 685; less on faster phones). After that, the game starts right away.

## Requirements

- An arm64 Android phone or tablet with Android 8 or newer, Vulkan 1.0, 4 GB of RAM or more (6 GB recommended for the
  first translation).
- About 1 GB of free storage for the translation (the compiler's files and the native library).

## Building the APK

Windows, with the Android SDK and NDK 27.2.12479018, a JDK 17+ and Rust with `cargo-ndk`. The folders sit side by
side as here (`pq-android` reaches `../zakuro` and `../3dsrecomp`):
```powershell
# Zig 0.17.0 for aarch64 Linux, unpacked in build\zig-android, and its library trimmed for the APK
curl.exe -L -o zig.tar.xz https://ziglang.org/download/0.17.0/zig-aarch64-linux-0.17.0.tar.xz   # sha256 9e8d1166...
tar -xJf zig.tar.xz -C build\zig-android; python tools\make_zig_lib.py build\zig-android
cd pq-android; python build_apk.py      # paths at the top of build_apk.py
```

## En español

> [!WARNING]
> **Se ha detenido el mantenimiento de este port.** Este repositorio ya no recibe mantenimiento activo.

Una app de Android que ejecuta **tu propia copia** de Persona Q: Shadow of the Labyrinth (3DS, EE. UU.) en un móvil o
tablet arm64. El código del juego se traduce a código nativo **en tu móvil** la primera vez que juegas: el APK no lleva
código ni datos del juego, solo el motor y un compilador de C.

- **Discord** (canales en español e inglés): https://discord.gg/rMgRneBTCe

**¿Por qué no hay una versión nativa (NCE) como en los ports de Switch?** Los ports de Switch ejecutan el código del
propio juego directamente en el procesador del móvil, porque la Switch y los móviles Android usan la misma arquitectura
ARM de 64 bits. La 3DS no: su procesador es un ARM11 de **32 bits**. Una app de Android es un proceso de 64 bits y no
puede ejecutar código ARM de 32 bits; muchos móviles recientes ni siquiera pueden ejecutar código de 32 bits. Por eso
el código del juego tiene que **traducirse**: 3dsrecomp lo convierte en C y el compilador incluido en la app (Zig) lo
convierte en código nativo para tu móvil, una sola vez.

1. Copia al móvil tu `.cia` descifrado (o `.3ds`) de Persona Q. Opcional: los `.cia` de sus DLC, la carpeta de un
   parche en español (la carpeta con el id del juego, con `code.bin` y `romfs`) y un pack de texturas HD. El lanzador
   los encuentra estén donde estén.
2. Instala **PersonaQ.apk** desde [Releases](../../releases), ábrelo y permite el acceso a todos los archivos.
3. Pulsa **JUGAR**. La primera vez la app prepara su compilador y **traduce el juego para tu móvil**: deja la app
   abierta hasta que termine (unos 40 minutos en una Lenovo Tab M11, Snapdragon 685; menos en móviles más rápidos). Después el juego arranca al momento.

No se proporcionan juegos ni claves.

## Credits and license

- Runtime: [Zakuro](https://github.com/fearkov/zakuro) by fearkov, MIT, here with this port's changes (`zakuro/`).
- Recompiler: [3dsrecomp](https://github.com/fearkov/3dsrecomp) by fearkov, MIT, here with this port's changes
  (`3dsrecomp/`).
- C compiler on the phone: [Zig](https://ziglang.org/) 0.17.0 (MIT; LLVM/clang inside, Apache-2.0 with LLVM
  exceptions), shipped unmodified.
- RAR extraction in the launcher: [unrar](https://crates.io/crates/unrar) with RARLAB's UnRAR source (UnRAR license).
- This port's own code is MIT, see [LICENSE](LICENSE); third-party notices in
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
