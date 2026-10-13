package dev.mj.excelupload.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Row.MissingCellPolicy;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class UploadService {

	public List<Map<String, String>> upload(MultipartFile file) throws IOException {
		try (InputStream in = open(file)) {
			return parse(in);
		}
	}

	public List<SheetData> uploadAllSheets(MultipartFile file) throws IOException {
		try (InputStream in = open(file)) {
			return parseAll(in);
		}
	}

	/**
	 * Reads the first sheet, using the first row as headers. Each following
	 * non-blank row becomes a map of header -> displayed cell value, in column order.
	 */
	public List<Map<String, String>> parse(InputStream in) throws IOException {
		try (Workbook workbook = WorkbookFactory.create(in)) {
			if (workbook.getNumberOfSheets() == 0) {
				return List.of();
			}
			return readSheet(workbook.getSheetAt(0), new DataFormatter(), evaluator(workbook));
		}
	}

	/**
	 * Reads every sheet in workbook order, each with its own header row.
	 */
	public List<SheetData> parseAll(InputStream in) throws IOException {
		try (Workbook workbook = WorkbookFactory.create(in)) {
			DataFormatter formatter = new DataFormatter();
			FormulaEvaluator evaluator = evaluator(workbook);
			List<SheetData> sheets = new ArrayList<>();
			for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
				Sheet sheet = workbook.getSheetAt(i);
				boolean hidden = workbook.isSheetHidden(i) || workbook.isSheetVeryHidden(i);
				sheets.add(new SheetData(sheet.getSheetName(), hidden, readSheet(sheet, formatter, evaluator)));
			}
			return sheets;
		}
	}

	private InputStream open(MultipartFile file) throws IOException {
		if (file.isEmpty()) {
			throw new InvalidUploadException("Uploaded file is empty");
		}
		return file.getInputStream();
	}

	private FormulaEvaluator evaluator(Workbook workbook) {
		return workbook.getCreationHelper().createFormulaEvaluator();
	}

	private List<Map<String, String>> readSheet(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
		Row headerRow = sheet.getRow(sheet.getFirstRowNum());
		if (headerRow == null) {
			return List.of();
		}
		List<String> headers = readHeaders(headerRow, formatter, evaluator);

		List<Map<String, String>> result = new ArrayList<>();
		for (int r = headerRow.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
			Row row = sheet.getRow(r);
			if (row == null) {
				continue;
			}
			Map<String, String> record = new LinkedHashMap<>();
			boolean blank = true;
			for (int c = 0; c < headers.size(); c++) {
				String value = cellText(row, c, formatter, evaluator);
				blank &= value.isEmpty();
				record.put(headers.get(c), value);
			}
			if (!blank) {
				result.add(record);
			}
		}
		return result;
	}

	private List<String> readHeaders(Row headerRow, DataFormatter formatter, FormulaEvaluator evaluator) {
		List<String> headers = new ArrayList<>();
		for (int c = 0; c < headerRow.getLastCellNum(); c++) {
			String name = cellText(headerRow, c, formatter, evaluator).trim();
			if (name.isEmpty()) {
				name = "column" + (c + 1);
			}
			// Keep duplicate headers distinct so no column gets overwritten
			String unique = name;
			for (int n = 2; headers.contains(unique); n++) {
				unique = name + "_" + n;
			}
			headers.add(unique);
		}
		return headers;
	}

	private String cellText(Row row, int column, DataFormatter formatter, FormulaEvaluator evaluator) {
		Cell cell = row.getCell(column, MissingCellPolicy.RETURN_BLANK_AS_NULL);
		return cell == null ? "" : formatter.formatCellValue(cell, evaluator);
	}

}
