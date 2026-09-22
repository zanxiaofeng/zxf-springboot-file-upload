package zxf.upload.domain.filescan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import zxf.upload.domain.filescan.model.ScanStatus;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FileDisposition 扫描状态 → 文件处置映射")
class FileDispositionTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "CLEAN, STORE",
            "INFECTED, QUARANTINE",
            "REJECTED, DISCARD",
            "ERROR, DISCARD",
            "SCANNING, DISCARD"
    })
    @DisplayName("处置映射查表")
    void dispositionMapping(ScanStatus status, FileDisposition expected) {
        assertThat(FileDisposition.of(status)).isEqualTo(expected);
    }
}
