package com.spring.app.modules.upload.services;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Index from content checksum (SHA-256) to the stored file holding that
 * content, used to avoid storing the same upload twice.
 *
 * <p>
 * Every operation that changes both the index and the disk runs under one
 * {@link ReentrantLock}, so the two cannot disagree. It is a lock rather than
 * {@code synchronized} because callers run on virtual threads, and on Java 21
 * blocking inside {@code synchronized} pins the carrier thread. Only the final
 * rename/delete happens under the lock; copying and hashing do not.
 * </p>
 *
 * <p>
 * The index lives in process memory: it is rebuilt from the upload directory
 * at startup, is not shared between instances, and does not see files changed
 * outside this API. A multi-instance deployment should persist the checksum
 * (with a unique index) next to the file metadata instead.
 * </p>
 */
@Slf4j
@Component
public class FileChecksumRegistry {

  private static final String ALGORITHM = "SHA-256";

  private final Executor taskExecutor;
  private final ReentrantLock lock = new ReentrantLock();
  private final Map<String, String> fileNameByChecksum = new HashMap<>();
  private final Map<String, String> checksumByFileName = new HashMap<>();

  // Explicit constructor: AsyncConfig defines two Executor beans, and Lombok
  // does not copy @Qualifier onto a generated constructor.
  public FileChecksumRegistry(@Qualifier("taskExecutor") Executor taskExecutor) {
    this.taskExecutor = taskExecutor;
  }

  public static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance(ALGORITHM);
    } catch (NoSuchAlgorithmException ex) {
      // Every Java platform is required to provide SHA-256.
      throw new IllegalStateException(ALGORITHM + " is not available", ex);
    }
  }

  public static String toHex(MessageDigest digest) {
    return HexFormat.of().formatHex(digest.digest());
  }

  /**
   * Runs {@code store} and records {@code fileName} as the holder of
   * {@code checksum} - unless a stored file already holds that content.
   *
   * @return the name of the file already holding this content, or empty when
   *         {@code store} ran
   */
  public Optional<String> storeIfUnique(String checksum, String fileName, IoAction store) throws IOException {
    lock.lock();
    try {
      String holder = fileNameByChecksum.get(checksum);
      if (holder != null) {
        return Optional.of(holder);
      }
      store.run();
      // The name may have held different content before being overwritten.
      forget(fileName);
      remember(checksum, fileName);
      return Optional.empty();
    } finally {
      lock.unlock();
    }
  }

  /** Runs {@code delete} and drops {@code fileName} from the index. */
  public void delete(String fileName, IoAction delete) throws IOException {
    lock.lock();
    try {
      delete.run();
      forget(fileName);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Indexes the files already in {@code directory}, on {@code taskExecutor}.
   * Nobody needs to wait for it: until it finishes, an upload duplicating an
   * older file is simply stored again.
   *
   * @return a future completing with the number of files indexed
   */
  public CompletableFuture<Integer> indexAsync(Path directory) {
    return CompletableFuture.supplyAsync(() -> index(directory), taskExecutor);
  }

  private int index(Path directory) {
    List<Path> files;
    try (Stream<Path> entries = Files.list(directory)) {
      // In-flight uploads are written to dot-prefixed temp files.
      files = entries
        .filter(Files::isRegularFile)
        .filter(file -> !file.getFileName().toString().startsWith("."))
        .toList();
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }

    int indexed = 0;
    for (Path file : files) {
      if (Thread.currentThread().isInterrupted()) {
        break; // application is shutting down
      }
      if (indexExisting(file)) {
        indexed++;
      }
    }
    return indexed;
  }

  private boolean indexExisting(Path file) {
    String checksum;
    try {
      checksum = checksumOf(file);
    } catch (IOException ex) {
      log.warn("Skipping {} while indexing upload checksums: {}", file, ex.getMessage());
      return false;
    }

    String fileName = file.getFileName().toString();
    lock.lock();
    try {
      // An upload or delete that ran while this file was being hashed has
      // already left the index in the right state - do not overwrite it.
      if (!Files.isRegularFile(file)
        || checksumByFileName.containsKey(fileName)
        || fileNameByChecksum.containsKey(checksum)) {
        return false;
      }
      remember(checksum, fileName);
      return true;
    } finally {
      lock.unlock();
    }
  }

  private static String checksumOf(Path file) throws IOException {
    MessageDigest digest = newDigest();
    try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
      in.transferTo(OutputStream.nullOutputStream());
    }
    return toHex(digest);
  }

  private void remember(String checksum, String fileName) {
    fileNameByChecksum.put(checksum, fileName);
    checksumByFileName.put(fileName, checksum);
  }

  private void forget(String fileName) {
    String checksum = checksumByFileName.remove(fileName);
    if (checksum != null) {
      fileNameByChecksum.remove(checksum);
    }
  }

  /** A file-system action that may fail with an {@link IOException}. */
  @FunctionalInterface
  public interface IoAction {
    void run() throws IOException;
  }
}
