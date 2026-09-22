package zxf.upload.domain.fileupload;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UploadPolicy 上传预检领域规则")
class UploadPolicyTest {

    private static final long MB = 1024 * 1024;
    private final UploadPolicy policy = new UploadPolicy(100 * MB, Set.of("txt", "pdf"), 20 * MB);

    private UploadFile file(String name, long size) {
        return UploadFile.unstaged(name, size);
    }

    @Test
    @DisplayName("空文件（size=0）→ 拒绝")
    void emptyFile_rejected() {
        assertThat(policy.rejectionReason(file("a.txt", 0))).contains("不能为空");
    }

    @Test
    @DisplayName("超过大小上限 → 拒绝并提示上限")
    void oversized_rejected() {
        assertThat(policy.rejectionReason(file("a.txt", 101 * MB))).contains("文件过大").contains("100MB");
    }

    @Test
    @DisplayName("扩展名不在白名单 → 拒绝")
    void illegalExtension_rejected() {
        assertThat(policy.rejectionReason(file("malware.exe", 10))).contains("不支持的文件扩展名: exe");
    }

    @Test
    @DisplayName("无扩展名文件 → 放行（内容由类型校验阶段判定）")
    void noExtension_allowed() {
        assertThat(policy.rejectionReason(file("README", 10))).isNull();
    }

    @Test
    @DisplayName("白名单内扩展名 → 放行")
    void allowedExtension_passes() {
        assertThat(policy.rejectionReason(file("doc.pdf", 10))).isNull();
    }

    @Test
    @DisplayName("超过同步阈值 → 路由提示含异步端点")
    void syncOverflow() {
        assertThat(policy.syncOverflowReason(file("big.pdf", 21 * MB)))
                .contains("/api/files/async/upload");
    }

    @Test
    @DisplayName("阈值内 → 可同步（null）")
    void withinSyncLimit() {
        assertThat(policy.syncOverflowReason(file("ok.pdf", 20 * MB))).isNull();
    }
}
