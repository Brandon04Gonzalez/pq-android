//! the phone's CPU, for the thread that emulates. phones' SoCs, Snapdragon,
//! Dimensity and Helio, Exynos, Tensor and Kirin, mix fast cores with slow
//! ones a third as quick or less, and the scheduler can leave the emulating
//! thread on a slow one for a while, through loading or after it slept
//! between frames, long enough to drop frames. once a game runs the thread
//! keeps to every core but the slowest cluster's, and on Android 13 and on
//! it tells the system how long its frames take against how long they may,
//! through a performance hint session, so the clocks go up before frames
//! are lost rather than after. ZAKURO_ANY_CORE=1 leaves the thread where
//! the scheduler puts it.

#[cfg(target_os = "android")]
use std::time::Duration;

/// how fast each core is, by its number: the kernel's own capacity, or else
/// its highest clock. none when the system keeps both to itself.
#[cfg(target_os = "android")]
fn speeds() -> Vec<(usize, u64)> {
    let read = |path: String| std::fs::read_to_string(path).ok().and_then(|text| text.trim().parse::<u64>().ok());
    let cores = std::thread::available_parallelism().map_or(1, |n| n.get()).max(possible_cores());
    let by = |file: &str| -> Option<Vec<(usize, u64)>> {
        let speeds: Vec<(usize, u64)> =
            (0..cores).filter_map(|core| Some((core, read(format!("/sys/devices/system/cpu/cpu{core}/{file}"))?))).collect();
        (speeds.len() > 1).then_some(speeds)
    };
    by("cpu_capacity").or_else(|| by("cpufreq/cpuinfo_max_freq")).or_else(|| by("cpufreq/scaling_max_freq")).unwrap_or_default()
}

/// the cores there can be, from the kernel's list of them, "0-7".
#[cfg(target_os = "android")]
fn possible_cores() -> usize {
    let Ok(text) = std::fs::read_to_string("/sys/devices/system/cpu/possible") else { return 0 };
    text.trim().split(',').filter_map(|range| range.rsplit('-').next()?.parse::<usize>().ok()).max().map_or(0, |last| last + 1)
}

/// the cores the emulating thread keeps to: all but the slowest cluster's,
/// when those are less than half as fast as the fastest. none when every
/// core is about as fast, or there is nothing to tell them apart by.
fn fast_cores(speeds: &[(usize, u64)]) -> Option<Vec<usize>> {
    let fastest = speeds.iter().map(|&(_, speed)| speed).max()?;
    let slowest = speeds.iter().map(|&(_, speed)| speed).min()?;
    let same = if fastest <= 1024 {
        slowest * 2 >= fastest
    } else {
        slowest * 11 / 10 >= fastest
    };
    if same {
        return None;
    }
    Some(speeds.iter().filter(|&&(_, speed)| speed > slowest).map(|&(core, _)| core).collect())
}

/// keeps the calling thread to the fast cores, once a game runs on it.
/// threads it starts later keep to them too.
#[cfg(target_os = "android")]
pub fn favor_fast_cores() {
    if std::env::var("ZAKURO_ANY_CORE").is_ok_and(|value| value != "0") {
        return;
    }
    let speeds = speeds();
    let cores = fast_cores(&speeds).or_else(|| {
        let n = possible_cores().max(std::thread::available_parallelism().map_or(1, |n| n.get()));
        if n >= 4 {
            let half = n / 2;
            Some((half..n).collect())
        } else {
            None
        }
    });
    let Some(cores) = cores else {
        log::info!("CPU: {} cores of about the same speed, {speeds:?}", speeds.len().max(1));
        return;
    };
    // SAFETY: a set of plain bits, then a call about the calling thread
    let kept = unsafe {
        let mut set: libc::cpu_set_t = std::mem::zeroed();
        for &core in &cores {
            libc::CPU_SET(core, &mut set);
        }
        libc::sched_setaffinity(0, std::mem::size_of::<libc::cpu_set_t>(), &set) == 0
    };
    match kept {
        true => log::info!("CPU: emulating on cores {cores:?} of {speeds:?}, the slowest cluster left to the rest"),
        false => log::info!("CPU: could not keep to cores {cores:?}, {}", std::io::Error::last_os_error()),
    }
    // ahead of the app's other threads, as Android's display threads are
    // SAFETY: a call about the calling thread
    unsafe { libc::setpriority(libc::PRIO_PROCESS, libc::gettid() as libc::id_t, -4) };
}

#[cfg(target_os = "android")]
type GetManager = unsafe extern "C" fn() -> *mut std::ffi::c_void;
#[cfg(target_os = "android")]
type CreateSession = unsafe extern "C" fn(*mut std::ffi::c_void, *const i32, usize, i64) -> *mut std::ffi::c_void;
#[cfg(target_os = "android")]
type ReportDuration = unsafe extern "C" fn(*mut std::ffi::c_void, i64) -> i32;
#[cfg(target_os = "android")]
type CloseSession = unsafe extern "C" fn(*mut std::ffi::c_void);

/// a performance hint session of Android 13's for the emulating thread,
/// which hears how long each frame's work took.
#[cfg(target_os = "android")]
pub struct Hint {
    session: *mut std::ffi::c_void,
    report: ReportDuration,
    close: CloseSession,
}

#[cfg(target_os = "android")]
impl Hint {
    /// a session for the calling thread, frames of target long, where the
    /// system has them.
    pub fn new(target: Duration) -> Option<Hint> {
        // SAFETY: libandroid.so is every app's, loaded already, and the
        // functions are looked up by the names and types the NDK gives them
        unsafe {
            let library = libc::dlopen(c"libandroid.so".as_ptr(), libc::RTLD_NOW | libc::RTLD_NOLOAD);
            let library = if library.is_null() { libc::dlopen(c"libandroid.so".as_ptr(), libc::RTLD_NOW) } else { library };
            if library.is_null() {
                return None;
            }
            let find = |name: &std::ffi::CStr| {
                let symbol = libc::dlsym(library, name.as_ptr());
                (!symbol.is_null()).then_some(symbol)
            };
            let manager: GetManager = std::mem::transmute(find(c"APerformanceHint_getManager")?);
            let create: CreateSession = std::mem::transmute(find(c"APerformanceHint_createSession")?);
            let report: ReportDuration = std::mem::transmute(find(c"APerformanceHint_reportActualWorkDuration")?);
            let close: CloseSession = std::mem::transmute(find(c"APerformanceHint_closeSession")?);
            let manager = manager();
            if manager.is_null() {
                return None;
            }
            let threads = [libc::gettid()];
            let session = create(manager, threads.as_ptr(), threads.len(), target.as_nanos() as i64);
            if session.is_null() {
                log::info!("CPU: no performance hint session");
                return None;
            }
            log::info!("CPU: frames reported to a performance hint session, {:.1} ms each", target.as_secs_f64() * 1000.0);
            Some(Hint { session, report, close })
        }
    }

    /// how long a frame's work took.
    pub fn report(&self, work: Duration) {
        // SAFETY: a live session of ours, on the thread it was made for
        unsafe { (self.report)(self.session, work.as_nanos().max(1) as i64) };
    }
}

#[cfg(target_os = "android")]
impl Drop for Hint {
    fn drop(&mut self) {
        // SAFETY: the session is ours and goes once
        unsafe { (self.close)(self.session) };
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// the slowest cluster is left out where it is less than half as fast,
    /// and nothing changes where the cores are about as fast.
    #[test]
    fn the_slowest_cluster_is_left_out() {
        // 4 Cortex-A55, 3 A78 and an X1, by capacity
        let mixed: Vec<(usize, u64)> = (0..8).map(|core| (core, [325, 325, 325, 325, 780, 780, 780, 1024][core])).collect();
        assert_eq!(fast_cores(&mixed), Some(vec![4, 5, 6, 7]));
        // 2 A76 and 6 A55, by clock
        let two: Vec<(usize, u64)> = (0..8).map(|core| (core, if core >= 6 { 2_200_000 } else { 1_800_000 / 2 })).collect();
        assert_eq!(fast_cores(&two), Some(vec![6, 7]));
        // four X4 and four A720
        let big: Vec<(usize, u64)> = (0..8).map(|core| (core, if core >= 4 { 1024 } else { 840 })).collect();
        assert_eq!(fast_cores(&big), None);
        assert_eq!(fast_cores(&[]), None);
    }
}
