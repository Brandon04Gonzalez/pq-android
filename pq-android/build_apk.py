"""builds the Persona Q APK: the Rust library through cargo ndk, the Java
launcher through javac and d8, resources through aapt2, then aligns and signs
it with a debug key of its own. python build_apk.py [--skip-cargo]"""

import os
import shutil
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(HERE, 'app')
OUT = r'C:\pqport\build\apk'
SDK = os.environ.get('ANDROID_HOME', r'C:\Users\brand\AppData\Local\Android\Sdk')
NDK = os.path.join(SDK, 'ndk', '27.2.12479018')
TOOLS = os.path.join(SDK, 'build-tools', '35.0.1')
PLATFORM = os.path.join(SDK, 'platforms', 'android-35', 'android.jar')
JDK = r'C:\Program Files\Android\Android Studio\jbr'
TARGET = r'C:\pqport\target\android'
# the launcher's RAR extraction, a library of its own
UNRAR = r'C:\pqport\src\pq-unrar'
UNRAR_TARGET = r'C:\pqport\target\unrar'
CARGO = os.path.join(os.environ['USERPROFILE'], '.cargo', 'bin', 'cargo.exe')
STRIP = os.path.join(NDK, 'toolchains', 'llvm', 'prebuilt', 'windows-x86_64', 'bin', 'llvm-strip.exe')
KEYSTORE = r'C:\pqport\build\personaq-debug.keystore'
# Zig for the phone: zig-aarch64-linux-0.17.0 (sha256 9e8d1166...) unpacked here, and zig-lib.zip
ZIG = r'C:\pqport\build\zig-android'


def run(*args, **kwargs):
    print('>', ' '.join(str(a) for a in args)[:300], flush=True)
    subprocess.run([str(a) for a in args], check=True, **kwargs)


def main():
    env = dict(os.environ, ANDROID_NDK_HOME=NDK, ANDROID_HOME=SDK, CARGO_TARGET_DIR=TARGET, JAVA_HOME=JDK)
    env['PATH'] = os.path.join(JDK, 'bin') + os.pathsep + env['PATH']
    if '--skip-cargo' not in sys.argv:
        run(CARGO, 'ndk', '-t', 'arm64-v8a', '--platform', '26', 'build', '--release', cwd=HERE, env=env)
        run(CARGO, 'ndk', '-t', 'arm64-v8a', '--platform', '26', 'build', '--release', cwd=UNRAR, env=dict(env, CARGO_TARGET_DIR=UNRAR_TARGET))

    shutil.rmtree(OUT, ignore_errors=True)
    for d in ['res', 'gen', 'classes', 'dex', 'lib/arm64-v8a']:
        os.makedirs(os.path.join(OUT, d), exist_ok=True)

    # the library, without its debug info
    library = os.path.join(TARGET, 'aarch64-linux-android', 'release', 'libpersonaq.so')
    stripped = os.path.join(OUT, 'lib', 'arm64-v8a', 'libpersonaq.so')
    run(STRIP, '--strip-debug', '-o', stripped, library)

    # resources and the manifest
    flat = os.path.join(OUT, 'res', 'res.zip')
    run(os.path.join(TOOLS, 'aapt2.exe'), 'compile', '--dir', os.path.join(APP, 'res'), '-o', flat)
    base = os.path.join(OUT, 'base.apk')
    # --debuggable: for run-as and simpleperf while measuring on a phone, never for players
    debug = ['--debug-mode'] if '--debuggable' in sys.argv else []
    run(os.path.join(TOOLS, 'aapt2.exe'), 'link', '-I', PLATFORM, '--manifest', os.path.join(APP, 'AndroidManifest.xml'),
        '--java', os.path.join(OUT, 'gen'), '--min-sdk-version', '26', '--target-sdk-version', '34', *debug, '-o', base, flat)

    # the Java side
    sources = []
    for root, _, files in os.walk(os.path.join(APP, 'java')):
        sources += [os.path.join(root, f) for f in files if f.endswith('.java')]
    for root, _, files in os.walk(os.path.join(OUT, 'gen')):
        sources += [os.path.join(root, f) for f in files if f.endswith('.java')]
    run(os.path.join(JDK, 'bin', 'javac.exe'), '--release', '11', '-classpath', PLATFORM, '-d', os.path.join(OUT, 'classes'), *sources, env=env)
    classes = []
    for root, _, files in os.walk(os.path.join(OUT, 'classes')):
        classes += [os.path.join(root, f) for f in files if f.endswith('.class')]
    run(os.path.join(TOOLS, 'd8.bat'), '--release', '--min-api', '26', '--lib', PLATFORM, '--output', os.path.join(OUT, 'dex'), *classes, env=env, shell=False)

    # everything in one zip
    unaligned = os.path.join(OUT, 'unaligned.apk')
    shutil.copy(base, unaligned)
    with zipfile.ZipFile(unaligned, 'a', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as apk:
        apk.write(os.path.join(OUT, 'dex', 'classes.dex'), 'classes.dex')
        apk.write(stripped, 'lib/arm64-v8a/libpersonaq.so')
        apk.write(os.path.join(UNRAR_TARGET, 'aarch64-linux-android', 'release', 'libpqunrar.so'), 'lib/arm64-v8a/libpqunrar.so')
        # the C compiler the game recompiles itself with on the phone (Zig 0.17.0, aarch64-linux, static), and its
        # library (make_zig_lib.py), already compressed
        apk.write(os.path.join(ZIG, 'zig-aarch64-linux-0.17.0', 'zig'), 'lib/arm64-v8a/libzig.so')
        apk.write(os.path.join(ZIG, 'zig-lib.zip'), 'assets/zig-lib.zip', compress_type=zipfile.ZIP_STORED)
    aligned = os.path.join(OUT, 'aligned.apk')
    run(os.path.join(TOOLS, 'zipalign.exe'), '-f', '-p', '4', unaligned, aligned)

    if not os.path.exists(KEYSTORE):
        run(os.path.join(JDK, 'bin', 'keytool.exe'), '-genkeypair', '-keystore', KEYSTORE, '-storepass', 'android', '-keypass', 'android',
            '-alias', 'personaq', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=PersonaQ Port,O=Personal,C=MX')
    final = r'C:\pqport\build\PersonaQ.apk'
    run(os.path.join(TOOLS, 'apksigner.bat'), 'sign', '--ks', KEYSTORE, '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
        '--ks-key-alias', 'personaq', '--out', final, aligned, env=env)
    run(os.path.join(TOOLS, 'apksigner.bat'), 'verify', final, env=env)
    print('APK:', final, '%.1f MB' % (os.path.getsize(final) / 1e6))


if __name__ == '__main__':
    main()
