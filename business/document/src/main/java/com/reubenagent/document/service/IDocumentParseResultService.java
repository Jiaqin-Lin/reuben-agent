package com.reubenagent.document.service;

import com.reubenagent.document.enums.DocumentFileTypeEnum;
import com.reubenagent.document.model.DocumentParseResult;

public interface IDocumentParseResultService {

    DocumentParseResult parse(byte[] fileBytes, String originalFileName, String mediaType, DocumentFileTypeEnum fileType);

}
