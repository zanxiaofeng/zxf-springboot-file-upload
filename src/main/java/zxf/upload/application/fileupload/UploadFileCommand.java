package zxf.upload.application.fileupload;

import org.springframework.web.multipart.MultipartFile;
import zxf.upload.domain.fileupload.UploadFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * 上传扫描命令（architecture §4.2：record、扁平不可变、携带写操作全部输入）。
 * {@link #toUploadFile()} 承担 Executor 时序中 check 之后的「转换」步骤
 * （Command → 领域值对象，内容流不进入领域模型）。
 */
public record UploadFileCommand(String originalFilename, long size, InputStream content) {

    /** 由 multipart 请求构造（协议转换的终点；IOException 由调用方按业务拒绝表达） */
    public static UploadFileCommand of(MultipartFile file) throws IOException {
        return new UploadFileCommand(file.getOriginalFilename(), file.getSize(), file.getInputStream());
    }

    /** Command → 领域值对象转换（未落盘快照） */
    public UploadFile toUploadFile() {
        return UploadFile.unstaged(originalFilename, size);
    }
}
