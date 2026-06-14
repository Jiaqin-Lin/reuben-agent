package com.reubenagent.framework.uid.worker;

/**
 * WorkerID 分配器接口。
 *
 * <p>每个服务实例在启动时需要获取一个唯一的 WorkerID，用于 UID 生成器中
 * 区分不同节点。不同的实现可以选择不同的分配策略：</p>
 * <ul>
 *   <li>Redis INCR 原子自增（推荐，适用于 K8s / 动态扩缩容）</li>
 *   <li>数据库表维护</li>
 *   <li>环境变量 / 配置文件指定（适用于固定节点数）</li>
 * </ul>
 *
 * @author reuben
 * @since 2026-06-14
 */
public interface WorkerIdAssigner {

    /**
     * 为当前实例分配一个唯一的 WorkerID。
     *
     * @return WorkerID，范围受 BitsAllocator 的 workerBits 限制
     */
    long assignWorkerId();
}
