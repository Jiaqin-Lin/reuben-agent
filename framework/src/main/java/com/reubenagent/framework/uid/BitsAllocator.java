package com.reubenagent.framework.uid;

/**
 * 64-bit 位分配器。
 *
 * <p>将 long 型 UID 按约定位宽拆分为三部分：</p>
 * <pre>
 * [符号位 1] [时间戳位 timeBits] [WorkerID 位 workerBits] [序列号位 seqBits]
 * </pre>
 *
 * <p>默认配置：28 + 22 + 13 = 63 bit（符号位 1bit）</p>
 * <ul>
 *   <li>时间戳：28 bit ≈ 8.5 年（从 epoch 起算）</li>
 *   <li>WorkerID：22 bit ≈ 419 万节点</li>
 *   <li>序列号：13 bit = 8192/秒</li>
 * </ul>
 *
 * @author reuben
 * @since 2026-06-14
 */
public class BitsAllocator {

    public static final int TOTAL_BITS = 64;

    private final int signBits = 1;
    private final int timestampBits;
    private final int workerIdBits;
    private final int sequenceBits;

    /** 最大时间差（秒） */
    private final long maxDeltaSeconds;
    /** 最大 WorkerID */
    private final long maxWorkerId;
    /** 最大序列号 */
    private final long maxSequence;

    /** 时间戳左移位数 = workerBits + seqBits */
    private final int timestampShift;
    /** WorkerID 左移位数 = seqBits */
    private final int workerIdShift;

    public BitsAllocator(int timestampBits, int workerIdBits, int sequenceBits) {
        int total = signBits + timestampBits + workerIdBits + sequenceBits;
        if (total != TOTAL_BITS) {
            throw new IllegalArgumentException(
                    "Total bits must be 64, but was " + total);
        }

        this.timestampBits = timestampBits;
        this.workerIdBits = workerIdBits;
        this.sequenceBits = sequenceBits;

        this.maxDeltaSeconds = ~(-1L << timestampBits);
        this.maxWorkerId = ~(-1L << workerIdBits);
        this.maxSequence = ~(-1L << sequenceBits);

        this.timestampShift = workerIdBits + sequenceBits;
        this.workerIdShift = sequenceBits;
    }

    /**
     * 将三部分拼接为一个 64-bit UID。
     *
     * @param deltaSeconds 当前时间与 epoch 的差值（秒）
     * @param workerId     机器标识
     * @param sequence     序列号
     * @return 拼装后的 long 型 ID
     */
    public long allocate(long deltaSeconds, long workerId, long sequence) {
        return (deltaSeconds << timestampShift)
                | (workerId << workerIdShift)
                | sequence;
    }

    // ============ getters ============

    public int getSignBits()          { return signBits; }
    public int getTimestampBits()     { return timestampBits; }
    public int getWorkerIdBits()      { return workerIdBits; }
    public int getSequenceBits()      { return sequenceBits; }
    public long getMaxDeltaSeconds()  { return maxDeltaSeconds; }
    public long getMaxWorkerId()      { return maxWorkerId; }
    public long getMaxSequence()      { return maxSequence; }
    public int getTimestampShift()    { return timestampShift; }
    public int getWorkerIdShift()     { return workerIdShift; }
}
