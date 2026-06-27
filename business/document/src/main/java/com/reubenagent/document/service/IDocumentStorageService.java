package com.reubenagent.document.service;

import com.reubenagent.document.model.StoredObjectInfo;

public interface IDocumentStorageService {

    StoredObjectInfo uploadOriginalFile(Long documentId, String originalFileName, byte[] fileBytes, String contentType);

    String uploadParsedText(Long documentId, String parsedText);

    byte[] downloadObject(String objectName);

    /**
     * 删除 MinIO 中的对象。
     *
     * @param objectName 对象存储路径
     */
    void deleteObject(String objectName);

    /**
     * 删除指定文档在 MinIO 中的所有数据（原文件 + 解析文本）。
     *
     * @param documentId 文档ID
     */
    void deleteByDocumentId(Long documentId);
}
