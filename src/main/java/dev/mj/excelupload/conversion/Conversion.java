package dev.mj.excelupload.conversion;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** One uploaded workbook, converted to one JSON file per sheet. */
public record Conversion(String id, String sourceFileName, Instant createdAt, Instant expiresAt, List<SheetFile> files) {

	public Optional<SheetFile> file(int index) {
		return index >= 0 && index < files.size() ? Optional.of(files.get(index)) : Optional.empty();
	}

	/** Source file name without its extension, used to name the zip. */
	public String baseName() {
		String base = FileNames.sanitize(sourceFileName.replaceFirst("\\.[^.]*$", ""));
		return base.isEmpty() ? "workbook" : base;
	}
}
