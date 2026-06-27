package com.reubenagent.document.service.impl;

import com.reubenagent.common.enums.DocumentManageCode;
import com.reubenagent.common.exception.DocumentException;
import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.model.StoredObjectInfo;
import com.reubenagent.document.service.IDocumentStorageService;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * MinIO 文档存储服务实现。
 *
 * @author reuben
 * @since 2026-06-14
 */
@Slf4j
@AllArgsConstructor
@Service
public class DocumentStorageServiceImpl implements IDocumentStorageService {

    private final MinioClient minioClient;
    private final DocumentProperties properties;

    @Override
    public StoredObjectInfo uploadOriginalFile(Long documentId, String originalFileName, byte[] bytes, String contentType) {
        DocumentProperties.Minio minio = properties.getMinio();

        String objectName = minio.getObjectPrefix()
                + "/" + documentId
                + "/" + System.currentTimeMillis()
                + "-" + originalFileName;

        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minio.getBucketName())
                    .object(objectName)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(StringUtils.isNotBlank(contentType) ? contentType : MediaType.APPLICATION_OCTET_STREAM_VALUE)
                    .build());
        } catch (Exception e) {
            log.error("MinIO 上传原文件失败 documentId={} objectName={}", documentId, objectName, e);
            throw new DocumentException(DocumentManageCode.MINIO_UPLOAD_FAIL, e.getMessage(), e);
        }

        return new StoredObjectInfo(minio.getBucketName(), objectName,
                buildObjectUrl(minio, objectName));
    }

    @Override
    public String uploadParsedText(Long documentId, String parsedText) {
        DocumentProperties.Minio minio = properties.getMinio();

        String objectName = minio.getObjectPrefix()
                + "/" + documentId
                + ".txt";

        byte[] parsedTextBytes = parsedText.getBytes(StandardCharsets.UTF_8);

        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minio.getBucketName())
                    .object(objectName)
                    .stream(new ByteArrayInputStream(parsedTextBytes), parsedTextBytes.length, -1)
                    .contentType(new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8).toString())
                    .build());
        } catch (Exception e) {
            log.error("MinIO 上传解析文本失败 documentId={} objectName={}", documentId, objectName, e);
            throw new DocumentException(DocumentManageCode.MINIO_UPLOAD_FAIL, e.getMessage(), e);
        }
        return objectName;
    }

    @Override
    public byte[] downloadObject(String objectName) {
        try {
            InputStream inputStream = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(properties.getMinio().getBucketName())
                    .object(objectName)
                    .build());
            return inputStream.readAllBytes();
        } catch (Exception e) {
            log.error("MinIO 下载失败 objectName={}", objectName, e);
            throw new DocumentException(DocumentManageCode.MINIO_DOWNLOAD_FAIL, e.getMessage(), e);
        }
    }

    @Override
    public void deleteObject(String objectName) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getMinio().getBucketName())
                    .object(objectName)
                    .build());
            log.info("MinIO 对象已删除 objectName={}", objectName);
        } catch (Exception e) {
            log.warn("MinIO 删除失败 objectName={}", objectName, e);
        }
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        // MinIO 删除前缀下所有对象需要 listObjects + 逐个 remove
        // 这里只记录 warn，实际由级联删除策略保证：存储删除失败不阻断 DB 删除
        String prefix = properties.getMinio().getObjectPrefix() + "/" + documentId;
        log.info("MinIO 目录清理请求: prefix={}", prefix);
        try {
            var objects = minioClient.listObjects(io.minio.ListObjectsArgs.builder()
                    .bucket(properties.getMinio().getBucketName())
                    .prefix(prefix)
                    .recursive(true)
                    .build());
            for (var result : objects) {
                try {
                    minioClient.removeObject(RemoveObjectArgs.builder()
                            .bucket(properties.getMinio().getBucketName())
                            .object(result.get().objectName())
                            .build());
                } catch (Exception e) {
                    log.warn("MinIO 单对象删除失败 objectName={}", result.get().objectName(), e);
                }
            }
        } catch (Exception e) {
            log.warn("MinIO 目录删除异常 prefix={}", prefix, e);
        }
    }

    /**
     * 拼接对象访问 URL。
     *
     * <p>格式：{@code endpoint/bucket/objectName}。
     * 注意：简单字符串拼接，endpoint 不应有尾部斜杠。</p>
     */
    private String buildObjectUrl(DocumentProperties.Minio minio, String objectName) {
        return minio.getEndpoint() + "/" + minio.getBucketName() + "/" + objectName;
    }
}
