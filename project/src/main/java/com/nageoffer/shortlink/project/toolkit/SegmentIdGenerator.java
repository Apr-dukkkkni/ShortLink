package com.nageoffer.shortlink.project.service.id;

import com.nageoffer.shortlink.project.service.SegmentAllocatorService;
import com.nageoffer.shortlink.project.toolkit.Segment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SegmentIdGenerator {

    /**
     * 当前号段。
     *
     * volatile：
     * 保证不同线程能够看到最新替换的 Segment。
     */
    private volatile Segment currentSegment;

    /**
     * 只有切换号段的时候需要加锁。
     */
    private final Object refillLock = new Object();

    private final SegmentAllocatorService segmentAllocatorService;

    /**
     * 当前业务标识。
     */
    private static final String BIZ_TAG = "short_link";

    /**
     * 获取一个全局唯一 ID。
     */
    public long nextId() {

        while (true) {

            Segment segment = currentSegment;

            /*
             * 第一次启动，还没有号段。
             */
            if (segment == null) {
                refillSegment();
                continue;
            }

            /*
             * 直接从 JVM 内存里拿 ID。
             */
            long id = segment.tryNextId();

            if (id != -1L) {
                return id;
            }

            /*
             * 当前号段已经用完。
             * 申请新的。
             */
            refillSegment();
        }
    }

    /**
     * 补充新的号段。
     *
     * synchronized 只发生在：
     *
     * 1. 第一次加载
     * 2. 当前号段耗尽
     *
     * 正常 nextId() 不需要锁数据库。
     */
    private void refillSegment() {

        synchronized (refillLock) {

            /*
             * Double Check。
             *
             * 可能线程 A 已经申请了新的号段，
             * 线程 B 排队进来时就不用重复申请。
             */
            Segment segment = currentSegment;

            if (segment != null && segment.hasRemaining()) {
                return;
            }

            currentSegment =
                    segmentAllocatorService
                            .allocateSegment(BIZ_TAG);
        }
    }
}