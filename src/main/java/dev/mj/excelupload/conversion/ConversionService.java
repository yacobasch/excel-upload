package dev.mj.excelupload.conversion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import dev.mj.excelupload.service.SheetData;
import dev.mj.excelupload.service.UploadService;

@Service
public class ConversionService {

	private final UploadService uploadService;
	private final ConversionStore store;
	private final ObjectWriter jsonWriter;

	public ConversionService(UploadService uploadService, ConversionStore store, ObjectMapper objectMapper) {
		this.uploadService = uploadService;
		this.store = store;
		this.jsonWriter = objectMapper.writerWithDefaultPrettyPrinter();
	}

	/** Converts every sheet to its own {@code <sheet name>.json} file and stores the result. */
	public Conversion convert(MultipartFile file) throws IOException {
		List<SheetData> sheets = uploadService.uploadAllSheets(file);

		FileNames.Allocator names = new FileNames.Allocator("json");
		List<SheetFile> files = new ArrayList<>();
		for (int i = 0; i < sheets.size(); i++) {
			SheetData sheet = sheets.get(i);
			files.add(new SheetFile(names.next(sheet.name(), "sheet" + (i + 1)), sheet.name(), sheet.hidden(),
					sheet.rows().size(), jsonWriter.writeValueAsBytes(sheet.rows())));
		}

		String sourceName = file.getOriginalFilename() == null ? "workbook" : file.getOriginalFilename();
		return store.save(sourceName, files);
	}

	public byte[] zip(Conversion conversion) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			for (SheetFile file : conversion.files()) {
				zip.putNextEntry(new ZipEntry(file.fileName()));
				zip.write(file.content());
				zip.closeEntry();
			}
		}
		return out.toByteArray();
	}
}
