package com.reubenagent.common.exception;

import com.reubenagent.common.enums.DocumentManageCode;
import lombok.Getter;

/**
 * 文档管理领域异常 —— 直接包装 {@link DocumentManageCode} 枚举。
 *
 * <h3>与直接用 {@link BusinessException} 的区别</h3>
 * <ol>
 *   <li>message 自动从枚举取值，不需要手动保持 code 和 message 一致。</li>
 *   <li>{@link #getDocumentCode()} 可取回枚举，方便日志和监控按枚举名统计。</li>
 *   <li>{@code catch (DocumentException e)} 可精确捕获文档异常，不被其他业务异常干扰。</li>
 * </ol>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 文件为空
 * throw new DocumentException(DocumentManageCode.EMPTY_FILE);
 *
 * // 文件类型不支持 + 补充详情
 * throw new DocumentException(DocumentManageCode.UNSUPPORTED_FILE_TYPE,
 *     "仅支持 .pdf, .docx, .txt，当前: .exe");
 *
 * // MinIO 上传失败 + 根因
 * throw new DocumentException(DocumentManageCode.MINIO_UPLOAD_FAIL,
 *     "上传到 MinIO 失败", e);
 * }</pre>
 *
 * @author reuben
 * @since 2026-06-14
 */
@Getter
public class DocumentException extends BusinessException {

    /**
     * -- GETTER --
     * 关联的文档错误码枚举，可能为 null（自定义 code 构造时）
     */
    private final DocumentManageCode documentCode;

    public DocumentException(DocumentManageCode documentCode) {
        super(documentCode.getCode(), documentCode.getMsg());
        this.documentCode = documentCode;
    }

    public DocumentException(DocumentManageCode documentCode, String detail) {
        super(documentCode.getCode(), documentCode.getMsg() + " —— " + detail);
        this.documentCode = documentCode;
    }

    public DocumentException(DocumentManageCode documentCode, String detail, Throwable cause) {
        super(documentCode.getCode(), documentCode.getMsg() + " —— " + detail, cause);
        this.documentCode = documentCode;
    }

}
