package zxf.upload.support.io;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 文件操作工具方法。集中处理暂存文件清理、扩展名提取等跨服务重复逻辑。
 */
@Slf4j
@UtilityClass
public class FileUtils {

    /**
     * 静默删除文件或目录，删除失败仅记录，不抛异常。
     *
     * @param path 待删除路径，允许为 null 或不存在
     */
    public void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("清理文件失败: {}", path, e);
        }
    }

    /**
     * 提取文件名扩展名并转为小写。
     *
     * @param filename 原始文件名，允许为 null
     * @return 小写扩展名；无扩展名或文件名为空时返回空字符串
     */
    public String extension(String filename) {
        String ext = StringUtils.getFilenameExtension(filename);
        return ext != null ? ext.toLowerCase(Locale.ROOT) : "";
    }

    /**
     * 剥离 ISO 控制字符（防日志注入/log forging）：客户端可控的文件名
     * 含 \r\n 时可在日志文件中伪造新行，入日志前必须净化。
     */
    public static String sanitizeForLog(String value) {
        return value == null ? "" : value.replaceAll("\\p{Cntrl}", "_");
    }
}
