package com.homeftw.ae2intelligentscheduling.smartcraft.runtime;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * v0.1.9.3 (G12-fix) Pure I/O layer for SmartCraft order persistence. Replaces the v0.1.9
 * {@code SmartCraftOrderWorldData} (extends Forge {@code WorldSavedData}) which never actually
 * wrote orders to disk in practice -- vanilla 1.7.10's {@code MapStorage.saveAllData} call sites
 * are not reliably hit on every world save / server stop path, so the dirty flag chain we relied
 * on silently dropped data on restart.
 *
 * <p>This class is deliberately framework-free: no Forge / vanilla {@code World} references in
 * its public API, just two static methods over a {@link File}. That makes it trivial to unit-test
 * with a JUnit {@code @TempDir} and gives us a single point to add atomic-write semantics on top
 * of the existing {@link SmartCraftOrderManager#writeToNBT} / {@link SmartCraftOrderManager#loadFromNBT}.
 *
 * <p>Wire-format compatibility note: vanilla {@code MapStorage} wrapped our compound under a
 * top-level {@code "data"} tag. Files written by v0.1.9 / v0.1.9.1 / v0.1.9.2 (when they got
 * written at all) carry that wrapper. {@link #readFromFile} transparently unwraps it so existing
 * saves continue to load. New writes from v0.1.9.3 onward write the {@code "data"} key flat.
 */
public final class SmartCraftPersistence {

    private static final Logger LOGGER = LogManager.getLogger("AE2IS-Persist");
    public static final String FILE_NAME = "AE2IS_SmartCraftOrders.dat";
    private static final String TMP_SUFFIX = ".tmp";

    private SmartCraftPersistence() {}

    /**
     * Resolve the persistence file path under {@code <world>/data/}. Creates the {@code data}
     * subdirectory if it doesn't exist (vanilla creates it lazily on first WorldSavedData write,
     * so it may not be present on a pristine save). Returns the canonical target file -- callers
     * pass this to {@link #writeToFile} / {@link #readFromFile}.
     *
     * @param worldDir the {@code <world>} directory (e.g. {@code saves/MyWorld} on the integrated
     *                 server, or {@code world} on a dedicated server). Must not be null.
     */
    public static File dataFile(File worldDir) {
        if (worldDir == null) throw new IllegalArgumentException("worldDir must not be null");
        File dataDir = new File(worldDir, "data");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            // mkdirs returns false if the directory already exists OR if creation failed. Re-check
            // existence to disambiguate; only log the warning on a real creation failure.
            if (!dataDir.exists()) {
                LOGGER.warn("Could not create data directory: {}", dataDir.getAbsolutePath());
            }
        }
        return new File(dataDir, FILE_NAME);
    }

    /**
     * Atomically write the manager's NBT into {@code target}. Algorithm:
     * <ol>
     * <li>Serialize {@link SmartCraftOrderManager#writeToNBT} into an in-memory compound.</li>
     * <li>GZIP-compress + write to {@code target.tmp}.</li>
     * <li>Delete the live target if it exists (Windows can't rename onto an existing file).</li>
     * <li>Rename {@code target.tmp} onto {@code target}.</li>
     * </ol>
     *
     * <p>Crash-safety: a JVM termination between steps 2 and 4 leaves an orphan {@code .tmp} file
     * but never corrupts the live target. On the next start we ignore orphan tmp files; the next
     * successful save overwrites them.
     *
     * <p>This is "best-effort atomic" -- on Windows an actual atomic rename across an existing
     * file is impossible without {@code java.nio.file.Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)}
     * which is JDK7+. We're targeting JDK8 so we could use it, but to keep the legacy I/O paths
     * uniform with the rest of the GTNH 1.7.10 codebase we stick with {@code File#renameTo}.
     */
    public static void writeToFile(File target, SmartCraftOrderManager manager) throws IOException {
        if (target == null) throw new IllegalArgumentException("target must not be null");
        if (manager == null) throw new IllegalArgumentException("manager must not be null");

        NBTTagCompound root = new NBTTagCompound();
        manager.writeToNBT(root);

        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw new IOException("Could not create parent directory: " + parent.getAbsolutePath());
        }

        File tmp = new File(parent, target.getName() + TMP_SUFFIX);
        // Defensive cleanup: if a previous crash left a tmp file, delete it before reusing the name.
        if (tmp.exists() && !tmp.delete()) {
            throw new IOException("Could not delete stale temp file: " + tmp.getAbsolutePath());
        }
        FileOutputStream fos = new FileOutputStream(tmp);
        BufferedOutputStream bos = new BufferedOutputStream(fos);
        try {
            CompressedStreamTools.writeCompressed(root, bos);
            bos.flush();
            // fsync the file descriptor so the OS-level page cache flushes to disk before we
            // proceed to the rename. Without this, an OS crash (not a JVM crash) between rename
            // and disk flush could lose the rename + the data.
            try {
                fos.getFD()
                    .sync();
            } catch (IOException syncFailed) {
                // Some filesystems (tmpfs, network mounts) reject fsync. Log + continue; the
                // worst case is a power-cut data loss window, the same as vanilla's behaviour.
                LOGGER.debug("fsync on {} failed: {}", tmp, syncFailed.getMessage());
            }
        } finally {
            bos.close();
        }

        // Windows-compatible rename: delete the old file first (no-op if it doesn't exist).
        if (target.exists() && !target.delete()) {
            // Couldn't delete the live file. Don't leave the tmp orphan -- delete it too so we
            // don't accumulate them. Then surface the error so the dirty flag stays set and the
            // next save round retries.
            if (!tmp.delete()) {
                LOGGER.warn("Could not clean up tmp file after rename failure: {}", tmp);
            }
            throw new IOException("Could not delete existing target file: " + target.getAbsolutePath());
        }
        if (!tmp.renameTo(target)) {
            throw new IOException(
                "Could not rename " + tmp.getAbsolutePath() + " -> " + target.getAbsolutePath());
        }
    }

    /**
     * Load {@code source} into the given manager. Behaviour:
     * <ul>
     * <li>File doesn't exist: log info ("first run / no orders") and return without touching the
     * manager. Manager keeps whatever in-memory state it has (typically empty).</li>
     * <li>File exists but corrupt / unreadable: log warn with the cause and return WITHOUT
     * mutating the manager. The next successful write overwrites the corrupt file. We never
     * crash the server start on a bad save file -- corrupting one mod's data file should not
     * brick the world.</li>
     * <li>File exists and parses OK: detect legacy "data" wrapper (from the v0.1.9 vanilla
     * {@code WorldSavedData} era) and unwrap if present, then forward to
     * {@link SmartCraftOrderManager#loadFromNBT}.</li>
     * </ul>
     */
    public static void readFromFile(File source, SmartCraftOrderManager manager) {
        if (source == null) throw new IllegalArgumentException("source must not be null");
        if (manager == null) throw new IllegalArgumentException("manager must not be null");

        if (!source.exists()) {
            LOGGER.info(
                "Smart craft persistence file not found at {} (first run, nothing to load)",
                source.getAbsolutePath());
            return;
        }
        try {
            FileInputStream fis = new FileInputStream(source);
            BufferedInputStream bis = new BufferedInputStream(fis);
            NBTTagCompound root;
            try {
                root = CompressedStreamTools.readCompressed(bis);
            } finally {
                bis.close();
            }

            // Compatibility: vanilla MapStorage wrapped the saved data under a "data" tag. If we
            // detect that wrapper, unwrap it. New writes from v0.1.9.3 are flat.
            NBTTagCompound payload = root.hasKey("data") ? root.getCompoundTag("data") : root;
            manager.loadFromNBT(payload);
            LOGGER.info(
                "Smart craft persistence: loaded from {} ({} orders in memory after load)",
                source.getAbsolutePath(),
                Integer.valueOf(
                    manager.snapshot()
                        .size()));
        } catch (IOException e) {
            LOGGER.warn(
                "Failed to read smart craft persistence file {}, leaving manager unchanged: {}",
                source.getAbsolutePath(),
                e.toString());
        } catch (RuntimeException e) {
            // NBT parse errors (e.g. truncated file) bubble up as RuntimeException. Same fail-soft
            // policy: log + continue. Better an empty order list than a server start failure.
            LOGGER.warn(
                "Smart craft persistence file {} appears corrupt, leaving manager unchanged: {}",
                source.getAbsolutePath(),
                e.toString());
        }
    }
}
