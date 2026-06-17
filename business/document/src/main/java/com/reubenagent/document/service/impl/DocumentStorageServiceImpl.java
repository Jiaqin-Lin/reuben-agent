package com.reubenagent.document.service.impl;

import com.reubenagent.common.enums.DocumentManageCode;
import com.reubenagent.common.exception.DocumentException;
import com.reubenagent.document.config.DocumentProperties;
import com.reubenagent.document.model.StoredObjectInfo;
import com.reubenagent.document.service.IDocumentStorageService;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * MinIO 文档存储服务实现。
 *
 * @author reuben
 * @since 2026-06-14
 */
@Slf4j
@Service
@RequiredArgsConstructor
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
            throw new DocumentException(DocumentManageCode.MINIO_UPLOAD_FAIL, e.getMessage(), e);
        }

        return new StoredObjectInfo(minio.getBucketName(), objectName,
                buildObjectUrl(minio, objectName));
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
            throw new DocumentException(DocumentManageCode.MINIO_DOWNLOAD_FAIL, e.getMessage(), e);
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
