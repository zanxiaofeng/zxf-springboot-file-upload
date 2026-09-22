package zxf.upload.domain.fileupload;

import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 上传文件值对象（FileUpload 域核心概念）：待扫描文件的原始名、大小与暂存位置。
 * {@link #unstaged} 创建受理快照，落盘后经 {@link #withStagingPath} 派生新实例（不可变）。
 */
public record UploadFile(String originalFilename, long size, Path stagingPath) {

    /** 受理快照（未落盘） */
    public static UploadFile unstaged(String originalFilename, long size) {
        return new UploadFile(originalFilename, size, null);
    }

    /** 落盘后派生：携带暂存路径的新实例 */
    public UploadFile withStagingPath(Path stagingPath) {
        return new UploadFile(originalFilename, size, stagingPath);
    }

    /** 清洗后的展示名（防路径混淆 + null 兜底），日志与下游命名的唯一入口 */
    public String cleanedName() {
        return StringUtils.cleanPath(originalFilename != null ? originalFilename : "unknown");
    }

    /** 小写扩展名；无扩展名返回空串 */
    public String extension() {
        String ext = StringUtils.getFilenameExtension(originalFilename);
        return ext != null ? ext.toLowerCase(Locale.ROOT) : "";
    }
}
