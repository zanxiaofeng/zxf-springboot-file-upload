package zxf.upload.domain.filescan;

import lombok.Getter;
import lombok.Setter;
import zxf.upload.domain.fileupload.UploadFile;

/**
 * 管道上下文：承载阶段间传递的数据（detectedMime 由阶段 1 产出、阶段 4 消费）。
 * 生命周期仅限单次管道执行，非线程共享对象。
 */
@Getter
public class ScanContext {

    private final UploadFile staged;

    /** Tika 探测的 MIME（阶段 1 产出，向后传递避免重复探测） */
    @Setter
    private String detectedMime;

    public ScanContext(UploadFile staged) {
        this.staged = staged;
    }
}
