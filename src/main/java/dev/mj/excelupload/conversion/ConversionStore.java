package dev.mj.excelupload.conversion;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Keeps finished conversions in memory so their files can be downloaded later.
 * Entries expire after a TTL, and the oldest are dropped once the store is full.
 */
@Component
public class ConversionStore {

	// Insertion order is creation order, so the first entry is always the oldest
	private final Map<String, Conversion> conversions = new LinkedHashMap<>();
	private final Duration ttl;
	private final int maxEntries;
	private final Supplier<Instant> clock;

	@Autowired
	public ConversionStore(@Value("${excel-upload.conversions.ttl:30m}") Duration ttl,
			@Value("${excel-upload.conversions.max-entries:100}") int maxEntries) {
		this(ttl, maxEntries, Instant::now);
	}

	ConversionStore(Duration ttl, int maxEntries, Supplier<Instant> clock) {
		this.ttl = ttl;
		this.maxEntries = maxEntries;
		this.clock = clock;
	}

	public synchronized Conversion save(String sourceFileName, List<SheetFile> files) {
		evictExpired();
		Iterator<String> oldest = conversions.keySet().iterator();
		while (conversions.size() >= maxEntries && oldest.hasNext()) {
			oldest.next();
			oldest.remove();
		}
		Instant now = clock.get();
		Conversion conversion = new Conversion(UUID.randomUUID().toString(), sourceFileName, now, now.plus(ttl),
				List.copyOf(files));
		conversions.put(conversion.id(), conversion);
		return conversion;
	}

	public synchronized Optional<Conversion> find(String id) {
		evictExpired();
		return Optional.ofNullable(conversions.get(id));
	}

	private void evictExpired() {
		Instant now = clock.get();
		conversions.values().removeIf(c -> !c.expiresAt().isAfter(now));
	}
}
