package zxf.upload.rest.filescan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import zxf.upload.application.ApplicationService;
import zxf.upload.domain.filescan.model.ScanStatus;
import zxf.upload.infrastructure.rest.GlobalExceptionHandler;
import zxf.upload.rest.fileupload.representation.UploadResponse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ScanResultController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("ScanResultController 查询端点（FileScan 域）")
class ScanResultControllerTest {

    /** 测试数据须满足 scanId 的 UUID 格式校验（确定性固定值） */
    private static final String VALID_SCAN_ID = "123e4567-e89b-12d3-a456-426614174000";

    @Autowired MockMvc mvc;
    @MockitoBean ApplicationService applicationService;

    @Test
    @DisplayName("轮询：返回扫描状态")
    void pollStatus_returnsResult() throws Exception {
        when(applicationService.fileScanStatus(VALID_SCAN_ID))
                .thenReturn(new UploadResponse(VALID_SCAN_ID, ScanStatus.CLEAN, "文件安全", "/data/file.txt"));

        mvc.perform(get("/api/files/async/scan/" + VALID_SCAN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLEAN"))
                .andExpect(jsonPath("$.filePath").value("/data/file.txt"));
    }

    @Test
    @DisplayName("轮询：扫描进行中")
    void pollStatus_scanning() throws Exception {
        when(applicationService.fileScanStatus(VALID_SCAN_ID))
                .thenReturn(UploadResponse.scanning(VALID_SCAN_ID));

        mvc.perform(get("/api/files/async/scan/" + VALID_SCAN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCANNING"));
    }

    @Test
    @DisplayName("scanId 格式非法 → 400 VALIDATION_ERROR（方法级校验）")
    void pollStatus_invalidScanId_returns400() throws Exception {
        mvc.perform(get("/api/files/async/scan/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verify(applicationService, never()).fileScanStatus(any());
    }
}
