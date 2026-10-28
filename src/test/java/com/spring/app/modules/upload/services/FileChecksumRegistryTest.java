package com.spring.app.modules.upload.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Uses a direct executor ({@code Runnable::run}) so {@code indexAsync} runs on
 * the test thread and is finished by the time {@code join()} returns.
 */
class FileChecksumRegistryTest {

  private final FileChecksumRegistry registry = new FileChecksumRegistry(Runnable::run);

  @TempDir
  Path dir;

  @Test
  void storesFirstCopyAndReportsHolderForDuplicate() throws IOException {
    AtomicInteger stores = new AtomicInteger();

    assertThat(registry.storeIfUnique("c1", "a.txt", stores::incrementAndGet)).isEmpty();
    assertThat(registry.storeIfUnique("c1", "b.txt", stores::incrementAndGet)).contains("a.txt");
    assertThat(stores).hasValue(1);
  }

  @Test
  void failedStoreIsNotRecorded() throws IOException {
    assertThatThrownBy(() -> registry.storeIfUnique("c1", "a.txt", () -> {
      throw new IOException("disk full");
    })).isInstanceOf(IOException.class);

    assertThat(registry.storeIfUnique("c1", "b.txt", () -> {})).isEmpty();
  }

  @Test
  void overwritingAFileReleasesItsOldContent() throws IOException {
    registry.storeIfUnique("c1", "a.txt", () -> {});
    registry.storeIfUnique("c2", "a.txt", () -> {});

    assertThat(registry.storeIfUnique("c1", "b.txt", () -> {})).isEmpty();
  }

  @Test
  void deletingAFileReleasesItsContent() throws IOException {
    registry.storeIfUnique("c1", "a.txt", () -> {});
    registry.delete("a.txt", () -> {});

    assertThat(registry.storeIfUnique("c1", "b.txt", () -> {})).isEmpty();
  }

  @Test
  void indexesExistingFilesButSkipsTempFilesAndDuplicates() throws IOException {
    Files.writeString(dir.resolve("a.txt"), "hello");
    Files.writeString(dir.resolve("b.txt"), "hello");
    Files.writeString(dir.resolve("c.txt"), "world");
    Files.writeString(dir.resolve(".upload-1.tmp"), "partial");

    assertThat(registry.indexAsync(dir).join()).isEqualTo(2);
    assertThat(registry.storeIfUnique(sha256("hello"), "x.txt", () -> {})).get().isIn("a.txt", "b.txt");
    assertThat(registry.storeIfUnique(sha256("world"), "y.txt", () -> {})).contains("c.txt");
    assertThat(registry.storeIfUnique(sha256("partial"), "z.txt", () -> {})).isEmpty();
  }

  @Test
  void indexingDoesNotOverrideAnUploadThatRanFirst() throws IOException {
    Files.writeString(dir.resolve("a.txt"), "hello");
    registry.storeIfUnique("newer-content", "a.txt", () -> {});

    assertThat(registry.indexAsync(dir).join()).isZero();
    assertThat(registry.storeIfUnique(sha256("hello"), "b.txt", () -> {})).isEmpty();
  }

  private static String sha256(String content) {
    MessageDigest digest = FileChecksumRegistry.newDigest();
    digest.update(content.getBytes(StandardCharsets.UTF_8));
    return FileChecksumRegistry.toHex(digest);
  }
}
