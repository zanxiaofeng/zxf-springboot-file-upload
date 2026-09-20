package zxf.upload.control;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import zxf.upload.model.ScanStatus;
import zxf.upload.model.UploadResponse;
import zxf.upload.service.AsyncScanProcessor;
import zxf.upload.service.StagingService;
import zxf.upload.support.rest.GlobalExceptionHandler;

import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FileAsyncUploadController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("FileAsyncUploadController REST API")
class FileAsyncUploadControllerTest {

    private static final String UPLOAD_URL = "/api/files/async/upload";

    @Autowired MockMvc mvc;
    @MockitoBean StagingService stagingService;
    @MockitoBean AsyncScanProcessor asyncProcessor;

    @Test
    @DisplayName("异步上传 → 202 + scanId")
    void asyncUpload_returns202() throws Exception {
        var file = new MockMultipartFile("file", "big.txt", "text/plain", "hello".getBytes());

        when(stagingService.stage(any())).thenReturn(Path.of("/tmp/staged.txt"));
        doNothing().when(asyncProcessor).processScan(anyString(), any(), anyString());

        mvc.perform(multipart(UPLOAD_URL).file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SCANNING"))
                .andExpect(jsonPath("$.scanId").isNotEmpty());
    }

    @Test
    @DisplayName("轮询：返回扫描状态")
    void pollStatus_returnsResult() throws Exception {
        when(asyncProcessor.getResult("scan-123"))
                .thenReturn(new UploadResponse("scan-123", ScanStatus.CLEAN, "文件安全", "/data/file.txt"));

        mvc.perform(get("/api/files/async/scan/scan-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLEAN"))
                .andExpect(jsonPath("$.filePath").value("/data/file.txt"));
    }

    @Test
    @DisplayName("轮询：扫描进行中")
    void pollStatus_scanning() throws Exception {
        when(asyncProcessor.getResult("scan-456"))
                .thenReturn(UploadResponse.scanning("scan-456"));

        mvc.perform(get("/api/files/async/scan/scan-456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCANNING"));
    }

    @Test
    @DisplayName("缺少 file part → 400 FILE_REJECTED（非 500 兜底）")
    void missingFilePart_returns400() throws Exception {
        mvc.perform(multipart(UPLOAD_URL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_REJECTED"));
    }
}
