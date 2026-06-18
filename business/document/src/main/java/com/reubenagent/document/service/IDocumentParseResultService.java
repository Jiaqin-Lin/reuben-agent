package com.reubenagent.document.service;

import com.reubenagent.document.enums.DocumentFileTypeEnum;
import com.reubenagent.document.model.DocumentParseResult;

public interface IDocumentParseResultService {

    /**
     * 解析文档为结构化结果。
     *
     * @param fileBytes     文件字节数组
     * @param documentTitle 文档标题（优先取用户指定的 documentName，其次取原始文件名去扩展名）
     * @param mediaType     HTTP Content-Type（可为 null）
     * @param fileType      文件类型枚举
     * @return 解析结果，含纯文本、字符数、结构节点
     */
    DocumentParseResult parse(byte[] fileBytes, String documentTitle, String mediaType, DocumentFileTypeEnum fileType);

}
