package zxf.upload.rest.fileupload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import zxf.upload.application.ApplicationService;
import zxf.upload.domain.filescan.model.ScanResult;
import zxf.upload.infrastructure.config.FileScanProperties;
import zxf.upload.infrastructure.domain.BusinessException;
import zxf.upload.infrastructure.rest.GlobalExceptionHandler;

import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FileUploadController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("FileUploadController 受理端点（FileUpload 域）")
class FileUploadControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ApplicationService applicationService;
    @MockitoBean FileScanProperties properties;

    @Nested
    @DisplayName("同步上传 POST /api/files/sync/upload")
    class SyncUpload {

        private static final String SYNC_URL = "/api/files/sync/upload";

        @Test
        @DisplayName("同步 CLEAN → 200")
        void syncUpload_clean_returns200() throws Exception {
            var file = new MockMultipartFile("file", "clean.txt", "text/plain", "hello".getBytes());
            when(applicationService.fileSyncUpload(any()))
                    .thenReturn(ScanResult.clean(Path.of("/tmp/staged.txt"), "text/plain"));

            mvc.perform(multipart(SYNC_URL).file(file))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CLEAN"))
                    .andExpect(jsonPath("$.message").value("文件安全"));
        }

        @Test
        @DisplayName("INFECTED → 422 VIRUS_DETECTED")
        void syncUpload_infected_returns422() throws Exception {
            var file = new MockMultipartFile("file", "eicar.txt", "text/plain", "x5o".getBytes());
            when(applicationService.fileSyncUpload(any()))
                    .thenThrow(BusinessException.virusDetected("ClamAV: Eicar-Test-Signature"));

            mvc.perform(multipart(SYNC_URL).file(file))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("VIRUS_DETECTED"));
        }

        @Test
        @DisplayName("REJECTED → 400 FILE_REJECTED")
        void syncUpload_rejected_returns400() throws Exception {
            var file = new MockMultipartFile("file", "fake.pdf", "application/pdf", "mz".getBytes());
            when(applicationService.fileSyncUpload(any()))
                    .thenThrow(BusinessException.rejected("文件类型与扩展名不符"));

            mvc.perform(multipart(SYNC_URL).file(file))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("FILE_REJECTED"));
        }

        @Test
        @DisplayName("扫描引擎故障 → 502 SCAN_ENGINE_ERROR（内部细节不外泄）")
        void scanEngineError_returns502() throws Exception {
            var file = new MockMultipartFile("file", "clean.txt", "text/plain", "hello".getBytes());
            when(applicationService.fileSyncUpload(any()))
                    .thenThrow(BusinessException.scanFailed("ClamAV connection refused at localhost:3310"));

            mvc.perform(multipart(SYNC_URL).file(file))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("SCAN_ENGINE_ERROR"))
                    .andExpect(jsonPath("$.message").value("扫描引擎暂时不可用，请稍后重试"));
        }

        @Test
        @DisplayName("缺少 file part → 400 FILE_REJECTED（非 500 兜底）")
        void missingFilePart_returns400() throws Exception {
            mvc.perform(multipart(SYNC_URL))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("FILE_REJECTED"));
        }
    }

    @Nested
    @DisplayName("异步上传 POST /api/files/async/upload")
    class AsyncUpload {

        private static final String ASYNC_URL = "/api/files/async/upload";

        @Test
        @DisplayName("异步上传 → 202 + scanId")
        void asyncUpload_returns202() throws Exception {
            var file = new MockMultipartFile("file", "big.txt", "text/plain", "hello".getBytes());
            when(applicationService.fileAsyncUpload(any())).thenReturn("scan-123");

            mvc.perform(multipart(ASYNC_URL).file(file))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("SCANNING"))
                    .andExpect(jsonPath("$.scanId").value("scan-123"));
        }

        @Test
        @DisplayName("缺少 file part → 400 FILE_REJECTED（非 500 兜底）")
        void missingFilePart_returns400() throws Exception {
            mvc.perform(multipart(ASYNC_URL))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("FILE_REJECTED"));
        }
    }
}