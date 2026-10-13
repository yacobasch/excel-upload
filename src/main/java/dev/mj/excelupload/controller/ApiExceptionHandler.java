package dev.mj.excelupload.controller;

import java.io.IOException;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.UnsupportedFileFormatException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import dev.mj.excelupload.conversion.ConversionNotFoundException;
import dev.mj.excelupload.service.InvalidUploadException;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler({ InvalidUploadException.class, IOException.class,
			EncryptedDocumentException.class, UnsupportedFileFormatException.class })
	public ProblemDetail handleBadFile(Exception e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"Could not read Excel file: " + e.getMessage());
	}

	@ExceptionHandler(ConversionNotFoundException.class)
	public ProblemDetail handleNotFound(ConversionNotFoundException e) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
	}
}
