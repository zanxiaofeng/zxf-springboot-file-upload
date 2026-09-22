package zxf.upload.domain.filescan;

/**
 * 管道过滤器：一个扫描阶段（Pipe-Filter 模式）。
 * 实现为 Spring Bean，阶段顺序即领域规则（"什么算 CLEAN"），显式装配于 {@link ScanPipelineConfig}。
 * 新增扫描阶段 = 新增一个实现 + 装配行加一项，管道本身零修改（OCP）。
 */
public interface ScanStage {

    /**
     * @return {@link ScanVerdict#Passed} 继续下一阶段；其余结论短路管道
     */
    ScanVerdict scan(ScanContext ctx);
}
