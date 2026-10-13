package dev.mj.excelupload.conversion;

/** The JSON produced from a single sheet. */
public record SheetFile(String fileName, String sheetName, boolean hidden, int rowCount, byte[] content) {
}
