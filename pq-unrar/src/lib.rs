//! RAR archives for the launcher, the game downloaded as one: what is in
//! them and their files put in a folder, through the unrar library, called
//! from com.personaq.port.Rar on a thread of the launcher's.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use unrar::Archive;

/// the file being unpacked now, for the launcher to show.
static CURRENT: Mutex<String> = Mutex::new(String::new());
/// the launcher asked to stop, which happens between files.
static STOP: AtomicBool = AtomicBool::new(false);

/// the files of an archive, a line each of size, a tab and the path, the
/// folders left out.
fn list(archive: &Path) -> Result<String, String> {
    let entries = Archive::new(archive).open_for_listing().map_err(|error| error.to_string())?;
    let mut lines = String::new();
    for entry in entries {
        let entry = entry.map_err(|error| error.to_string())?;
        if entry.is_file() {
            lines.push_str(&format!("{}\t{}\n", entry.unpacked_size, entry.filename.to_string_lossy()));
        }
    }
    Ok(lines)
}

/// puts every file of an archive in a folder, by their paths in it.
fn extract(archive: &Path, folder: &Path) -> Result<(), String> {
    STOP.store(false, Ordering::Relaxed);
    let mut open = Archive::new(archive).open_for_processing().map_err(|error| error.to_string())?;
    while let Some(header) = open.read_header().map_err(|error| error.to_string())? {
        if STOP.load(Ordering::Relaxed) {
            return Err("cancelado".to_owned());
        }
        let name = header.entry().filename.to_string_lossy().into_owned();
        *CURRENT.lock().unwrap_or_else(|poisoned| poisoned.into_inner()) = name.clone();
        open = if header.entry().is_file() {
            header.extract_with_base(folder).map_err(|error| format!("{name}: {error}"))?
        } else {
            header.skip().map_err(|error| error.to_string())?
        };
    }
    CURRENT.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).clear();
    Ok(())
}

fn path(env: &mut JNIEnv, text: &JString) -> Option<PathBuf> {
    env.get_string(text).ok().map(|text| PathBuf::from(String::from(text)))
}

fn answer(env: &mut JNIEnv, text: &str) -> jstring {
    env.new_string(text).map(|text| text.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// "OK\n" and the files, or "ERR\n" and why not.
#[no_mangle]
pub extern "system" fn Java_com_personaq_port_Rar_list<'local>(mut env: JNIEnv<'local>, _class: JClass<'local>, archive: JString<'local>) -> jstring {
    let result = path(&mut env, &archive).ok_or_else(|| "bad path".to_owned()).and_then(|archive| list(&archive));
    match result {
        Ok(lines) => answer(&mut env, &format!("OK\n{lines}")),
        Err(error) => answer(&mut env, &format!("ERR\n{error}")),
    }
}

/// null when every file was put in the folder, else why not.
#[no_mangle]
pub extern "system" fn Java_com_personaq_port_Rar_extract<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    archive: JString<'local>,
    folder: JString<'local>,
) -> jstring {
    let (Some(archive), Some(folder)) = (path(&mut env, &archive), path(&mut env, &folder)) else {
        return answer(&mut env, "bad path");
    };
    match extract(&archive, &folder) {
        Ok(()) => std::ptr::null_mut(),
        Err(error) => answer(&mut env, &error),
    }
}

/// the file being unpacked.
#[no_mangle]
pub extern "system" fn Java_com_personaq_port_Rar_current<'local>(mut env: JNIEnv<'local>, _class: JClass<'local>) -> jstring {
    let current = CURRENT.lock().unwrap_or_else(|poisoned| poisoned.into_inner()).clone();
    answer(&mut env, &current)
}

/// stops after the file being unpacked.
#[no_mangle]
pub extern "system" fn Java_com_personaq_port_Rar_cancel<'local>(_env: JNIEnv<'local>, _class: JClass<'local>) {
    STOP.store(true, Ordering::Relaxed);
}
