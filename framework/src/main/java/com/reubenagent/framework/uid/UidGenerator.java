package com.reubenagent.framework.uid;

import com.reubenagent.framework.uid.exception.UidGenerateException;

/**
 * UID 生成器接口。
 *
 * <p>提供全局唯一 64-bit long 型 ID，趋势递增，适用于数据库主键。</p>
 *
 * <h3>位分配（64 bits）</h3>
 * <pre>
 * [符号 1bit] [时间戳 28bit] [WorkerID 22bit] [序列号 13bit]
 * </pre>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @Autowired
 * private UidGenerator uidGenerator;
 *
 * long id = uidGenerator.getUid();
 * entity.setId(id);
 * }</pre>
 *
 * @author reuben
 * @since 2026-06-14
 */
public interface UidGenerator {

    /**
     * 获取一个全局唯一 ID。
     *
     * @return 64-bit 唯一 ID（正数，趋势递增）
     * @throws UidGenerateException 生成失败时抛出
     */
    long getUid() throws UidGenerateException;

    /**
     * 解析 UID 的组成结构。
     *
     * @param uid 待解析的 ID
     * @return JSON 字符串，包含 timestamp / workerId / sequence 等信息
     */
    String parseUid(long uid);
}
