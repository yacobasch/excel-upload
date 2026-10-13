package dev.mj.excelupload.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ConversionControllerTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void convertsEachSheetToItsOwnFile() throws Exception {
		byte[] xlsx = workbook();

		String body = mvc.perform(multipart("/conversions").file(new MockMultipartFile("file", "shop.xlsx", null, xlsx)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		JsonNode conversion = objectMapper.readTree(body);
		String id = conversion.get("id").asText();

		assertThat(conversion.get("sourceFile").asText()).isEqualTo("shop.xlsx");
		assertThat(conversion.get("url").asText()).endsWith("/conversions/" + id);
		assertThat(conversion.get("zipUrl").asText()).endsWith("/conversions/" + id + "/zip");
		JsonNode files = conversion.get("files");
		assertThat(files).hasSize(4);
		assertThat(files.findValuesAsText("fileName"))
				.containsExactly("People.json", "Orders 2024.json", "Q1_Q2.json", "Q1_Q2_2.json");
		assertThat(files.get(1).get("rows").asInt()).isEqualTo(2);
		assertThat(files.get(3).get("hidden").asBoolean()).isTrue();

		mvc.perform(get("/conversions/" + id + "/files/1"))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Disposition", "attachment; filename=\"Orders 2024.json\""))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].id").value("1"))
				.andExpect(jsonPath("$[1].total").value("20.5"));

		byte[] zip = mvc.perform(get("/conversions/" + id + "/zip"))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "application/zip"))
				.andReturn().getResponse().getContentAsByteArray();
		Map<String, JsonNode> entries = unzip(zip);
		assertThat(entries).containsOnlyKeys("People.json", "Orders 2024.json", "Q1_Q2.json", "Q1_Q2_2.json");
		assertThat(entries.get("People.json").get(0).get("name").asText()).isEqualTo("Ann");

		mvc.perform(get("/conversions/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.files.length()").value(4));
	}

	@Test
	void unknownConversionOrFileIsNotFound() throws Exception {
		mvc.perform(get("/conversions/does-not-exist/zip")).andExpect(status().isNotFound());

		String body = mvc.perform(multipart("/conversions").file(new MockMultipartFile("file", "shop.xlsx", null, workbook())))
				.andReturn().getResponse().getContentAsString();
		String id = objectMapper.readTree(body).get("id").asText();
		mvc.perform(get("/conversions/" + id + "/files/9")).andExpect(status().isNotFound());
	}

	@Test
	void rejectsNonExcelFile() throws Exception {
		mvc.perform(multipart("/conversions").file(new MockMultipartFile("file", "notes.txt", null, "hello".getBytes())))
				.andExpect(status().isBadRequest());
	}

	private byte[] workbook() throws Exception {
		try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet people = wb.createSheet("People");
			row(people, 0, "name", "age");
			row(people, 1, "Ann", "42");

			Sheet orders = wb.createSheet("Orders 2024");
			row(orders, 0, "id", "total");
			Row r1 = orders.createRow(1);
			r1.createCell(0).setCellValue(1);
			r1.createCell(1).setCellValue(9.99);
			Row r2 = orders.createRow(2);
			r2.createCell(0).setCellValue(2);
			r2.createCell(1).setCellValue(20.5);

			// Excel forbids "/" in sheet names, but other tools may not; "<" and ">" are allowed in Excel
			wb.createSheet("Q1<Q2");

			// Sanitizes to the same file name as "Q1<Q2"
			wb.createSheet("Q1_Q2");
			wb.setSheetHidden(3, true);

			wb.write(out);
			return out.toByteArray();
		}
	}

	private void row(Sheet sheet, int index, String... values) {
		Row row = sheet.createRow(index);
		for (int i = 0; i < values.length; i++) {
			row.createCell(i).setCellValue(values[i]);
		}
	}

	private Map<String, JsonNode> unzip(byte[] zip) throws Exception {
		Map<String, JsonNode> entries = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
			for (ZipEntry e; (e = in.getNextEntry()) != null;) {
				entries.put(e.getName(), objectMapper.readTree(in.readAllBytes()));
			}
		}
		return entries;
	}
}
