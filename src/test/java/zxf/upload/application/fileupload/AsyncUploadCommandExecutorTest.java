package zxf.upload.application.fileupload;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import zxf.upload.application.fileupload.UploadFileCommand;
import zxf.upload.application.fileupload.UploadFileCommandChecker;
import zxf.upload.application.filescan.AsyncScanProcessor;
import zxf.upload.domain.fileupload.UploadFile;
import zxf.upload.infrastructure.domain.BusinessException;
import zxf.upload.infrastructure.domain.ErrorCode;
import zxf.upload.infrastructure.fileupload.StagingService;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AsyncUploadCommandExecutor：预检 → 落盘 → 受理分发")
class AsyncUploadCommandExecutorTest {

    @Mock UploadFileCommandChecker checker;
    @Mock StagingService stagingService;
    @Mock AsyncScanProcessor asyncScanProcessor;
    @InjectMocks AsyncUploadCommandExecutor executor;

    private UploadFileCommand command() {
        return new UploadFileCommand("a.txt", 5, new ByteArrayInputStream("hello".getBytes()));
    }

    @BeforeEach
    void allowChecks() {
        lenient().doAnswer(inv -> null).when(checker).check(any());
    }

    @Test
    @DisplayName("受理 → 生成 scanId 并分发 staged UploadFile 到后台扫描")
    void accept_dispatchesStagedFile() {
        var stagedPath = Path.of("/tmp/staged.txt");
        when(stagingService.stage(any(), any())).thenReturn(stagedPath);

        String scanId = executor.execute(command());

        assertThat(scanId).isNotBlank();
        ArgumentCaptor<String> scanIdCaptor = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<UploadFile> stagedCaptor = ArgumentCaptor.forClass(UploadFile.class);
        verify(asyncScanProcessor).processScan(scanIdCaptor.capture(), stagedCaptor.capture());
        assertThat(scanIdCaptor.getValue()).isEqualTo(scanId);
        assertThat(stagedCaptor.getValue().cleanedName()).isEqualTo("a.txt");
        assertThat(stagedCaptor.getValue().stagingPath()).isEqualTo(stagedPath);
    }

    @Test
    @DisplayName("预检拒绝 → 不受理、不落盘")
    void precheckRejected_notAccepted() {
        doThrow(BusinessException.rejected("上传文件不能为空")).when(checker).check(any());

        assertThatThrownBy(() -> executor.execute(command()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FILE_REJECTED);
        verify(stagingService, never()).stage(any(), any());
        verify(asyncScanProcessor, never()).processScan(any(), any());
    }
}
