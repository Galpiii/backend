package com.github.galpiii.galpi.domain.featurespec.validator;

import com.github.galpiii.galpi.domain.featurespec.validator.FeatureSpecFileValidator.ValidatedFeatureSpec;
import com.github.galpiii.galpi.global.error.ErrorCode;
import com.github.galpiii.galpi.global.error.exception.BadRequestException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FeatureSpecFileValidator — 기능명세서 PDF 검증")
class FeatureSpecFileValidatorTest {

    private static final String FILE_NAME = "기능명세서.pdf";
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;

    private final FeatureSpecFileValidator validator = new FeatureSpecFileValidator();

    private static byte[] pdfWithPages(int pageCount) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                document.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);

            return out.toByteArray();
        }
    }

    private static byte[] encryptedPdf(String userPassword) throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy("owner", userPassword, new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);

            return out.toByteArray();
        }
    }

    private static MultipartFile pdfFile(String fileName, byte[] content) {
        return new MockMultipartFile("file", fileName, MediaType.APPLICATION_PDF_VALUE, content);
    }

    private static long tempFileCount() {
        File[] files = new File(System.getProperty("java.io.tmpdir"))
                .listFiles((dir, name) -> name.startsWith("feature-spec-") && name.endsWith(".pdf"));

        return files == null ? 0 : files.length;
    }

    @Test
    @DisplayName("정상 PDF는 정리된 파일명과 검증에 쓴 임시 파일을 함께 돌려준다")
    void returnsFileNameAndTempFile() throws IOException {
        byte[] content = pdfWithPages(1);

        ValidatedFeatureSpec validated = validator.validate(pdfFile(FILE_NAME, content));

        assertThat(validated.fileName()).isEqualTo(FILE_NAME);
        assertThat(validated.tempFile()).exists();
        assertThat(validated.tempFile()).hasBinaryContent(content);

        validator.deleteTempFile(validated.tempFile());
    }

    @Test
    @DisplayName("경로가 섞인 파일명은 파일명만 남긴다")
    void stripsPathFromFileName() throws IOException {
        ValidatedFeatureSpec validated =
                validator.validate(pdfFile("../../etc/기능명세서.pdf", pdfWithPages(1)));

        assertThat(validated.fileName()).isEqualTo(FILE_NAME);

        validator.deleteTempFile(validated.tempFile());
    }

    @Test
    @DisplayName("100페이지는 통과한다")
    void acceptsMaxPageCount() throws IOException {
        ValidatedFeatureSpec validated = validator.validate(pdfFile(FILE_NAME, pdfWithPages(100)));

        assertThat(validated.tempFile()).exists();

        validator.deleteTempFile(validated.tempFile());
    }

    @Test
    @DisplayName("파일이 없으면 거부한다")
    void rejectsNullFile() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_FILE_EMPTY);
    }

    @Test
    @DisplayName("빈 파일은 거부한다")
    void rejectsEmptyFile() {
        assertThatThrownBy(() -> validator.validate(pdfFile(FILE_NAME, new byte[0])))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_FILE_EMPTY);
    }

    @Test
    @DisplayName("원본 파일명이 없으면 거부한다")
    void rejectsMissingFileName() throws IOException {
        assertThatThrownBy(() -> validator.validate(pdfFile("", pdfWithPages(1))))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_FILE_NAME_MISSING);
    }

    @Test
    @DisplayName("파일명이 255자를 넘으면 거부한다")
    void rejectsTooLongFileName() throws IOException {
        String fileName = "a".repeat(252) + ".pdf";

        assertThatThrownBy(() -> validator.validate(pdfFile(fileName, pdfWithPages(1))))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_FILE_NAME_TOO_LONG);
    }

    @Test
    @DisplayName("PDF 확장자가 아니면 거부한다")
    void rejectsNonPdfExtension() throws IOException {
        assertThatThrownBy(() -> validator.validate(pdfFile("기능명세서.png", pdfWithPages(1))))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_FILE_EXTENSION_INVALID);
    }

    @Test
    @DisplayName("20MB를 넘으면 거부한다")
    void rejectsOversizedFile() throws IOException {
        byte[] content = pdfWithPages(1);
        MultipartFile file =
                new MockMultipartFile("file", FILE_NAME, MediaType.APPLICATION_PDF_VALUE, content) {
                    @Override
                    public long getSize() {
                        return MAX_FILE_SIZE + 1;
                    }
                };

        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_FILE_SIZE_EXCEEDED);
    }

    @Test
    @DisplayName("Content-Type이 application/pdf가 아니면 거부한다")
    void rejectsWrongContentType() throws IOException {
        MultipartFile file = new MockMultipartFile(
                "file", FILE_NAME, MediaType.IMAGE_PNG_VALUE, pdfWithPages(1));

        assertThatThrownBy(() -> validator.validate(file))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_FILE_CONTENT_TYPE_INVALID);
    }

    @Test
    @DisplayName("확장자만 PDF인 파일은 열어 보고 거부한다")
    void rejectsFileThatIsNotReallyPdf() {
        byte[] content = "not a pdf at all".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> validator.validate(pdfFile(FILE_NAME, content)))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_PDF_INVALID);
    }

    @Test
    @DisplayName("비밀번호로 보호된 PDF는 거부한다")
    void rejectsPasswordProtectedPdf() throws IOException {
        assertThatThrownBy(() -> validator.validate(pdfFile(FILE_NAME, encryptedPdf("user"))))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_PDF_ENCRYPTED);
    }

    @Test
    @DisplayName("비밀번호 없이 열리더라도 암호화된 PDF는 거부한다")
    void rejectsEncryptedPdfThatOpensWithoutPassword() throws IOException {
        assertThatThrownBy(() -> validator.validate(pdfFile(FILE_NAME, encryptedPdf(""))))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_PDF_ENCRYPTED);
    }

    @Test
    @DisplayName("101페이지는 거부한다")
    void rejectsTooManyPages() throws IOException {
        assertThatThrownBy(() -> validator.validate(pdfFile(FILE_NAME, pdfWithPages(101))))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_PDF_PAGE_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("페이지가 없으면 거부한다")
    void rejectsPdfWithoutPages() throws IOException {
        assertThatThrownBy(() -> validator.validate(pdfFile(FILE_NAME, pdfWithPages(0))))
                .isInstanceOf(BadRequestException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FEATURE_SPEC_PDF_PAGE_MISSING);
    }

    @Test
    @DisplayName("PDF 검증에 실패하면 임시 파일을 남기지 않는다")
    void deletesTempFileWhenPdfValidationFails() throws IOException {
        long before = tempFileCount();

        assertThatThrownBy(() -> validator.validate(pdfFile(FILE_NAME, pdfWithPages(101))))
                .isInstanceOf(BadRequestException.class);

        assertThat(tempFileCount()).isEqualTo(before);
    }
}
