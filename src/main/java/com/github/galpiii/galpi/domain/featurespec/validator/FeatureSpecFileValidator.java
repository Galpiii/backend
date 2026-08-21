package com.github.galpiii.galpi.domain.featurespec.validator;

import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import com.github.galpiii.galpi.global.error.exception.GlobalException;
import lombok.RequiredArgsConstructor;
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
import java.nio.file.Files;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeatureSpecFileValidator {

    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final int MIN_PAGE_COUNT = 1;
    private static final int MAX_PAGE_COUNT = 100;
    private static final String PDF_EXTENSION = "pdf";

    private final FeatureSpecTempFileStore tempFileStore;

    public ValidatedFeatureSpec validate(MultipartFile file) {
        String fileName = validateBasicFile(file);
        File tempFile = tempFileStore.create(file);

        try {
            validatePdfStructure(tempFile);
        } catch (RuntimeException | Error e) {
            tempFileStore.delete(tempFile);
            throw e;
        }

        return new ValidatedFeatureSpec(fileName, tempFile);
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
    private void validatePdfStructure(File tempFile) {
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

    public record ValidatedFeatureSpec(String fileName, File tempFile) {
    }
}
