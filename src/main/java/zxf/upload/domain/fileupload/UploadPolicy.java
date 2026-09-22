package zxf.upload.domain.fileupload;

import java.util.Set;

/**
 * 上传预检策略（领域规则）：空文件、大小上限、扩展名白名单、同步/异步路由阈值。
 * 规则值由基础设施装配（infrastructure/config/FileUploadDomainConfig），本类只承载判定逻辑，
 * 拒绝语义以文案返回（null = 通过），异常表达由 application 层完成。
 */
public class UploadPolicy {
    private final long maxFileSize;
    private final Set<String> allowedExtensions;
    private final long syncMaxFileSize;

    public UploadPolicy(long maxFileSize, Set<String> allowedExtensions, long syncMaxFileSize) {
        this.maxFileSize = maxFileSize;
        this.allowedExtensions = Set.copyOf(allowedExtensions);
        this.syncMaxFileSize = syncMaxFileSize;
    }

    /** 预检拒绝原因；null = 通过 */
    public String rejectionReason(UploadFile file) {
        if (file.size() <= 0) {
            return "上传文件不能为空";
        }
        if (file.size() > maxFileSize) {
            return "文件过大，最大允许 " + maxFileSize / 1024 / 1024 + "MB";
        }
        String ext = file.extension();
        if (!ext.isEmpty() && !allowedExtensions.contains(ext)) {
            return "不支持的文件扩展名: " + ext;
        }
        return null;
    }

    /** 同步/异步路由：非 null = 超出同步阈值，应改走异步端点 */
    public String syncOverflowReason(UploadFile file) {
        if (syncMaxFileSize > 0 && file.size() > syncMaxFileSize) {
            return "文件超过 " + syncMaxFileSize / 1024 / 1024
                    + "MB，同步扫描耗时过长，请使用异步上传接口 POST /api/files/async/upload";
        }
        return null;
    }
}
