package com.nageoffer.shortlink.project.service;


import com.nageoffer.shortlink.project.common.convention.exception.ServiceException;
import com.nageoffer.shortlink.project.dao.entity.ShortLinkSequenceDO;
import com.nageoffer.shortlink.project.dao.mapper.ShortLinkSequenceMapper;
import com.nageoffer.shortlink.project.toolkit.Segment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.rmi.ServerException;


@Service
@RequiredArgsConstructor
public class SegmentAllocatorService {
    private final ShortLinkSequenceMapper shortLinkSequenceMapper;

    @Transactional(rollbackFor = Exception.class)
    public Segment allocateSegment(String bizTag){
        ShortLinkSequenceDO sequenceDO =
        shortLinkSequenceMapper.selectByBizTagForUpdate(bizTag);

        if (sequenceDO == null){
            throw new ServiceException("号段配置不存在，bizTag = " + bizTag);
        }

        long oldMaxId = sequenceDO.getMaxId();
        int step = sequenceDO.getStep();

        if (step <= 0) {
            throw new ServiceException(
                    "号段 step 配置错误，bizTag=" + bizTag
            );
        }

        long newMaxId;

        try {
            newMaxId = Math.addExact(oldMaxId, step);
        } catch (ArithmeticException ex) {
            throw new ServiceException(
                    "号段 ID 已达到 Long 上限"
            );
        }

        int affectedRows =
                shortLinkSequenceMapper.updateMaxId(
                        bizTag,
                        newMaxId
                );

        if (affectedRows != 1) {
            throw new ServiceException(
                    "申请号段失败，bizTag=" + bizTag
            );
        }

        return new Segment(
                oldMaxId + 1,
                newMaxId
        );
    }
}
