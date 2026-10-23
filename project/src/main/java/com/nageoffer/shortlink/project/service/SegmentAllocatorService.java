/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
