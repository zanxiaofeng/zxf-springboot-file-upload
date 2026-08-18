package zxf.upload.control;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import zxf.upload.config.VirusScanProperties;
import zxf.upload.model.ScanResult;
import zxf.upload.model.ScanStatus;
import zxf.upload.model.UploadResponse;
import zxf.upload.model.exception.FileRejectedException;
import zxf.upload.model.exception.ScanFailedException;
import zxf.upload.model.exception.VirusDetectedException;
import zxf.upload.service.AsyncScanProcessor;
import zxf.upload.service.StagingService;
import zxf.upload.service.VirusScanService;
import zxf.upload.support.rest.GlobalExceptionHandler;

import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FileUploadController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("FileUploadController REST API")
class FileUploadControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean StagingService stagingService;
    @MockitoBean VirusScanService scanService;
    @MockitoBean AsyncScanProcessor asyncProcessor;
    @MockitoBean VirusScanProperties properties;

    @Test
    @DisplayName("同步 CLEAN → 200")
    void syncUpload_clean_returns200() throws Exception {
        var file = new MockMultipartFile("file", "clean.txt", "text/plain", "hello".getBytes());
        var result = ScanResult.clean(Path.of("/tmp/staged.txt"), "text/plain");

        when(stagingService.stage(any())).thenReturn(Path.of("/tmp/staged.txt"));
        when(scanService.scanFile(any(), anyString())).thenReturn(result);

        mvc.perform(multipart("/api/files/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLEAN"))
                .andExpect(jsonPath("$.message").value("文件安全"));
    }

    @Test
    @DisplayName("INFECTED → 422 VIRUS_DETECTED")
    void syncUpload_infected_returns422() throws Exception {
        var file = new MockMultipartFile("file", "eicar.txt", "text/plain", "x5o".getBytes());
        var result = ScanResult.infected(Path.of("/tmp/staged.txt"), "ClamAV: Eicar-Test-Signature");

        when(stagingService.stage(any())).thenReturn(Path.of("/tmp/staged.txt"));
        when(scanService.scanFile(any(), anyString())).thenThrow(new VirusDetectedException(result));

        mvc.perform(multipart("/api/files/upload").file(file))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VIRUS_DETECTED"));
    }

    @Test
    @DisplayName("REJECTED → 400 FILE_REJECTED")
    void syncUpload_rejected_returns400() throws Exception {
        var file = new MockMultipartFile("file", "fake.pdf", "application/pdf", "mz".getBytes());

        when(stagingService.stage(any())).thenReturn(Path.of("/tmp/staged.txt"));
        when(scanService.scanFile(any(), anyString()))
                .thenThrow(new FileRejectedException("文件类型与扩展名不符"));

        mvc.perform(multipart("/api/files/upload").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_REJECTED"));
    }

    @Test
    @DisplayName("扫描引擎故障 → 502 SCAN_ENGINE_ERROR")
    void scanEngineError_returns502() throws Exception {
        var file = new MockMultipartFile("file", "clean.txt", "text/plain", "hello".getBytes());

        when(stagingService.stage(any())).thenReturn(Path.of("/tmp/staged.txt"));
        when(scanService.scanFile(any(), anyString()))
                .thenThrow(new ScanFailedException("ClamAV connection refused at localhost:3310"));

        mvc.perform(multipart("/api/files/upload").file(file))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("SCAN_ENGINE_ERROR"))
                .andExpect(jsonPath("$.message").value("扫描引擎暂时不可用，请稍后重试"));
    }

    @Test
    @DisplayName("异步上传 → 202 + scanId")
    void asyncUpload_returns202() throws Exception {
        var file = new MockMultipartFile("file", "big.txt", "text/plain", "hello".getBytes());

        when(stagingService.stage(any())).thenReturn(Path.of("/tmp/staged.txt"));
        doNothing().when(asyncProcessor).processScan(anyString(), any(), anyString());

        mvc.perform(multipart("/api/files/upload").file(file).header("X-Scan-Async", "true"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SCANNING"))
                .andExpect(jsonPath("$.scanId").isNotEmpty());
    }

    @Test
    @DisplayName("轮询：返回扫描状态")
    void pollStatus_returnsResult() throws Exception {
        when(asyncProcessor.getResult("scan-123"))
                .thenReturn(new UploadResponse("scan-123", ScanStatus.CLEAN, "文件安全", "/data/file.txt"));

        mvc.perform(get("/api/files/scan/scan-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLEAN"))
                .andExpect(jsonPath("$.filePath").value("/data/file.txt"));
    }

    @Test
    @DisplayName("轮询：扫描进行中")
    void pollStatus_scanning() throws Exception {
        when(asyncProcessor.getResult("scan-456"))
                .thenReturn(UploadResponse.scanning("scan-456"));

        mvc.perform(get("/api/files/scan/scan-456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCANNING"));
    }

    @Test
    @DisplayName("X-Scan-Async 非法值 → 400 FILE_REJECTED")
    void invalidAsyncHeader_returns400() throws Exception {
        var file = new MockMultipartFile("file", "clean.txt", "text/plain", "hello".getBytes());

        mvc.perform(multipart("/api/files/upload").file(file).header("X-Scan-Async", "1x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_REJECTED"));
    }
}
