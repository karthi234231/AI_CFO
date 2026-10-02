package com.fintech.cfo.platform.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import com.fintech.cfo.shared.exception.NotFoundException;
import com.fintech.cfo.shared.exception.ValidationException;
import com.fintech.cfo.shared.util.HashUtils;

/**
 * Tenant-scoped, checksum-verified facade over {@link ObjectStoragePort}.
 *
 * <p>Responsibilities:
 * <ul>
 * <li>Naming objects by tenant, so one tenant's key can never be used to reach
 * another's evidence.</li>
 * <li>Computing and verifying SHA-256, so a corrupted or substituted document
 * is detected rather than presented as evidence.</li>
 * </ul>
 *
 * <p>The checksum is why this is not a thin passthrough: an evidence attachment
 * that cannot be proven to be the file which produced a number is worthless for
 * audit.
 */
@Service
public class ObjectStorageService {

	private static final Logger log = LoggerFactory.getLogger(ObjectStorageService.class);

	private final ObjectStoragePort objectStoragePort;

	public ObjectStorageService(ObjectStoragePort objectStoragePort) {
		this.objectStoragePort = objectStoragePort;
	}

	/**
	 * Stores content under a tenant-scoped key and returns metadata including
	 * the computed checksum.
	 *
	 * @param tenantId       owning tenant
	 * @param category       logical grouping, e.g. {@code evidence}, {@code source}
	 * @param originalName   original file name, used for display only
	 * @param contentType    MIME type
	 * @param content        payload stream; not closed by this method
	 * @param declaredLength expected length, or {@code -1} if unknown
	 */
	public StorageObject store(UUID tenantId, String category, String originalName, String contentType,
			InputStream content, long declaredLength) {
		String key = buildKey(tenantId, category, originalName);
		StorageObject stored = this.objectStoragePort.store(key, content, contentType, declaredLength);
		log.info("Stored object key={} bytes={} checksum={}", stored.storageKey(), stored.contentLength(),
				stored.checksum());
		return stored;
	}

	/**
	 * Verifies that content still matches its recorded checksum.
	 *
	 * <p>The content is hashed as a stream, so verification does not require
	 * the whole document in memory. A read failure returns {@code false}: an
	 * unverifiable document must never be treated as a verified one.
	 */
	public boolean verify(StorageObject stored, InputStream content) {
		if (stored == null || stored.checksum() == null || content == null) {
			return false;
		}
		try {
			String actual = HashUtils.sha256(content);
			return actual != null && actual.equals(stored.checksum());
		}
		finally {
			closeQuietly(content);
		}
	}

	public InputStream retrieve(UUID tenantId, String key) {
		return this.objectStoragePort.retrieve(requireTenantPrefix(tenantId, key));
	}

	public void delete(UUID tenantId, String key) {
		this.objectStoragePort.delete(requireTenantPrefix(tenantId, key));
	}

	private String buildKey(UUID tenantId, String category, String originalName) {
		return tenantId + "/" + sanitize(category) + "/" + UUID.randomUUID() + "-" + sanitize(originalName);
	}

	/**
	 * Rejects a key that is not already under the caller's tenant prefix.
	 *
	 * <p>Reported as "not found" rather than "forbidden" so the response cannot
	 * be used to confirm that another tenant's object exists.
	 */
	private static String requireTenantPrefix(UUID tenantId, String key) {
		if (tenantId == null || key == null || !key.startsWith(tenantId + "/")) {
			throw new NotFoundException("Storage object not found");
		}
		return key;
	}

	private static String sanitize(String value) {
		if (value == null || value.isBlank()) {
			return "unnamed";
		}
		String cleaned = value.replaceAll("[^A-Za-z0-9._-]", "_");
		return cleaned.length() > 120 ? cleaned.substring(cleaned.length() - 120) : cleaned;
	}

	private static void closeQuietly(InputStream content) {
		try {
			content.close();
		}
		catch (IOException ignored) {
			// closing a verification stream cannot invalidate the verdict
		}
	}

	/**
	 * Registers the development default: a sandboxed local-filesystem store.
	 *
	 * <p>Declared as a bean method rather than a {@code @Component} so that
	 * {@link ConditionalOnMissingBean} is evaluated correctly and an
	 * S3-compatible implementation can replace it simply by declaring its own
	 * {@link ObjectStoragePort}.
	 */
	@Configuration(proxyBeanMethods = false)
	static class LocalStorageConfiguration {

		@Bean
		@ConditionalOnMissingBean(ObjectStoragePort.class)
		ObjectStoragePort localFileObjectStoragePort(
				@org.springframework.beans.factory.annotation.Value(
						"${cfo.storage.local-root:${java.io.tmpdir}/cfo-storage}") String rootPath) {
			return new LocalFileObjectStorageAdapter(rootPath);
		}
	}

	/**
	 * Filesystem-backed store used when no other port is configured.
	 */
	static final class LocalFileObjectStorageAdapter implements ObjectStoragePort {

		private final Path root;

		LocalFileObjectStorageAdapter(String rootPath) {
			try {
				this.root = Path.of(rootPath).toAbsolutePath().normalize();
				Files.createDirectories(this.root);
			}
			catch (IOException ex) {
				throw new IllegalStateException("Unable to initialise local object storage at " + rootPath, ex);
			}
		}

		@Override
		public StorageObject store(String key, InputStream content, String contentType, long declaredLength) {
			Path target = resolve(key);
			try {
				Files.createDirectories(target.getParent());
				Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
				long size = Files.size(target);
				String checksum;
				try (InputStream in = Files.newInputStream(target)) {
					checksum = HashUtils.sha256(in);
				} catch (java.io.UncheckedIOException ex) {
					throw new IllegalStateException("Failed to checksum stored object " + key, ex.getCause());
				}
				return new StorageObject(key, size, contentType, checksum, humanSize(size), Instant.now());
			}
			catch (IOException ex) {
				throw new IllegalStateException("Failed to store object " + key, ex);
			}
		}

		@Override
		public InputStream retrieve(String key) {
			Path target = resolve(key);
			try {
				if (!Files.exists(target)) {
					throw new NotFoundException("Storage object not found");
				}
				return Files.newInputStream(target);
			}
			catch (IOException ex) {
				throw new IllegalStateException("Failed to read object " + key, ex);
			}
		}

		@Override
		public void delete(String key) {
			try {
				Files.deleteIfExists(resolve(key));
			}
			catch (IOException ex) {
				throw new IllegalStateException("Failed to delete object " + key, ex);
			}
		}

		@Override
		public Path localPath(String key) {
			return resolve(key);
		}

		/**
		 * Normalizes the key and confirms it stays inside the storage root, so a
		 * crafted key containing {@code ../} cannot escape the sandbox.
		 */
		private Path resolve(String key) {
			Path resolved = this.root.resolve(key).normalize();
			if (!resolved.startsWith(this.root)) {
				throw new ValidationException("Invalid storage key");
			}
			return resolved;
		}

		private static String humanSize(long bytes) {
			if (bytes < 1024) {
				return bytes + " B";
			}
			if (bytes < 1024L * 1024L) {
				return (bytes / 1024) + " KB";
			}
			return (bytes / (1024L * 1024L)) + " MB";
		}
	}

}