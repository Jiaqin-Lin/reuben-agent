package com.reubenagent.document.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 存储对象信息 —— MinIO 上传完成后返回的定位信息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StoredObjectInfo {

    /** 存储桶名称 */
    private String bucketName;

    /** 对象键（存储路径 + 文件名） */
    private String objectName;

    /** 对象访问 URL（公开直链，由 endpoint + bucket + objectName 拼接） */
    private String objectUrl;

}
