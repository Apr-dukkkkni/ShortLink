package com.nageoffer.shortlink.project.integration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.shortlink.project.ShortLinkApplication;
import com.nageoffer.shortlink.project.common.constant.RedisKeyConstant;
import com.nageoffer.shortlink.project.dao.entity.ShortLinkDO;
import com.nageoffer.shortlink.project.dao.entity.ShortLinkGotoDO;
import com.nageoffer.shortlink.project.dao.mapper.ShortLinkGotoMapper;
import com.nageoffer.shortlink.project.dao.mapper.ShortLinkMapper;
import com.nageoffer.shortlink.project.dto.req.ShortLinkCreateReqDTO;
import com.nageoffer.shortlink.project.service.ShortLinkService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 保留真实 Service、Spring 事务代理、主表 Mapper 和 MySQL。
 * Spy 只在精确故障点拦截第二次 Mapper 插入/Redis 写入，其余执行真实逻辑。
 * 运行开关：-DrunMysqlIT=true
 */
@EnabledIfSystemProperty(named = "runMysqlIT", matches = "true")
@SpringBootTest(classes = ShortLinkApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("integration-test")
class ShortLinkMysqlFaultInjectionTest {

    @Autowired private ShortLinkService linkService;
    @Autowired private ShortLinkMapper linkMapper;
    @SpyBean private ShortLinkGotoMapper gotoMapper;
    @SpyBean private StringRedisTemplate redis;
    @Value("${short-link.domain.default}") private String defaultDomain;

    private final AtomicReference<String> currentFull = new AtomicReference<>();
    private String currentGid;
    private static HttpServer localServer;
    private static String localOrigin;

    @BeforeAll
    static void beforeAll() throws Exception {
        localServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        localServer.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        localServer.start();
        localOrigin = "http://127.0.0.1:" + localServer.getAddress().getPort();
    }

    @AfterAll
    static void afterAll() {
        if (localServer != null) localServer.stop(0);
    }

    private ShortLinkCreateReqDTO request() {
        currentGid = "IT" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return ShortLinkCreateReqDTO.builder()
                .gid(currentGid)
                .domain(defaultDomain)
                .originUrl(localOrigin + "/fault-" + UUID.randomUUID())
                .createdType(1)
                .validDateType(0)
                .validDate(null)
                .describe("IT-MYSQL-FAULT")
                .build();
    }

    private void injectGotoInsertFailure() {
        doAnswer(invocation -> {
            ShortLinkGotoDO gotoEntity = invocation.getArgument(0);
            currentFull.set(gotoEntity.getFullShortUrl());
            throw new DuplicateKeyException("IT: 注入跳转表插入失败");
        }).when(gotoMapper).insert(any(ShortLinkGotoDO.class));
    }

    @SuppressWarnings("unchecked")
    private void injectRedisFailure() {
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        doReturn(operations).when(redis).opsForValue();
        doAnswer(invocation -> {
            String redisKey = invocation.getArgument(0);
            String prefix = String.format(RedisKeyConstant.GOTO_SHORT_LINK_KEY, "");
            if (redisKey.startsWith(prefix)) currentFull.set(redisKey.substring(prefix.length()));
            throw new RedisConnectionFailureException("IT: 注入 Redis 写入失败");
        }).when(operations).set(anyString(), anyString(), anyLong(), eq(TimeUnit.MILLISECONDS));
    }

    private void assertDatabaseEmpty() {
        assertNotNull(currentFull.get(), "测试未进入指定故障点，不能判定为回滚通过");
        assertEquals(0L, linkMapper.selectCount(Wrappers.<ShortLinkDO>lambdaQuery()
                .eq(ShortLinkDO::getGid, currentGid)
                .eq(ShortLinkDO::getFullShortUrl, currentFull.get())).longValue(),
                "主表仍存在事务异常留下的数据");
        assertEquals(0L, gotoMapper.selectCount(Wrappers.<ShortLinkGotoDO>lambdaQuery()
                .eq(ShortLinkGotoDO::getFullShortUrl, currentFull.get())).longValue(),
                "映射表仍存在事务异常留下的数据");
    }

    @AfterEach
    void cleanup() {
        // 先恢复 Spy 的真实行为，避免被注入故障影响清理。
        reset(gotoMapper, redis);
        if (currentFull.get() != null) {
            String full = currentFull.get();
            gotoMapper.delete(Wrappers.<ShortLinkGotoDO>lambdaQuery()
                    .eq(ShortLinkGotoDO::getFullShortUrl, full));
            redis.delete(String.format(RedisKeyConstant.GOTO_SHORT_LINK_KEY, full));
        }
        // 无论失败点是否触发，都只删除本次唯一 gid 对应的数据。
        if (currentGid != null) {
            List<ShortLinkDO> leftovers = linkMapper.selectList(
                    Wrappers.<ShortLinkDO>lambdaQuery().eq(ShortLinkDO::getGid, currentGid));
            for (ShortLinkDO link : leftovers) {
                gotoMapper.delete(Wrappers.<ShortLinkGotoDO>lambdaQuery()
                        .eq(ShortLinkGotoDO::getFullShortUrl, link.getFullShortUrl()));
                linkMapper.delete(Wrappers.<ShortLinkDO>lambdaQuery()
                        .eq(ShortLinkDO::getGid, currentGid)
                        .eq(ShortLinkDO::getFullShortUrl, link.getFullShortUrl()));
                redis.delete(String.format(RedisKeyConstant.GOTO_SHORT_LINK_KEY, link.getFullShortUrl()));
            }
        }
        currentFull.set(null);
        currentGid = null;
    }

    // A. 真正的普通创建：主表插入成功后注入第二张表故障。
    @Test
    void testA_normalServiceGotoFailureRollsBack() {
        ShortLinkCreateReqDTO request = request();
        injectGotoInsertFailure();
        assertThrows(RuntimeException.class, () -> linkService.createShortLink(request));
        assertDatabaseEmpty();
    }

    // B. 真正的普通创建：数据库写入后注入 Redis 故障。
    @Test
    void testB_normalServiceRedisFailureRollsBack() {
        ShortLinkCreateReqDTO request = request();
        injectRedisFailure();
        assertThrows(RedisConnectionFailureException.class,
                () -> linkService.createShortLink(request));
        assertDatabaseEmpty();
    }

    // C. 真实分布式锁创建：在映射表写入时注入故障。
    @Test
    void testC_lockedServiceGotoFailureRollsBack() {
        ShortLinkCreateReqDTO request = request();
        injectGotoInsertFailure();
        assertThrows(RuntimeException.class, () -> linkService.createShortLinkByLock(request));
        assertDatabaseEmpty();
    }

    // D. 真实分布式锁创建：两表插入后注入 Redis 故障。
    @Test
    void testD_lockedServiceRedisFailureRollsBack() {
        ShortLinkCreateReqDTO request = request();
        injectRedisFailure();
        assertThrows(RedisConnectionFailureException.class,
                () -> linkService.createShortLinkByLock(request));
        assertDatabaseEmpty();
    }
}
