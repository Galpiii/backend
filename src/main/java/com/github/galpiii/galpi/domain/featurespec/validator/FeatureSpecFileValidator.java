package com.github.galpiii.galpi.domain.featurespec.validator;

import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Component
public class FeatureSpecFileValidator {

    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;
    private static final int MAX_FILE_NAME_LENGTH = 255;
    private static final int MIN_PAGE_COUNT = 1;
    private static final int MAX_PAGE_COUNT = 100;
    private static final String PDF_EXTENSION = "pdf";

    // 업로드된 기능명세서 PDF의 전체 검증
    public void validate(MultipartFile file) {
        validateBasicFile(file);
        validatePdfStructure(file);
    }

    // 파일 존재 여부, 파일명, 확장자, 크기, Content-Type 검증
    private void validateBasicFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException(ErrorCode.FEATURE_SPEC_FILE_EMPTY);
        }

        String originalFilename = file.getOriginalFilename();

        if (!StringUtils.hasText(originalFilename)) {
            throw new BadRequestException(ErrorCode.FEATURE_SPEC_FILE_NAME_MISSING);
        }

        if (originalFilename.length() > MAX_FILE_NAME_LENGTH) {
            throw new BadRequestException(ErrorCode.FEATURE_SPEC_FILE_NAME_TOO_LONG);
        }

        String extension = StringUtils.getFilenameExtension(originalFilename);

        if (!PDF_EXTENSION.equalsIgnoreCase(extension)) {
            throw new BadRequestException(ErrorCode.FEATURE_SPEC_FILE_EXTENSION_INVALID);
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BadRequestException(ErrorCode.FEATURE_SPEC_FILE_SIZE_EXCEEDED);
        }

        if (!MediaType.APPLICATION_PDF_VALUE.equalsIgnoreCase(file.getContentType())) {
            throw new BadRequestException(ErrorCode.FEATURE_SPEC_FILE_CONTENT_TYPE_INVALID);
        }
    }

    // PDF 파일을 열어 구조와 암호화 여부 검증
    private void validatePdfStructure(MultipartFile file) {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            if (document.isEncrypted()) {
                throw new BadRequestException(ErrorCode.FEATURE_SPEC_PDF_ENCRYPTED);
            }

            validatePageCount(document.getNumberOfPages());
        } catch (InvalidPasswordException e) {
            throw new BadRequestException(ErrorCode.FEATURE_SPEC_PDF_ENCRYPTED);
        } catch (IOException e) {
            throw new BadRequestException(ErrorCode.FEATURE_SPEC_PDF_INVALID);
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
