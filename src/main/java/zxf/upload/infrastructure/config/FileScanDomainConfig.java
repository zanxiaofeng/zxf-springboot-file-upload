package zxf.upload.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import zxf.upload.domain.filescan.documentthreat.DocumentThreatScanner;

/**
 * FileScan 域领域组件装配：domain 不依赖 Spring 注解，
 * 由本层以 @Bean 注册——同 FileUploadDomainConfig 装配 UploadPolicy 的先例。
 */
@Configuration
public class FileScanDomainConfig {

    @Bean
    public DocumentThreatScanner documentThreatScanner() {
        return new DocumentThreatScanner();
    }
}
