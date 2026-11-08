//! links the game's code, which 3dsrecomp port compiled for the phone into
//! a static library, PQ_RECOMP_DIR saying where it is.

use std::env::var;

fn main() {
    // only the developer's builds link the game's code (the linked feature)
    println!("cargo:rerun-if-env-changed=CARGO_FEATURE_LINKED");
    if std::env::var_os("CARGO_FEATURE_LINKED").is_none() {
        return;
    }
    let dir = var("PQ_RECOMP_DIR").unwrap_or_else(|_| "C:/pqport/build/android/code".to_owned());
    println!("cargo:rerun-if-env-changed=PQ_RECOMP_DIR");
    println!("cargo:rerun-if-changed={dir}/librecomp.a");
    println!("cargo:rustc-link-search=native={dir}");
    println!("cargo:rustc-link-lib=static=recomp");
    println!("cargo:rustc-link-lib=m");
}
