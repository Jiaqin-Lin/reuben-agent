package com.reubenagent.document.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文档模块 MinIO 配置 —— 创建 {@link MinioClient} 并确保存储桶存在。
 *
 * @author reuben
 * @since 2026-06-14
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(DocumentProperties.class)
public class DocumentMinioConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MinioClient documentMinioClient(DocumentProperties properties) {
        DocumentProperties.Minio minio = properties.getMinio();
        return MinioClient.builder()
                .endpoint(minio.getEndpoint())
                .credentials(minio.getAccessKey(), minio.getSecretKey())
                .build();
    }

    /** 启动时自动检查并创建存储桶 */
    @Bean
    public CommandLineRunner documentMinioBucketInitializer(MinioClient documentMinioClient,
                                                            DocumentProperties properties) {
        return args -> {
            String bucketName = properties.getMinio().getBucketName();
            try {
                boolean exists = documentMinioClient.bucketExists(
                        BucketExistsArgs.builder().bucket(bucketName).build());
                if (!exists) {
                    documentMinioClient.makeBucket(
                            MakeBucketArgs.builder().bucket(bucketName).build());
                    log.info("MinIO 桶已自动创建: {}", bucketName);
                } else {
                    log.info("MinIO 桶已存在: {}", bucketName);
                }
            } catch (Exception e) {
                log.error("MinIO 桶初始化失败: {}", bucketName, e);
                throw new RuntimeException("MinIO 桶初始化失败: " + e.getMessage(), e);
            }
        };
    }
}
