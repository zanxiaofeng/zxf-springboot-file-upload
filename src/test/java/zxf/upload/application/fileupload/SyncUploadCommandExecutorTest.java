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
import zxf.upload.application.filescan.FileScanService;
import zxf.upload.domain.filescan.model.ScanResult;
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
@DisplayName("SyncUploadCommandExecutor：check → 转换 → 落盘 → 扫描 → 翻译")
class SyncUploadCommandExecutorTest {

    @Mock UploadFileCommandChecker checker;
    @Mock StagingService stagingService;
    @Mock FileScanService fileScanService;
    @InjectMocks SyncUploadCommandExecutor executor;

    private UploadFileCommand command() {
        return new UploadFileCommand("a.txt", 5, new ByteArrayInputStream("hello".getBytes()));
    }

    @BeforeEach
    void allowChecks() {
        // 预检规则本身由 UploadPolicyTest 覆盖；本类只验证时序与翻译
        lenient().doAnswer(inv -> null).when(checker).checkSyncLimit(any());
        lenient().doAnswer(inv -> null).when(checker).check(any());
    }

    @Test
    @DisplayName("超同步阈值 → FILE_REJECTED，落盘前拦截")
    void syncOverflow_rejectedBeforeStaging() {
        org.mockito.Mockito.doThrow(BusinessException.rejected("文件超过 20MB，同步扫描耗时过长，请使用异步上传接口 POST /api/files/async/upload"))
                .when(checker).checkSyncLimit(any());

        assertThatThrownBy(() -> executor.execute(command()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FILE_REJECTED);
        verify(stagingService, never()).stage(any(), any());
    }

    @Test
    @DisplayName("通用预检拒绝 → 异常传播，不落盘")
    void precheckRejected_propagates() {
        org.mockito.Mockito.doThrow(BusinessException.rejected("不支持的文件扩展名: exe"))
                .when(checker).check(any());

        assertThatThrownBy(() -> executor.execute(command()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的文件扩展名");
        verify(stagingService, never()).stage(any(), any());
    }

    @Test
    @DisplayName("CLEAN → 落盘后扫描，Command 转换为 staged UploadFile")
    void clean_scanExecutesWithStagedFile() {
        var stagedPath = Path.of("/tmp/staged.txt");
        var result = ScanResult.clean(stagedPath, "text/plain");
        when(stagingService.stage(any(), any())).thenReturn(stagedPath);
        when(fileScanService.scanFile(any())).thenReturn(result);

        assertThat(executor.execute(command())).isEqualTo(result);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UploadFile> captor = ArgumentCaptor.forClass(UploadFile.class);
        verify(fileScanService).scanFile(captor.capture());
        assertThat(captor.getValue().cleanedName()).isEqualTo("a.txt");
        assertThat(captor.getValue().stagingPath()).isEqualTo(stagedPath);
    }

    @Test
    @DisplayName("REJECTED → BusinessException(FILE_REJECTED)")
    void rejected_translatedToFileRejected() {
        when(stagingService.stage(any(), any())).thenReturn(Path.of("/tmp/staged.txt"));
        when(fileScanService.scanFile(any()))
                .thenReturn(ScanResult.rejected(Path.of("/tmp/staged.txt"), "文件类型与扩展名不符"));

        assertThatThrownBy(() -> executor.execute(command()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FILE_REJECTED);
    }

    @Test
    @DisplayName("INFECTED → BusinessException(VIRUS_DETECTED)，消息为威胁描述")
    void infected_translatedToVirusDetected() {
        when(stagingService.stage(any(), any())).thenReturn(Path.of("/tmp/staged.txt"));
        when(fileScanService.scanFile(any()))
                .thenReturn(ScanResult.infected(Path.of("/tmp/staged.txt"), "ClamAV: Eicar-Test-Signature"));

        var ex = businessExceptionFrom();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VIRUS_DETECTED);
        assertThat(ex.getClientMessage()).isEqualTo("ClamAV: Eicar-Test-Signature");
    }

    @Test
    @DisplayName("ERROR → BusinessException(SCAN_ENGINE_ERROR)，技术细节不进对外文案")
    void error_translatedToScanEngineError() {
        when(stagingService.stage(any(), any())).thenReturn(Path.of("/tmp/staged.txt"));
        when(fileScanService.scanFile(any()))
                .thenReturn(ScanResult.error(Path.of("/tmp/staged.txt"), "connection refused at localhost:3310"));

        var ex = businessExceptionFrom();
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SCAN_ENGINE_ERROR);
        assertThat(ex.getClientMessage()).isEqualTo(ErrorCode.SCAN_ENGINE_ERROR.getDefaultMessage());
        assertThat(ex.getClientMessage()).doesNotContain("localhost:3310");
        assertThat(ex.getMessage()).contains("localhost:3310");
    }

    private BusinessException businessExceptionFrom() {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> executor.execute(command()));
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return (BusinessException) thrown;
    }
}
