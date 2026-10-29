package org.mydreamduels.dreammultiverse.util;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One background thread for every file write, so writes to the same file
 * always land in the order they were queued (the plain async scheduler
 * gives no such guarantee) and nothing ever blocks a server/region thread.
 */
public final class IoQueue {

    private final Logger logger;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Dreammultiverse-IO");
        t.setDaemon(true);
        return t;
    });

    public IoQueue(Logger logger) {
        this.logger = logger;
    }

    public void write(File file, String content) {
        submit(() -> {
            try {
                FileUtil.writeAtomically(file, content);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Could not save " + file.getName(), e);
            }
        });
    }

    public void submit(Runnable task) {
        if (executor.isShutdown()) {
            task.run();
            return;
        }
        executor.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "Background task failed", t);
            }
        });
    }

    /** Blocks until everything queued so far is on disk - used before re-reading files on /mv reload. */
    public void flush() {
        if (executor.isShutdown()) return;
        try {
            executor.submit(() -> { }).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warning("Timed out waiting for pending file writes: " + e);
        }
    }

    /** Drains everything queued so far - called from onDisable. */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warning("Timed out waiting for pending file writes to finish");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
