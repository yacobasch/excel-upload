package dev.mj.excelupload.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import dev.mj.excelupload.service.UploadService;

@WebMvcTest(UploadController.class)
@Import(UploadService.class)
class UploadControllerTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void convertsMixedCellTypesAndBlanks() throws Exception {
		byte[] xlsx;
		try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			CellStyle dateStyle = wb.createCellStyle();
			dateStyle.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));

			Sheet sheet = wb.createSheet();
			Row header = sheet.createRow(0);
			header.createCell(0).setCellValue("name");
			header.createCell(1).setCellValue("age");
			header.createCell(2).setCellValue("joined");
			header.createCell(3).setCellValue("double");

			Row ann = sheet.createRow(1);
			ann.createCell(0).setCellValue("Ann");
			ann.createCell(1).setCellValue(42);
			ann.createCell(2).setCellValue(LocalDate.of(2024, 1, 15));
			ann.getCell(2).setCellStyle(dateStyle);
			ann.createCell(3).setCellFormula("B2*2");

			// Bob has no age cell at all; row 3 is skipped entirely
			Row bob = sheet.createRow(3);
			bob.createCell(0).setCellValue("Bob");

			wb.write(out);
			xlsx = out.toByteArray();
		}

		mvc.perform(multipart("/upload").file(new MockMultipartFile("file", "people.xlsx", null, xlsx)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].name").value("Ann"))
				.andExpect(jsonPath("$[0].age").value("42"))
				.andExpect(jsonPath("$[0].joined").value("2024-01-15"))
				.andExpect(jsonPath("$[0].double").value("84"))
				.andExpect(jsonPath("$[1].name").value("Bob"))
				.andExpect(jsonPath("$[1].age").value(""))
				.andExpect(jsonPath("$[1].joined").value(""));
	}

	@Test
	void rejectsNonExcelFile() throws Exception {
		mvc.perform(multipart("/upload").file(new MockMultipartFile("file", "notes.txt", null, "hello".getBytes())))
				.andExpect(status().isBadRequest());
	}

	@Test
	void rejectsEmptyFile() throws Exception {
		mvc.perform(multipart("/upload").file(new MockMultipartFile("file", "empty.xlsx", null, new byte[0])))
				.andExpect(status().isBadRequest());
	}
}
