package zxf.upload.rest.fileupload;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import zxf.upload.application.ApplicationService;
import zxf.upload.infrastructure.rest.GlobalExceptionHandler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FileAsyncUploadController.class)
@Import(GlobalExceptionHandler.class)
@DisplayName("FileAsyncUploadController 受理端点（FileUpload 域）")
class FileAsyncUploadControllerTest {

    private static final String UPLOAD_URL = "/api/files/async/upload";

    @Autowired MockMvc mvc;
    @MockitoBean ApplicationService applicationService;

    @Test
    @DisplayName("异步上传 → 202 + scanId")
    void asyncUpload_returns202() throws Exception {
        var file = new MockMultipartFile("file", "big.txt", "text/plain", "hello".getBytes());
        when(applicationService.fileAsyncUpload(any())).thenReturn("scan-123");

        mvc.perform(multipart(UPLOAD_URL).file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SCANNING"))
                .andExpect(jsonPath("$.scanId").value("scan-123"));
    }

    @Test
    @DisplayName("缺少 file part → 400 FILE_REJECTED（非 500 兜底）")
    void missingFilePart_returns400() throws Exception {
        mvc.perform(multipart(UPLOAD_URL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_REJECTED"));
    }
}
