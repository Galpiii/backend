package com.github.galpiii.galpi.domain.featurespec.validator;

import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;

@Slf4j
@Component
public class FeatureSpecFileValidator {

    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final int MIN_PAGE_COUNT = 1;
    private static final int MAX_PAGE_COUNT = 100;
    private static final String PDF_EXTENSION = "pdf";
    private static final String TEMP_FILE_PREFIX = "feature-spec-";
    private static final String TEMP_FILE_SUFFIX = ".pdf";

    // 업로드된 기능명세서 PDF를 검증하고 저장에 사용할 파일명을 반환한다.
    public String validate(MultipartFile file) {
        String fileName = validateBasicFile(file);
        validatePdfStructure(file);

        return fileName;
    }

    // 파일 존재 여부, 파일명, 확장자, 크기, Content-Type 검증
    private String validateBasicFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException(ErrorCode.FEATURE_SPEC_FILE_EMPTY);
        }

        String originalFilename = file.getOriginalFilename();

        if (!StringUtils.hasText(originalFilename)) {
            throw new BadRequestException(ErrorCode.FEATURE_SPEC_FILE_NAME_MISSING);
        }

        // 경로 구분자가 포함된 파일명이 그대로 저장되지 않도록 파일명만 남긴다.
        String fileName = StringUtils.getFilename(StringUtils.cleanPath(originalFilename));

        if (!StringUtils.hasText(fileName)) {
            throw new BadRequestException(ErrorCode.FEATURE_SPEC_FILE_NAME_MISSING);
        }

        if (fileName.length() > MAX_FILE_NAME_LENGTH) {
            throw new BadRequestException(ErrorCode.FEATURE_SPEC_FILE_NAME_TOO_LONG);
        }

        String extension = StringUtils.getFilenameExtension(fileName);

        if (!PDF_EXTENSION.equalsIgnoreCase(extension)) {
            throw new BadRequestException(ErrorCode.FEATURE_SPEC_FILE_EXTENSION_INVALID);
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BadRequestException(ErrorCode.FEATURE_SPEC_FILE_SIZE_EXCEEDED);
        }

        if (!MediaType.APPLICATION_PDF_VALUE.equalsIgnoreCase(file.getContentType())) {
            throw new BadRequestException(ErrorCode.FEATURE_SPEC_FILE_CONTENT_TYPE_INVALID);
        }

        return fileName;
    }

    // PDF 파일을 열어 구조와 암호화 여부 검증
    private void validatePdfStructure(MultipartFile file) {
        File tempFile = createTempFile(file);

        try {
            boolean encrypted;
            int pageCount;

            try (RandomAccessReadBufferedFile source = new RandomAccessReadBufferedFile(tempFile);
                 PDDocument document = Loader.loadPDF(source)) {
                encrypted = document.isEncrypted();
                pageCount = document.getNumberOfPages();
            } catch (InvalidPasswordException e) {
                throw new BadRequestException(ErrorCode.FEATURE_SPEC_PDF_ENCRYPTED);
            } catch (IOException | RuntimeException | StackOverflowError e) {
                log.warn("[기능명세서 업로드] PDF 파싱 실패. type: {}", e.getClass().getSimpleName());
                throw new BadRequestException(ErrorCode.FEATURE_SPEC_PDF_INVALID);
            }

            if (encrypted) {
                throw new BadRequestException(ErrorCode.FEATURE_SPEC_PDF_ENCRYPTED);
            }

            validatePageCount(pageCount);
        } finally {
            deleteTempFile(tempFile);
        }
    }

    private File createTempFile(MultipartFile file) {
        try {
            File tempFile = File.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX);
            file.transferTo(tempFile);

            return tempFile;
        } catch (IOException | IllegalStateException e) {
            log.error("[기능명세서 업로드] 임시 파일 생성 실패.", e);
            throw new GlobalException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    private void deleteTempFile(File tempFile) {
        if (!tempFile.delete()) {
            log.warn("[기능명세서 업로드] 임시 파일 삭제 실패. path: {}", tempFile.getAbsolutePath());
        }
    }

    // PDF 페이지 수가 1페이지 이상 100페이지 이하인지 검증
    private void validatePageCount(int pageCount) {
        if (pageCount < MIN_PAGE_COUNT) {
            throw new BadRequestException(ErrorCode.FEATURE_SPEC_PDF_PAGE_MISSING);
        }

        if (pageCount > MAX_PAGE_COUNT) {
            throw new BadRequestException(ErrorCode.FEATURE_SPEC_PDF_PAGE_LIMIT_EXCEEDED);
        }
    }
}
