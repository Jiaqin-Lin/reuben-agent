package com.reubenagent.document.service;

import com.reubenagent.document.model.StoredObjectInfo;

public interface IDocumentStorageService {

    StoredObjectInfo uploadOriginalFile(Long documentId, String originalFileName, byte[] fileBytes, String contentType);

    String uploadParsedText(Long documentId, String parsedText);

    byte[] downloadObject(String objectName);
}
