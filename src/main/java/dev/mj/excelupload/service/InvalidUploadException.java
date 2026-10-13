package dev.mj.excelupload.service;

public class InvalidUploadException extends RuntimeException {

	public InvalidUploadException(String message) {
		super(message);
	}

}
