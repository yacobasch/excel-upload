package dev.mj.excelupload.service;

import java.util.List;
import java.util.Map;

public record SheetData(String name, boolean hidden, List<Map<String, String>> rows) {
}
