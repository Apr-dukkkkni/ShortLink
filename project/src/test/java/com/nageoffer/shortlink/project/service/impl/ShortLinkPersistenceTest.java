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

package com.nageoffer.shortlink.project.service.impl;

import com.nageoffer.shortlink.project.common.convention.exception.ServiceException;
import com.nageoffer.shortlink.project.config.GotoDomainWhiteListConfiguration;
import com.nageoffer.shortlink.project.dao.entity.ShortLinkDO;
import com.nageoffer.shortlink.project.dao.entity.ShortLinkGotoDO;
import com.nageoffer.shortlink.project.dao.mapper.ShortLinkMapper;
import com.nageoffer.shortlink.project.dao.mapper.ShortLinkGotoMapper;
import com.nageoffer.shortlink.project.dto.req.ShortLinkCreateReqDTO;
import com.nageoffer.shortlink.project.mq.producer.ShortLinkStatsSaveProducer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShortLinkPersistenceTest {

    private static final String URL =
            "https://www.baidu.com";

    private static final String GID =
            "XMQIfR";

    private static final String SHORT_URL =
            "nurl.ink:8001/test123";

    @Mock
    private ShortLinkMapper shortLinkMapper;

    @Mock
    private ShortLinkGotoMapper shortLinkGotoMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private GotoDomainWhiteListConfiguration gotoDomainWhiteListConfiguration;

    @Mock
    private RBloomFilter<String> shortUriCreateCachePenetrationBloomFilter;

    @Mock
    private RBloomFilter<String> shortUrlRBloomFilter;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private ShortLinkStatsSaveProducer shortLinkStatsSaveProducer;

    @InjectMocks
    private ShortLinkServiceImpl shortLinkService;

    @BeforeEach
    void setUp() {

        // ServiceImpl 的 baseMapper 位于父类中
        ReflectionTestUtils.setField(
                shortLinkService,
                "baseMapper",
                shortLinkMapper
        );
    }

    // 构造测试请求
    private ShortLinkCreateReqDTO request() {

        return ShortLinkCreateReqDTO.builder()
                .domain("nurl.ink:8001")
                .originUrl(URL)
                .gid(GID)
                .createdType(1)
                .validDateType(0)
                .validDate(null)
                .describe("持久化自动化测试")
                .build();
    }

    // 构造数据库实体
    private ShortLinkDO link() {

        return ShortLinkDO.builder()
                .domain("nurl.ink:8001")
                .originUrl(URL)
                .gid(GID)
                .shortUri("test123")
                .fullShortUrl(SHORT_URL)
                .createdType(1)
                .validDateType(0)
                .enableStatus(0)
                .build();
    }

    // 执行实际的私有持久化方法
    private void executeSave() {

        ReflectionTestUtils.invokeMethod(
                shortLinkService,
                "saveSingleShortLink",
                link(),
                request()
        );
    }

    // 配置两次插入正常返回
    private void mockDatabaseSuccess() {

        when(shortLinkMapper.insert(
                any(ShortLinkDO.class)
        )).thenReturn(1);

        when(shortLinkGotoMapper.insert(
                any(ShortLinkGotoDO.class)
        )).thenReturn(1);
    }

    // ==================================
    // 1. 第一张表插入失败
    // ==================================

    @Test
    void test01_FirstInsertFailure() {

        DataAccessResourceFailureException exception =
                new DataAccessResourceFailureException(
                        "模拟主表数据库不可用"
                );

        when(shortLinkMapper.insert(
                any(ShortLinkDO.class)
        )).thenThrow(exception);

        RuntimeException actual = assertThrows(
                RuntimeException.class,
                this::executeSave
        );

        // 数据库故障不应伪装成重复短码
        assertSame(exception, actual);

        verify(shortLinkMapper, times(1))
                .insert(any(ShortLinkDO.class));

        verifyNoInteractions(
                shortLinkGotoMapper,
                stringRedisTemplate
        );
    }

    // ==================================
    // 2. 第二张表插入失败
    // ==================================

    @Test
    void test02_SecondInsertFailure() {

        when(shortLinkMapper.insert(
                any(ShortLinkDO.class)
        )).thenReturn(1);

        DataAccessResourceFailureException exception =
                new DataAccessResourceFailureException(
                        "模拟跳转表数据库不可用"
                );

        when(shortLinkGotoMapper.insert(
                any(ShortLinkGotoDO.class)
        )).thenThrow(exception);

        RuntimeException actual = assertThrows(
                RuntimeException.class,
                this::executeSave
        );

        assertSame(exception, actual);

        InOrder order = inOrder(
                shortLinkMapper,
                shortLinkGotoMapper
        );

        order.verify(shortLinkMapper)
                .insert(any(ShortLinkDO.class));

        order.verify(shortLinkGotoMapper)
                .insert(any(ShortLinkGotoDO.class));

        verifyNoInteractions(stringRedisTemplate);
    }

    // ==================================
    // 3. 第一张表唯一键冲突
    // ==================================

    @Test
    void test03_FirstInsertDuplicateKey() {

        when(shortLinkMapper.insert(
                any(ShortLinkDO.class)
        )).thenThrow(
                new DuplicateKeyException("重复短码")
        );

        assertThrows(
                ServiceException.class,
                this::executeSave
        );

        verifyNoInteractions(
                shortLinkGotoMapper,
                stringRedisTemplate
        );
    }

    // ==================================
    // 4. 第二张表唯一键冲突
    // ==================================

    @Test
    void test04_SecondInsertDuplicateKey() {

        when(shortLinkMapper.insert(
                any(ShortLinkDO.class)
        )).thenReturn(1);

        when(shortLinkGotoMapper.insert(
                any(ShortLinkGotoDO.class)
        )).thenThrow(
                new DuplicateKeyException("跳转表重复")
        );

        assertThrows(
                ServiceException.class,
                this::executeSave
        );

        verifyNoInteractions(stringRedisTemplate);
    }

    // ==================================
    // 5. 数据库成功，但 Redis 写入失败
    // ==================================

    @Test
    void test05_RedisWriteFailure() {

        mockDatabaseSuccess();

        when(stringRedisTemplate.opsForValue())
                .thenReturn(valueOperations);

        doThrow(
                new RedisConnectionFailureException(
                        "模拟 Redis 连接失败"
                )
        ).when(valueOperations).set(
                anyString(),
                anyString(),
                anyLong(),
                eq(TimeUnit.MILLISECONDS)
        );

        assertThrows(
                RedisConnectionFailureException.class,
                this::executeSave
        );

        verify(shortLinkMapper)
                .insert(any(ShortLinkDO.class));

        verify(shortLinkGotoMapper)
                .insert(any(ShortLinkGotoDO.class));

        // Redis 失败后不应继续更新布隆过滤器
        verifyNoInteractions(
                shortUriCreateCachePenetrationBloomFilter,
                shortUrlRBloomFilter
        );
    }

    // ==================================
    // 6. 正常保存
    // ==================================

    @Test
    void test06_NormalPersistence() {

        mockDatabaseSuccess();

        when(stringRedisTemplate.opsForValue())
                .thenReturn(valueOperations);

        assertDoesNotThrow(this::executeSave);

        InOrder order = inOrder(
                shortLinkMapper,
                shortLinkGotoMapper,
                valueOperations
        );

        order.verify(shortLinkMapper)
                .insert(any(ShortLinkDO.class));

        order.verify(shortLinkGotoMapper)
                .insert(any(ShortLinkGotoDO.class));

        order.verify(valueOperations).set(
                anyString(),
                eq(URL),
                anyLong(),
                eq(TimeUnit.MILLISECONDS)
        );
    }
}
