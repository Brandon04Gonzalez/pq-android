//! Persona Q: Shadow of the Labyrinth, recompiled ahead of time by 3dsrecomp
//! and run by Zakuro, as an Android app of its own. the game's files, its
//! mods and the saves live in the PersonaQ folder of the phone's storage,
//! where the player puts their own copy of the game.

use std::ffi::c_void;
use std::path::{Path, PathBuf};
#[cfg(feature = "linked")]
use std::ptr::addr_of;

use zakuro::AndroidApp;

/// Persona Q's, USA.
const PROGRAM_ID: u64 = 0x0004_0000_0012_3400;

#[cfg(feature = "linked")]
extern "C" {
    static recomp_abi: u32;
    static recomp_entry_count: u32;
    static recomp_entries: u8;
    static recomp_module_count: u32;
    static recomp_modules: u8;
}

/// the folder in the phone's storage the player copies the game to.
fn folder() -> PathBuf {
    let storage = std::env::var_os("EXTERNAL_STORAGE").filter(|path| !path.is_empty()).map(PathBuf::from);
    storage.unwrap_or_else(|| PathBuf::from("/storage/emulated/0")).join("PersonaQ")
}

/// whether a CIA is a decrypted dump: its contents not encrypted as a whole
/// and the first one's NCCH flagged as holding no crypto.
fn decrypted(path: &Path) -> bool {
    use std::io::{Read, Seek, SeekFrom};
    let align = |x: u64| (x + 63) / 64 * 64;
    let Ok(mut file) = std::fs::File::open(path) else { return false };
    let mut header = [0u8; 0x20];
    if file.read_exact(&mut header).is_err() {
        return false;
    }
    let word = |at: usize| u32::from_le_bytes(header[at..at + 4].try_into().unwrap()) as u64;
    let (size, certs, ticket, tmd) = (word(0), word(8), word(12), word(16));
    let content = align(align(align(align(size) + certs) + ticket) + tmd);
    let mut ncch = [0u8; 0x100];
    file.seek(SeekFrom::Start(content + 0x100)).is_ok()
        && file.read_exact(&mut ncch).is_ok()
        && &ncch[..4] == b"NCCH"
        && ncch[0x8F] & 4 != 0
}

/// the DLC in folder, decrypted, which a name with dlc in it tells apart.
fn dlc(folder: &Path) -> Vec<PathBuf> {
    let Ok(entries) = std::fs::read_dir(folder) else { return Vec::new() };
    entries
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| {
            let name = path.file_name().map(|name| name.to_string_lossy().to_ascii_lowercase()).unwrap_or_default();
            name.ends_with(".cia") && name.contains("dlc")
        })
        .filter(|path| {
            let usable = decrypted(path);
            if !usable {
                log::warn!("{} is encrypted, the game runs without it", path.display());
            }
            usable
        })
        .collect()
}

/// the game in folder, the largest of the dumps there.
fn game(folder: &Path) -> Option<PathBuf> {
    let entries = std::fs::read_dir(folder).ok()?;
    entries
        .filter_map(Result::ok)
        .filter(|entry| {
            let name = entry.file_name().to_string_lossy().to_ascii_lowercase();
            [".cia", ".3ds", ".cci", ".cxi"].iter().any(|extension| name.ends_with(extension)) && !name.contains("dlc")
        })
        .filter_map(|entry| Some((entry.metadata().ok()?.len(), entry.path())))
        .max()
        .map(|(_, path)| path)
}

/// Android's onTrimMemory, the system short of memory or the game in the
/// background: what the GPU keeps that it can read again goes.
#[no_mangle]
pub extern "system" fn Java_com_personaq_port_GameActivity_trimMemory(_env: *mut c_void, _activity: *mut c_void, level: i32) {
    zakuro::trim_memory(level);
}

#[no_mangle]
fn android_main(app: AndroidApp) {
    // settings and caches in the app's own folder, as XDG would have them
    let own = app.internal_data_path().unwrap_or_else(|| PathBuf::from("/data/local/tmp/personaq"));
    let cache = own.join("cache");
    let _ = std::fs::create_dir_all(&cache);
    for name in ["HOME", "XDG_CONFIG_HOME", "XDG_DATA_HOME"] {
        std::env::set_var(name, &own);
    }
    std::env::set_var("XDG_CACHE_HOME", &cache);
    std::env::set_var("TMPDIR", &cache);
    // options to try without building the app again, a NAME=value a line
    // in PersonaQ/env.txt, for measuring on a phone
    if let Ok(text) = std::fs::read_to_string(folder().join("env.txt")) {
        for line in text.lines().map(str::trim).filter(|line| !line.is_empty() && !line.starts_with('#')) {
            if let Some((name, value)) = line.split_once('=') {
                std::env::set_var(name.trim(), value.trim());
            }
        }
    }
    // the shader compiler says what it compiles at info, far too much for a log
    if std::env::var_os("RUST_LOG").is_none() {
        std::env::set_var("RUST_LOG", "info,cranelift_jit=warn,cranelift_codegen=warn,cranelift_frontend=warn");
    }

    let folder = folder();
    let _ = std::fs::create_dir_all(&folder);
    // where the launcher found the game and its DLC, else what is in the folder
    let found = |name: &str| std::env::var_os(name).filter(|value| !value.is_empty());
    let rom = found("PQ_GAME").map(PathBuf::from).filter(|path| path.is_file()).or_else(|| game(&folder));
    // SAFETY: the tables 3dsrecomp wrote beside the code build.rs links in
    #[cfg(feature = "linked")]
    let code = Some(unsafe {
        zakuro::Linked::new(
            PROGRAM_ID,
            recomp_abi,
            addr_of!(recomp_entries) as *const c_void,
            recomp_entry_count,
            addr_of!(recomp_modules) as *const c_void,
            recomp_module_count,
        )
    });
    // the APK holds no game code: zakuro recompiles the player's game on the
    // phone the first time, with the C compiler the app ships (ZAKURO_CC)
    #[cfg(not(feature = "linked"))]
    let code = None;
    let dlc = match found("PQ_DLC") {
        Some(paths) => paths.to_string_lossy().lines().map(PathBuf::from).filter(|path| path.is_file() && decrypted(path)).collect(),
        None => dlc(&folder),
    };
    zakuro::run_android(app, code, rom.as_deref(), dlc, &folder);
}
