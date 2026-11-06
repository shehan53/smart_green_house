package com.greenhouse.monitor;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Author: Role 5 - Timing & Integration Lead
 * Concepts: #8 synchronized (several threads print at the same time; one file write at a time)
 * Purpose: Makes the full log an EXACT copy of the CLI window. It replaces System.out / System.err with a
 *          stream that writes every byte both to the console and to logs/YYYY-MM-DD/greenhouse_HH-mm-ss.log.
 *          So the menu, the help box, every line and every error appear in the file exactly as on the screen.
 */
public final class ConsoleCopy extends OutputStream {

    private static ConsoleCopy instance;

    private final PrintStream console;
    private OutputStream file;
    private LocalDate day;
    private Path path;
    private boolean closed;

    private ConsoleCopy(PrintStream console) {
        this.console = console;
    }

    /** Called once at the very start of main(): from now on everything printed is also written to the log. */
    public static synchronized void install() {
        if (instance != null) return;
        PrintStream original = System.out;
        instance = new ConsoleCopy(original);
        PrintStream both = new PrintStream(instance, true, original.charset());
        System.setOut(both);
        System.setErr(both);
    }

    @Override
    public void write(int b) {
        console.write(b);
        toFile(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) {
        console.write(b, off, len);
        toFile(b, off, len);
    }

    @Override
    public void flush() {
        console.flush();
        synchronized (this) {
            try {
                if (file != null) file.flush();
            } catch (IOException ignored) {
                // the console output must never fail because of the log file
            }
        }
    }

    // CONCEPT 8: synchronized - bytes from different threads are written to the file one after the other
    private synchronized void toFile(byte[] b, int off, int len) {
        if (closed) return;
        try {
            LocalDate today = LocalDate.now();
            if (!today.equals(day)) openFor(today);
            if (file != null) file.write(b, off, len);
        } catch (IOException e) {
            file = null; // logging to file failed -> the system and the console keep running
        }
    }

    /** One file per run, in a folder per date; after midnight the run continues in the new date folder. */
    private void openFor(LocalDate today) throws IOException {
        if (file != null) file.close();
        day = today;
        path = Log.LOG_DIR.resolve(today.toString()).resolve("greenhouse_" + Log.RUN_NAME + ".log");
        Files.createDirectories(path.getParent());
        file = new BufferedOutputStream(new FileOutputStream(path.toFile(), true));
    }

    /** Where the full log of this run is written, e.g. logs/2026-11-05/greenhouse_16-30-02.log */
    public static String path() {
        LocalDate today = LocalDate.now();
        Path p = Log.LOG_DIR.resolve(today.toString()).resolve("greenhouse_" + Log.RUN_NAME + ".log");
        return p.toString().replace('\\', '/');
    }

    /** Closes the file at the end of the clean shutdown (the console keeps working). */
    public static synchronized void closeFile() {
        if (instance == null) return;
        synchronized (instance) {
            instance.closed = true;
            try {
                if (instance.file != null) instance.file.close();
            } catch (IOException ignored) {
                // nothing more to do at shutdown
            }
            instance.file = null;
        }
    }
}
