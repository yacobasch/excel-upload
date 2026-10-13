package dev.mj.excelupload.conversion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class ConversionStoreTests {

	private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

	@Test
	void expiresAfterTtl() {
		ConversionStore store = new ConversionStore(Duration.ofMinutes(30), 10, now::get);
		String id = store.save("a.xlsx", List.of()).id();

		now.set(now.get().plus(Duration.ofMinutes(29)));
		assertThat(store.find(id)).isPresent();

		now.set(now.get().plus(Duration.ofMinutes(1)));
		assertThat(store.find(id)).isEmpty();
	}

	@Test
	void dropsOldestWhenFull() {
		ConversionStore store = new ConversionStore(Duration.ofMinutes(30), 2, now::get);
		String first = store.save("1.xlsx", List.of()).id();
		String second = store.save("2.xlsx", List.of()).id();
		String third = store.save("3.xlsx", List.of()).id();

		assertThat(store.find(first)).isEmpty();
		assertThat(store.find(second)).isPresent();
		assertThat(store.find(third)).isPresent();
	}

	@Test
	void fileNamesAreSafeAndUnique() {
		FileNames.Allocator names = new FileNames.Allocator("json");
		assertThat(names.next("Sales", "sheet1")).isEqualTo("Sales.json");
		assertThat(names.next("sales", "sheet2")).isEqualTo("sales_2.json");
		assertThat(names.next("a/b:c", "sheet3")).isEqualTo("a_b_c.json");
		assertThat(names.next("..hidden", "sheet4")).isEqualTo("hidden.json");
		assertThat(names.next("   ", "sheet5")).isEqualTo("sheet5.json");
	}
}
