package com.personaq.port;

/** RAR archives, through libpqunrar.so: the game downloaded as one, unpacked in the phone. */
final class Rar {
    private static Boolean loaded;

    private Rar() {
    }

    /** whether the library is there to call, loading it the first time. */
    static synchronized boolean available() {
        if (loaded == null) {
            try {
                System.loadLibrary("pqunrar");
                loaded = true;
            } catch (Throwable e) {
                android.util.Log.w("PersonaQ", "no RAR library", e);
                loaded = false;
            }
        }
        return loaded;
    }

    /** "OK\n" and a line of size, tab and path for each file, or "ERR\n" and why not. */
    static native String list(String archive);

    /** puts every file in folder, by its path in the archive; null when done, else why not. */
    static native String extract(String archive, String folder);

    /** the file being unpacked. */
    static native String current();

    /** stops once the file being unpacked is done. */
    static native void cancel();

    /** whether a file starts the way RAR archives do, whatever it is named. */
    static boolean is(java.io.File file) {
        byte[] magic = new byte[7];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            return in.read(magic) == 7 && magic[0] == 'R' && magic[1] == 'a' && magic[2] == 'r' && magic[3] == '!'
                && magic[4] == 0x1A && magic[5] == 0x07;
        } catch (Exception e) {
            return false;
        }
    }
}
