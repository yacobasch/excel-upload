package dev.mj.excelupload.controller;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import dev.mj.excelupload.conversion.Conversion;
import dev.mj.excelupload.conversion.ConversionNotFoundException;
import dev.mj.excelupload.conversion.ConversionService;
import dev.mj.excelupload.conversion.ConversionStore;
import dev.mj.excelupload.conversion.SheetFile;

@RestController
@RequestMapping("/conversions")
public class ConversionController {

	private final ConversionService conversionService;
	private final ConversionStore store;

	public ConversionController(ConversionService conversionService, ConversionStore store) {
		this.conversionService = conversionService;
		this.store = store;
	}

	@PostMapping
	public ResponseEntity<ConversionResponse> create(@RequestParam("file") MultipartFile file) throws IOException {
		ConversionResponse body = describe(conversionService.convert(file));
		return ResponseEntity.created(URI.create(body.url())).body(body);
	}

	@GetMapping("/{id}")
	public ConversionResponse get(@PathVariable String id) {
		return describe(find(id));
	}

	@GetMapping("/{id}/files/{index}")
	public ResponseEntity<byte[]> file(@PathVariable String id, @PathVariable int index) {
		SheetFile file = find(id).file(index)
				.orElseThrow(() -> new ConversionNotFoundException("No file " + index + " in conversion " + id));
		return attachment(file.fileName(), MediaType.APPLICATION_JSON, file.content());
	}

	@GetMapping("/{id}/zip")
	public ResponseEntity<byte[]> zip(@PathVariable String id) throws IOException {
		Conversion conversion = find(id);
		return attachment(conversion.baseName() + ".zip", MediaType.parseMediaType("application/zip"),
				conversionService.zip(conversion));
	}

	private Conversion find(String id) {
		return store.find(id)
				.orElseThrow(() -> new ConversionNotFoundException("Conversion " + id + " not found or expired"));
	}

	private ResponseEntity<byte[]> attachment(String fileName, MediaType type, byte[] content) {
		// Only use the RFC 5987 UTF-8 form when needed; plain ASCII names stay readable for simple clients
		ContentDisposition disposition = StandardCharsets.US_ASCII.newEncoder().canEncode(fileName)
				? ContentDisposition.attachment().filename(fileName).build()
				: ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build();
		return ResponseEntity.ok()
				.contentType(type)
				.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
				.body(content);
	}

	private ConversionResponse describe(Conversion conversion) {
		String base = "/conversions/" + conversion.id();
		List<FileLink> files = new ArrayList<>();
		for (int i = 0; i < conversion.files().size(); i++) {
			SheetFile f = conversion.files().get(i);
			files.add(new FileLink(f.fileName(), f.sheetName(), f.hidden(), f.rowCount(), f.content().length,
					link(base + "/files/" + i)));
		}
		return new ConversionResponse(conversion.id(), conversion.sourceFileName(), conversion.expiresAt(),
				link(base), link(base + "/zip"), files);
	}

	private String link(String path) {
		return ServletUriComponentsBuilder.fromCurrentContextPath().path(path).toUriString();
	}

	public record ConversionResponse(String id, String sourceFile, Instant expiresAt, String url, String zipUrl,
			List<FileLink> files) {
	}

	public record FileLink(String fileName, String sheetName, boolean hidden, int rows, int sizeBytes, String url) {
	}
}
