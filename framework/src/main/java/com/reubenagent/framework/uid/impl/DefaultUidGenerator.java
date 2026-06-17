package com.reubenagent.framework.uid.impl;

import com.reubenagent.framework.uid.BitsAllocator;
import com.reubenagent.framework.uid.UidGenerator;
import com.reubenagent.framework.uid.exception.UidGenerateException;
import com.reubenagent.framework.uid.utils.AbstractDateUtils;
import com.reubenagent.framework.uid.worker.WorkerIdAssigner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;

import java.util.concurrent.TimeUnit;

/**
 * 默认 UID 生成器 —— 基于百度 UID 框架的位分配方案。
 *
 * <h3>位分配</h3>
 * <pre>
 * [符号 1bit] [时间戳 28bit] [WorkerID 22bit] [序列号 13bit]
 * </pre>
 *
 * <h3>关键参数</h3>
 * <ul>
 *   <li>epoch 起始日期：2024-05-20（秒级时间戳）</li>
 *   <li>时间戳 28 bit：可用 ~8.5 年（到 2032 年左右）</li>
 *   <li>WorkerID 22 bit：最多 419 万节点</li>
 *   <li>序列号 13 bit：单节点 8192 / 秒</li>
 *   <li>支持时钟回拨检测：回拨直接抛异常</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * @Autowired
 * private UidGenerator uidGenerator;
 *
 * long userId = uidGenerator.getUid();
 * }</pre>
 *
 * @author reuben
 * @since 2026-06-14
 */
@Slf4j
public class DefaultUidGenerator implements UidGenerator, InitializingBean {

    // ============ 位分配参数（可通过 setter 覆盖） ============
    protected int timeBits = 28;
    protected int workerBits = 22;
    protected int seqBits = 13;

    /** epoch 起始日期，默认 2024-05-20 */
    protected String epochStr = "2024-05-20";
    /** epoch 对应的秒数（启动时根据 epochStr 计算） */
    protected long epochSeconds;

    // ============ 运行时状态 ============
    protected BitsAllocator bitsAllocator;
    protected long workerId;
    protected WorkerIdAssigner workerIdAssigner;

    /** 当前秒内的序列号 */
    protected long sequence = 0L;
    /** 上一次生成 ID 的秒数 */
    protected long lastSecond = -1L;

    // ============ 初始化 ============

    @Override
    public void afterPropertiesSet() {
        // 1. 初始化位分配器
        bitsAllocator = new BitsAllocator(timeBits, workerBits, seqBits);

        // 2. 分配 WorkerID
        workerId = workerIdAssigner.assignWorkerId();
        if (workerId > bitsAllocator.getMaxWorkerId()) {
            throw new IllegalStateException(
                    "WorkerID " + workerId + " 超过最大值 " + bitsAllocator.getMaxWorkerId());
        }

        // 3. 计算 epoch 秒数
        this.epochSeconds = AbstractDateUtils.parseEpochSeconds(epochStr);

        log.info("DefaultUidGenerator 初始化完成 — bits(1, {}, {}, {}), workerId={}",
                timeBits, workerBits, seqBits, workerId);
    }

    // ============ 公开方法 ============

    @Override
    public long getUid() throws UidGenerateException {
        try {
            return nextId();
        } catch (UidGenerateException e) {
            throw e;
        } catch (Exception e) {
            log.error("生成 UID 异常", e);
            throw new UidGenerateException(e);
        }
    }

    @Override
    public String parseUid(long uid) {
        long totalBits = BitsAllocator.TOTAL_BITS;
        long signBits = bitsAllocator.getSignBits();
        long timestampBits = bitsAllocator.getTimestampBits();
        long workerIdBits = bitsAllocator.getWorkerIdBits();
        long sequenceBits = bitsAllocator.getSequenceBits();

        long sequence = (uid << (totalBits - sequenceBits)) >>> (totalBits - sequenceBits);
        long workerId = (uid << (timestampBits + signBits)) >>> (totalBits - workerIdBits);
        long deltaSeconds = uid >>> (workerIdBits + sequenceBits);

        String thatTimeStr = AbstractDateUtils.formatTimestamp(epochSeconds + deltaSeconds);

        return String.format(
                "{\"UID\":\"%d\",\"timestamp\":\"%s\",\"workerId\":\"%d\",\"sequence\":\"%d\"}",
                uid, thatTimeStr, workerId, sequence);
    }

    // ============ 核心逻辑 ============

    /**
     * 生成下一个 ID（synchronized 保证线程安全）。
     */
    protected synchronized long nextId() {
        long currentSecond = getCurrentSecond();

        // 时钟回拨检测
        if (currentSecond < lastSecond) {
            long refusedSeconds = lastSecond - currentSecond;
            throw new UidGenerateException(
                    "时钟回拨，拒绝生成 ID。回拨秒数: %d", refusedSeconds);
        }

        // 同一秒内序列号递增
        if (currentSecond == lastSecond) {
            sequence = (sequence + 1) & bitsAllocator.getMaxSequence();
            // 序列号用完，等待下一秒
            if (sequence == 0) {
                currentSecond = getNextSecond(lastSecond);
            }
        } else {
            // 新的一秒，序列号归零
            sequence = 0L;
        }

        lastSecond = currentSecond;

        return bitsAllocator.allocate(
                currentSecond - epochSeconds, workerId, sequence);
    }

    // ============ 时间工具 ============

    /**
     * 等待直到下一秒钟。
     *
     * <p>自旋等待 + 短暂睡眠，避免空转耗尽 CPU。</p>
     */
    private long getNextSecond(long lastTimestamp) {
        long timestamp = getCurrentSecond();
        // 自旋几次（微秒级），等不到就 sleep 1ms 让出 CPU
        int spins = 0;
        while (timestamp <= lastTimestamp) {
            if (++spins > 100) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new UidGenerateException("等待下一秒时被中断");
                }
                spins = 0;
            } else {
                Thread.onSpinWait();
            }
            timestamp = getCurrentSecond();
        }
        return timestamp;
    }

    /** 获取当前秒数，并检查时间戳位是否耗尽 */
    private long getCurrentSecond() {
        long currentSecond = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
        if (currentSecond - epochSeconds > bitsAllocator.getMaxDeltaSeconds()) {
            throw new UidGenerateException(
                    "时间戳位耗尽，无法再生成 ID。当前秒数: %d", currentSecond);
        }
        return currentSecond;
    }

    // ============ setter（Spring 注入） ============

    public void setWorkerIdAssigner(WorkerIdAssigner workerIdAssigner) {
        this.workerIdAssigner = workerIdAssigner;
    }

    public void setTimeBits(int timeBits) {
        if (timeBits > 0) {
            this.timeBits = timeBits;
        }
    }

    public void setWorkerBits(int workerBits) {
        if (workerBits > 0) {
            this.workerBits = workerBits;
        }
    }

    public void setSeqBits(int seqBits) {
        if (seqBits > 0) {
            this.seqBits = seqBits;
        }
    }

    public void setEpochStr(String epochStr) {
        if (epochStr != null && !epochStr.isBlank()) {
            this.epochStr = epochStr;
        }
    }
}
