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

import com.nageoffer.shortlink.project.config.GotoDomainWhiteListConfiguration;
import com.nageoffer.shortlink.project.dao.entity.ShortLinkDO;
import com.nageoffer.shortlink.project.toolkit.SegmentIdGenerator;
import com.nageoffer.shortlink.project.dao.entity.ShortLinkGotoDO;
import com.nageoffer.shortlink.project.dao.mapper.ShortLinkMapper;
import com.nageoffer.shortlink.project.dao.mapper.ShortLinkGotoMapper;
import com.nageoffer.shortlink.project.dto.req.ShortLinkCreateReqDTO;
import com.nageoffer.shortlink.project.mq.producer.ShortLinkStatsSaveProducer;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShortLinkTransactionTest {

    @Mock
    private ShortLinkMapper shortLinkMapper;

    @Mock
    private ShortLinkGotoMapper shortLinkGotoMapper;

    @Mock
    private GotoDomainWhiteListConfiguration gotoDomainWhiteListConfiguration;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private RBloomFilter<String> shortUriCreateCachePenetrationBloomFilter;

    @Mock
    private RBloomFilter<String> shortUrlRBloomFilter;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private ShortLinkStatsSaveProducer shortLinkStatsSaveProducer;

    @Mock
    private SegmentIdGenerator segmentIdGenerator;

    @InjectMocks
    private ShortLinkServiceImpl targetService;

    private ShortLinkServiceImpl service;
    private JdbcTemplate jdbc;

    private static HttpServer httpServer;
    private static String originUrl;

    // 只在本机启动一个临时 HTTP 服务
    // 避免 getFavicon() 访问互联网
    @BeforeAll
    static void startLocalServer() throws Exception {

        httpServer = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );

        httpServer.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });

        httpServer.start();

        originUrl = "http://127.0.0.1:"
                + httpServer.getAddress().getPort()
                + "/test";
    }

    @AfterAll
    static void stopLocalServer() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @BeforeEach
    void setUp() {

        // 每项测试使用独立的内存数据库
        String databaseName = "shortlink_tx_"
                + UUID.randomUUID().toString()
                .replace("-", "");

        DriverManagerDataSource dataSource =
                new DriverManagerDataSource();

        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl(
                "jdbc:h2:mem:" + databaseName
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1"
        );

        dataSource.setUsername("sa");
        dataSource.setPassword("");

        jdbc = new JdbcTemplate(dataSource);

        // 两张简化测试表
        jdbc.execute("""
                CREATE TABLE test_link (
                    full_short_url VARCHAR(255) PRIMARY KEY,
                    origin_url VARCHAR(2048),
                    gid VARCHAR(128)
                )
                """);

        jdbc.execute("""
                CREATE TABLE test_link_goto (
                    full_short_url VARCHAR(255) PRIMARY KEY,
                    gid VARCHAR(128)
                )
                """);

        // 主表 Mapper 调用真实 JDBC
        doAnswer(invocation -> {

            ShortLinkDO entity =
                    invocation.getArgument(0);

            return jdbc.update(
                    """
                    INSERT INTO test_link
                    (full_short_url, origin_url, gid)
                    VALUES (?, ?, ?)
                    """,
                    entity.getFullShortUrl(),
                    entity.getOriginUrl(),
                    entity.getGid()
            );

        }).when(shortLinkMapper)
                .insert(any(ShortLinkDO.class));

        // 跳转映射表 Mapper 调用真实 JDBC
        lenient().doAnswer(invocation -> {

            ShortLinkGotoDO entity =
                    invocation.getArgument(0);

            return jdbc.update(
                    """
                    INSERT INTO test_link_goto
                    (full_short_url, gid)
                    VALUES (?, ?)
                    """,
                    entity.getFullShortUrl(),
                    entity.getGid()
            );

        }).when(shortLinkGotoMapper)
                .insert(any(ShortLinkGotoDO.class));

        // 禁用白名单，专注于事务逻辑
        lenient().when(
                gotoDomainWhiteListConfiguration.getEnable()
        ).thenReturn(false);

        lenient().when(
                stringRedisTemplate.opsForValue()
        ).thenReturn(valueOperations);

        ReflectionTestUtils.setField(
                targetService,
                "baseMapper",
                shortLinkMapper
        );

        ReflectionTestUtils.setField(
                targetService,
                "createShortLinkDefaultDomain",
                "nurl.ink:8001"
        );

        // 通过 Spring TransactionInterceptor
        // 根据方法上的 @Transactional 开启事务
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);

        TransactionInterceptor interceptor =
                new TransactionInterceptor(
                        transactionManager,
                        new AnnotationTransactionAttributeSource()
                );

        ReflectionTestUtils.setField(
                targetService,
                "segmentIdGenerator",
                segmentIdGenerator
        );

        lenient().when(segmentIdGenerator.nextId())
                .thenReturn(1000001L);

        ProxyFactory proxyFactory =
                new ProxyFactory(targetService);

        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(interceptor);

        service = (ShortLinkServiceImpl)
                proxyFactory.getProxy();
    }

    private ShortLinkCreateReqDTO request() {

        return ShortLinkCreateReqDTO.builder()
                .originUrl(originUrl)
                .domain("nurl.ink:8001")
                .gid("XMQIfR")
                .createdType(1)
                .validDateType(0)
                .validDate(null)
                .describe("事务回滚自动化测试")
                .build();
    }

    private int countLinks() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM test_link",
                Integer.class
        );
    }

    private int countGotoLinks() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM test_link_goto",
                Integer.class
        );
    }

    private void assertBothTablesEmpty() {

        assertEquals(
                0,
                countLinks(),
                "主表出现未回滚的数据"
        );

        assertEquals(
                0,
                countGotoLinks(),
                "跳转表出现未回滚的数据"
        );
    }

    // 模拟第二张表数据库异常
    private void failSecondInsert() {

        doThrow(
                new DataAccessResourceFailureException(
                        "模拟第二张表插入失败"
                )
        ).when(shortLinkGotoMapper)
                .insert(any(ShortLinkGotoDO.class));
    }

    // 模拟 Redis 写入异常
    private void failRedisWrite() {

        doThrow(
                new RedisConnectionFailureException(
                        "模拟 Redis 写入失败"
                )
        ).when(valueOperations).set(
                anyString(),
                anyString(),
                anyLong(),
                eq(TimeUnit.MILLISECONDS)
        );
    }

    // 准备分布式锁
    private void mockLockSuccess() throws Exception {

        RLock lock = mock(RLock.class);

        when(redissonClient.getLock(anyString()))
                .thenReturn(lock);

        when(lock.tryLock(
                eq(3L),
                eq(30L),
                eq(TimeUnit.SECONDS)
        )).thenReturn(true);

        when(lock.isHeldByCurrentThread())
                .thenReturn(true);
    }

    // =====================================
    // 1. 普通创建：正常提交
    // =====================================

    @Test
    void test01_NormalCreateCommitsBothTables() {

        assertDoesNotThrow(
                () -> service.createShortLink(request())
        );

        assertEquals(1, countLinks());
        assertEquals(1, countGotoLinks());
    }

    // =====================================
    // 2. 普通创建：第二张表失败
    // =====================================

    @Test
    void test02_SecondInsertFailureRollsBack() {

        failSecondInsert();

        assertThrows(
                RuntimeException.class,
                () -> service.createShortLink(request())
        );

        assertBothTablesEmpty();
    }

    // =====================================
    // 3. 普通创建：Redis 写入失败
    // =====================================

    @Test
    void test03_RedisFailureRollsBackDatabase() {

        failRedisWrite();

        assertThrows(
                RuntimeException.class,
                () -> service.createShortLink(request())
        );

        assertBothTablesEmpty();
    }

    // =====================================
    // 4. 加锁创建：第二张表失败
    // =====================================

    @Test
    void test04_LockedCreateSecondInsertRollback()
            throws Exception {

        mockLockSuccess();
        failSecondInsert();

        assertThrows(
                RuntimeException.class,
                () -> service.createShortLinkByLock(request())
        );

        assertBothTablesEmpty();
    }

    // =====================================
    // 5. 加锁创建：Redis 写入失败
    // =====================================

    @Test
    void test05_LockedCreateRedisFailureRollback()
            throws Exception {

        mockLockSuccess();
        failRedisWrite();

        assertThrows(
                RuntimeException.class,
                () -> service.createShortLinkByLock(request())
        );

        assertBothTablesEmpty();
    }
}
