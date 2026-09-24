package zxf.upload.domain.fileupload;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 上传文件值对象（FileUpload 域核心概念）：待扫描文件的原始名、大小与暂存位置。
 * {@link #unstaged} 创建受理快照，落盘后经 {@link #withStagingPath} 派生新实例（不可变）。
 * 零框架依赖（JDK 原生实现），可脱离 Spring 纯单测。
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

    /** 清洗后的展示名（basename：剥离客户端可伪造的目录部分防路径混淆 + null 兜底），日志与下游命名的唯一入口 */
    public String cleanedName() {
        if (originalFilename == null) {
            return "unknown";
        }
        String path = originalFilename.replace('\\', '/');
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    /** 小写扩展名；无扩展名（含目录点后缀、以点结尾）返回空串 */
    public String extension() {
        String name = originalFilename != null ? originalFilename : "";
        int dotIndex = name.lastIndexOf('.');
        int sepIndex = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (dotIndex <= sepIndex || dotIndex == name.length() - 1) {
            return "";
        }
        return name.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }
}
