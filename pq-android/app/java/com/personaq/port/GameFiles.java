package com.personaq.port;

import android.os.Environment;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * where the player keeps Persona Q: the game, its DLC, the Spanish patch and
 * the HD textures, wherever in the phone's storage they are, told apart by
 * what the files hold rather than by their names.
 */
final class GameFiles {
    static final String TITLE_ID = "0004000000123400";
    static final long PROGRAM_ID = 0x0004000000123400L;
    /** the game's DLC titles, 0004008C and the game's unique id. */
    static final long DLC_HIGH = 0x0004008CL;

    /** what a file holds, by its headers. */
    static final class Dump {
        final File file;
        /** cia, cci, cxi, rar, zip, 7z, or null when it is none of them. */
        final String kind;
        /** the title it is, from the CIA's TMD or the NCCH's header, 0 when unknown. */
        final long title;
        /** its content can be read as it is, not encrypted. */
        final boolean decrypted;

        Dump(File file, String kind, long title, boolean decrypted) {
            this.file = file;
            this.kind = kind;
            this.title = title;
            this.decrypted = decrypted;
        }

        boolean game() {
            return title == PROGRAM_ID && ("cia".equals(kind) || "cci".equals(kind) || "cxi".equals(kind));
        }

        boolean dlc() {
            return "cia".equals(kind) && (title >>> 32) == DLC_HIGH && (title & 0xFFFFFFFFL) == (PROGRAM_ID & 0xFFFFFFFFL);
        }

        boolean archive() {
            return "rar".equals(kind) || "zip".equals(kind) || "7z".equals(kind);
        }
    }

    /** the game, the first readable one found, or one that is not, to say why. */
    Dump game;
    /** a game found that can't be used: encrypted, or another region's. */
    final List<Dump> unusable = new ArrayList<>();
    final List<Dump> dlc = new ArrayList<>();
    final List<Dump> encryptedDlc = new ArrayList<>();
    /** the patch's folder, holding code.bin and romfs, or null. */
    File patch;
    /** the HD textures' folder, or null, and how many pictures it has. */
    File textures;
    int pictures;
    /** archives that may hold the game. */
    final List<Dump> archives = new ArrayList<>();
    /** files named as a game is that are something else, a RAR most often. */
    final List<Dump> misnamed = new ArrayList<>();

    static File storage() {
        return Environment.getExternalStorageDirectory();
    }

    /** the folder the saves and the log go in, and where downloads get unpacked. */
    static File home() {
        return new File(storage(), "PersonaQ");
    }

    private static long align(long x) {
        return (x + 63) / 64 * 64;
    }

    /** what a file holds, reading its headers. */
    static Dump inspect(File file) {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            byte[] head = new byte[0x200];
            int got = in.read(head);
            if (got >= 7 && head[0] == 'R' && head[1] == 'a' && head[2] == 'r' && head[3] == '!' && head[4] == 0x1A && head[5] == 0x07) {
                return new Dump(file, "rar", 0, true);
            }
            if (got >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) {
                return new Dump(file, "zip", 0, true);
            }
            if (got >= 6 && head[0] == '7' && head[1] == 'z' && (head[2] & 0xFF) == 0xBC && (head[3] & 0xFF) == 0xAF) {
                return new Dump(file, "7z", 0, true);
            }
            if (got < 0x200) {
                return new Dump(file, null, 0, false);
            }
            if (magic(head, 0x100, "NCSD")) {
                // a cartridge: its first partition's NCCH
                long offset = (le32(head, 0x120) & 0xFFFFFFFFL) * 0x200;
                return ncch(file, in, offset, "cci");
            }
            if (magic(head, 0x100, "NCCH")) {
                return ncch(file, in, 0, "cxi");
            }
            if (le32(head, 0) == 0x2020 && le16(head, 4) == 0) {
                long size = le32(head, 0) & 0xFFFFFFFFL, certs = le32(head, 8) & 0xFFFFFFFFL;
                long ticket = le32(head, 12) & 0xFFFFFFFFL, tmdSize = le32(head, 16) & 0xFFFFFFFFL;
                long tmd = align(align(align(size) + certs) + ticket);
                long content = align(tmd + tmdSize);
                // the title id, past the TMD's RSA-2048 signature
                byte[] id = new byte[8];
                in.seek(tmd + 0x18C);
                in.readFully(id);
                long title = 0;
                for (byte b : id) {
                    title = title << 8 | (b & 0xFF);
                }
                byte[] ncch = new byte[0x100];
                in.seek(content + 0x100);
                in.readFully(ncch);
                boolean clear = magic(ncch, 0, "NCCH") && (ncch[0x8F] & 4) != 0;
                return new Dump(file, "cia", title, clear);
            }
        } catch (Exception ignored) {
        }
        return new Dump(file, null, 0, false);
    }

    private static Dump ncch(File file, RandomAccessFile in, long offset, String kind) throws java.io.IOException {
        byte[] header = new byte[0x200];
        in.seek(offset);
        in.readFully(header);
        if (!magic(header, 0x100, "NCCH")) {
            return new Dump(file, null, 0, false);
        }
        long title = (le32(header, 0x118) & 0xFFFFFFFFL) | (long) le32(header, 0x11C) << 32;
        return new Dump(file, kind, title, (header[0x18F] & 4) != 0);
    }

    private static boolean magic(byte[] bytes, int at, String magic) {
        for (int i = 0; i < magic.length(); i++) {
            if (bytes[at + i] != magic.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int le32(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) | (bytes[at + 1] & 0xFF) << 8 | (bytes[at + 2] & 0xFF) << 16 | (bytes[at + 3] & 0xFF) << 24;
    }

    private static int le16(byte[] bytes, int at) {
        return (bytes[at] & 0xFF) | (bytes[at + 1] & 0xFF) << 8;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static boolean gameName(String name) {
        String extension = extension(name);
        return extension.equals("cia") || extension.equals("3ds") || extension.equals("cci") || extension.equals("cxi");
    }

    static boolean archiveName(String name) {
        String extension = extension(name);
        return extension.equals("rar") || extension.equals("zip") || extension.equals("7z");
    }

    /** whether a folder is the patch's: the title's id, with code or files for the game in it. */
    private static boolean patchFolder(File dir) {
        return new File(dir, "code.bin").isFile() || new File(dir, "exheader.bin").isFile()
            || new File(new File(dir, "romfs"), "mod.cpk").isFile() || new File(dir, "code.ips").isFile();
    }

    /** the first PNG under a folder, looking a few levels down, or how many when counting. */
    private static int pngs(File dir, int depth, boolean count) {
        File[] files = dir.listFiles();
        if (files == null || depth > 8) {
            return 0;
        }
        int found = 0;
        for (File file : files) {
            if (file.isDirectory()) {
                found += pngs(file, depth + 1, count);
            } else if (file.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
                found++;
            }
            if (!count && found > 0) {
                return found;
            }
        }
        return found;
    }

    /** where to look, the folder the player picked first, each with how deep. */
    static List<Object[]> places(File picked) {
        List<Object[]> places = new ArrayList<>();
        if (picked != null) {
            places.add(new Object[] {picked, 7});
        }
        places.add(new Object[] {home(), 7});
        places.add(new Object[] {new File(storage(), Environment.DIRECTORY_DOWNLOADS), 6});
        places.add(new Object[] {new File(storage(), Environment.DIRECTORY_DOCUMENTS), 5});
        places.add(new Object[] {storage(), 4});
        // memory cards
        File[] cards = new File("/storage").listFiles();
        if (cards != null) {
            for (File card : cards) {
                if (card.getName().matches("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) {
                    places.add(new Object[] {card, 5});
                }
            }
        }
        return places;
    }

    /** folders no one keeps a game in, which have a great many files. */
    private static final Set<String> SKIPPED = new HashSet<>(java.util.Arrays.asList(
        "android", "dcim", "pictures", "movies", "music", "ringtones", "notifications", "alarms", "podcasts",
        "audiobooks", "recordings", "whatsapp", "telegram", "lost.dir", "screenshots", "snapchat", "instagram"));

    /**
     * looks through the places for everything, a file the player picked as
     * the game taking the place of any other, stopping after a number of
     * entries so a full phone does not take long.
     */
    static GameFiles find(File picked, File pickedGame) {
        GameFiles found = new GameFiles();
        Set<String> seen = new HashSet<>();
        Set<Long> dlcTitles = new HashSet<>();
        int budget = 40000;
        if (pickedGame != null && pickedGame.isFile()) {
            Dump dump = inspect(pickedGame);
            if (dump.game()) {
                found.game = dump;
            }
        }
        for (Object[] place : places(picked)) {
            File root = (File) place[0];
            int deepest = (Integer) place[1];
            ArrayDeque<Object[]> queue = new ArrayDeque<>();
            queue.add(new Object[] {root, 0});
            while (!queue.isEmpty() && budget > 0) {
                Object[] next = queue.poll();
                File dir = (File) next[0];
                int depth = (Integer) next[1];
                String key;
                try {
                    key = dir.getCanonicalPath();
                } catch (Exception e) {
                    key = dir.getAbsolutePath();
                }
                if (!seen.add(key)) {
                    continue;
                }
                File[] files = dir.listFiles();
                if (files == null) {
                    continue;
                }
                budget -= files.length;
                String dirName = dir.getName();
                if (dirName.equalsIgnoreCase(TITLE_ID)) {
                    if (found.patch == null && patchFolder(dir)) {
                        found.patch = dir;
                    }
                    File textures = new File(dir, "textures");
                    if (found.textures == null && textures.isDirectory() && pngs(textures, 0, false) > 0) {
                        found.textures = textures;
                    }
                    // Citra's load/textures/<title id>
                    File parent = dir.getParentFile();
                    if (found.textures == null && parent != null && parent.getName().equalsIgnoreCase("textures") && pngs(dir, 0, false) > 0) {
                        found.textures = dir;
                    }
                    // nothing to look for further down a mod's folder
                    continue;
                }
                for (File file : files) {
                    String name = file.getName();
                    if (file.isDirectory()) {
                        String lower = name.toLowerCase(Locale.ROOT);
                        boolean skipped = name.startsWith(".") || (depth == 0 && SKIPPED.contains(lower));
                        if (depth < deepest && !skipped) {
                            queue.add(new Object[] {file, depth + 1});
                        }
                        continue;
                    }
                    if (gameName(name) && file.length() > 0x4000) {
                        Dump dump = inspect(file);
                        if (dump.game()) {
                            if (found.game == null && dump.decrypted) {
                                found.game = dump;
                            } else if (!dump.decrypted && !contains(found.unusable, file)) {
                                found.unusable.add(dump);
                            }
                        } else if (dump.dlc()) {
                            if (dump.decrypted) {
                                if (dlcTitles.add(dump.title)) {
                                    found.dlc.add(dump);
                                }
                            } else {
                                found.encryptedDlc.add(dump);
                            }
                        } else if (dump.archive()) {
                            found.misnamed.add(dump);
                        } else if ("cia".equals(dump.kind) || "cci".equals(dump.kind) || "cxi".equals(dump.kind)) {
                            if (dump.title != 0 && (dump.title >>> 32) == 0x00040000L) {
                                // another game, or Persona Q from another region
                                found.unusable.add(dump);
                            }
                        } else {
                            found.misnamed.add(dump);
                        }
                    } else if (archiveName(name) && file.length() > 100L << 20) {
                        String lower = name.toLowerCase(Locale.ROOT);
                        if (lower.contains("persona") || dir.equals(home()) || (picked != null && dir.equals(picked))) {
                            found.archives.add(inspect(file));
                        }
                    }
                }
            }
        }
        if (found.textures != null) {
            found.pictures = pngs(found.textures, 0, true);
        }
        return found;
    }

    private static boolean contains(List<Dump> dumps, File file) {
        for (Dump dump : dumps) {
            if (dump.file.equals(file)) {
                return true;
            }
        }
        return false;
    }
}
