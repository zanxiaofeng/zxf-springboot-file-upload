package zxf.upload.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import zxf.upload.domain.fileupload.UploadPolicy;

import java.util.Set;

/**
 * 领域策略装配：从基础设施配置提取纯值构造 domain 规则对象——
 * domain 不依赖 infrastructure，配置绑定细节收敛在本层。
 */
@Configuration
public class FileUploadDomainConfig {

    @Bean
    public UploadPolicy uploadPolicy(FileScanProperties properties) {
        return new UploadPolicy(
                properties.getMaxFileSize(),
                Set.copyOf(properties.getAllowedExtensions()),
                properties.getSyncMaxFileSize());
    }
}
