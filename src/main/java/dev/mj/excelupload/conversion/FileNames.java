package dev.mj.excelupload.conversion;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Turns sheet names into safe, unique file names. */
final class FileNames {

	private FileNames() {
	}

	/** Replaces characters that are unsafe in file names on common file systems. */
	static String sanitize(String name) {
		return name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
				.strip()
				.replaceFirst("^\\.+", "");
	}

	/** Hands out unique {@code <name>.<extension>} file names, compared case-insensitively. */
	static class Allocator {

		private final Set<String> used = new HashSet<>();
		private final String extension;

		Allocator(String extension) {
			this.extension = extension;
		}

		String next(String name, String fallback) {
			String base = sanitize(name);
			if (base.isEmpty()) {
				base = fallback;
			}
			String candidate = base + "." + extension;
			for (int n = 2; !used.add(candidate.toLowerCase(Locale.ROOT)); n++) {
				candidate = base + "_" + n + "." + extension;
			}
			return candidate;
		}
	}
}
