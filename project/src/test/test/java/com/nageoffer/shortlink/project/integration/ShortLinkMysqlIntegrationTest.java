package com.nageoffer.shortlink.project.integration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.shortlink.project.ShortLinkApplication;
import com.nageoffer.shortlink.project.common.constant.RedisKeyConstant;
import com.nageoffer.shortlink.project.dao.entity.ShortLinkDO;
import com.nageoffer.shortlink.project.dao.entity.ShortLinkGotoDO;
import com.nageoffer.shortlink.project.dao.mapper.ShortLinkGotoMapper;
import com.nageoffer.shortlink.project.dao.mapper.ShortLinkMapper;
import com.nageoffer.shortlink.project.dto.req.ShortLinkCreateReqDTO;
import com.nageoffer.shortlink.project.dto.resp.ShortLinkCreateRespDTO;
import com.nageoffer.shortlink.project.service.ShortLinkService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真正使用 application.yaml 对应的 MySQL/ShardingSphere、MyBatis Mapper、Redis。
 * 只有显式执行 -DrunMysqlIT=true 时才允许访问真实数据库。
 * 不要给本类添加 @Transactional，否则测试框架的自动回滚会掩盖生产事务缺陷。
 */
@EnabledIfSystemProperty(named = "runMysqlIT", matches = "true")
@SpringBootTest(classes = ShortLinkApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
class ShortLinkMysqlIntegrationTest {

    @Autowired private ShortLinkMapper linkMapper;
    @Autowired private ShortLinkGotoMapper gotoMapper;
    @Autowired private ShortLinkService linkService;
    @Autowired private StringRedisTemplate redis;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MockMvc mockMvc;

    @Value("${short-link.domain.default}")
    private String defaultDomain;

    // 只记录当前用例自己生成的完整短链、gid 和测试 Redis key。
    private final Map<String, String> urlsAndGids = new LinkedHashMap<>();
    private final Set<String> redisKeys = new LinkedHashSet<>();
    private static HttpServer localServer;
    private static String localOrigin;

    @BeforeAll
    static void startLocalHttpServer() throws Exception {
        localServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        localServer.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        localServer.start();
        localOrigin = "http://127.0.0.1:" + localServer.getAddress().getPort();
    }

    @AfterAll
    static void closeLocalHttpServer() {
        if (localServer != null) localServer.stop(0);
    }

    /**
     * 即使用例抛异常，也只精确删除本用例记录的 URL；绝不清空业务表或 Redis 库。
     */
    @AfterEach
    void cleanup() {
        for (Map.Entry<String, String> entry : urlsAndGids.entrySet()) {
            String full = entry.getKey();
            String gid = entry.getValue();
            // 反向清理映射表，然后清理主表；两个查询均包含分片键。
            gotoMapper.delete(Wrappers.<ShortLinkGotoDO>lambdaQuery()
                    .eq(ShortLinkGotoDO::getFullShortUrl, full));
            linkMapper.delete(Wrappers.<ShortLinkDO>lambdaQuery()
                    .eq(ShortLinkDO::getGid, gid)
                    .eq(ShortLinkDO::getFullShortUrl, full));
            redisKeys.add(String.format(RedisKeyConstant.GOTO_SHORT_LINK_KEY, full));
            redisKeys.add(RedisKeyConstant.SHORT_LINK_STATS_UV_KEY + full);
            redisKeys.add(RedisKeyConstant.SHORT_LINK_STATS_UIP_KEY + full);
        }
        for (String key : redisKeys) redis.delete(key);
        urlsAndGids.clear();
        redisKeys.clear();
    }

    private String gid() {
        return "IT" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private ShortLinkDO link(String gid) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String full = defaultDomain + "/" + suffix;
        urlsAndGids.put(full, gid);
        ShortLinkDO link = ShortLinkDO.builder()
                .domain(defaultDomain)
                .shortUri(suffix)
                .fullShortUrl(full)
                .originUrl(localOrigin + "/" + suffix)
                .gid(gid)
                .enableStatus(0)
                .createdType(1)
                .validDateType(0)
                .validDate(null)
                .describe("IT-MYSQL-" + suffix)
                .totalPv(0)
                .totalUv(0)
                .totalUip(0)
                .delTime(0L)
                .build();
        link.setDelFlag(0);
        link.setCreateTime(new Date());
        link.setUpdateTime(new Date());
        return link;
    }

    private ShortLinkGotoDO gotoEntity(ShortLinkDO link) {
        return ShortLinkGotoDO.builder()
                .gid(link.getGid())
                .fullShortUrl(link.getFullShortUrl())
                .build();
    }

    private long countMain(ShortLinkDO link) {
        return linkMapper.selectCount(Wrappers.<ShortLinkDO>lambdaQuery()
                .eq(ShortLinkDO::getGid, link.getGid())
                .eq(ShortLinkDO::getFullShortUrl, link.getFullShortUrl()));
    }

    private long countGoto(ShortLinkDO link) {
        return gotoMapper.selectCount(Wrappers.<ShortLinkGotoDO>lambdaQuery()
                .eq(ShortLinkGotoDO::getFullShortUrl, link.getFullShortUrl()));
    }

    private void insertPair(ShortLinkDO link) {
        assertEquals(1, linkMapper.insert(link));
        assertEquals(1, gotoMapper.insert(gotoEntity(link)));
    }

    private void assertNoRows(ShortLinkDO link) {
        assertEquals(0L, countMain(link), "主表存在异常残留记录");
        assertEquals(0L, countGoto(link), "跳转映射表存在异常残留记录");
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    // 1. 真正运行 MyBatis Mapper + ShardingSphere；事务提交后两张表都有记录。
    @Test
    void test01_commitBothShardedTables() {
        ShortLinkDO link = link(gid());
        tx().execute(status -> { insertPair(link); return null; });
        assertEquals(1L, countMain(link));
        assertEquals(1L, countGoto(link));
    }

    // 2. 主表重复键：MySQL 真实唯一索引拒绝第二次插入。
    @Test
    void test02_realMainTableDuplicateKey() {
        ShortLinkDO first = link(gid());
        assertEquals(1, linkMapper.insert(first));
        // 第二个对象使用同一个完整链接和 gid，但不同 id；冲突发生在唯一键。
        ShortLinkDO duplicate = link(first.getGid());
        String unusedUrl = duplicate.getFullShortUrl();
        urlsAndGids.remove(unusedUrl);
        duplicate.setFullShortUrl(first.getFullShortUrl());
        duplicate.setShortUri(first.getShortUri());
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> tx().execute(status -> { linkMapper.insert(duplicate); return null; }));
        assertNotNull(error);
        assertEquals(1L, countMain(first));
        assertEquals(0L, countGoto(first));
    }

    // 3. 第二张表已有同一短链：主表新插入后遇到真实唯一键冲突，必须回滚。
    @Test
    void test03_realGotoDuplicateRollsBackMain() {
        ShortLinkDO link = link(gid());
        assertEquals(1, gotoMapper.insert(gotoEntity(link))); // 预先存在的测试映射
        assertThrows(RuntimeException.class, () -> tx().execute(status -> {
            linkMapper.insert(link);
            gotoMapper.insert(gotoEntity(link)); // 必定触发唯一键冲突
            return null;
        }));
        assertEquals(0L, countMain(link), "第二张表失败后主表没有回滚");
        assertEquals(1L, countGoto(link), "原本存在的测试映射不应被回滚");
    }

    // 4. 两张表插入后抛业务异常：Spring 真实事务必须回滚两张物理分表。
    @Test
    void test04_runtimeExceptionRollsBackBothTables() {
        ShortLinkDO link = link(gid());
        assertThrows(IllegalStateException.class, () -> tx().execute(status -> {
            insertPair(link);
            throw new IllegalStateException("IT: 模拟业务异常");
        }));
        assertNoRows(link);
    }

    // 5. 模拟 Redis 写入异常；验证数据库事务能否回滚，不涉及篡改真正的 Redis 连接。
    @Test
    void test05_redisFailureRollbackContract() {
        ShortLinkDO link = link(gid());
        assertThrows(RedisConnectionFailureException.class, () -> tx().execute(status -> {
            insertPair(link);
            throw new RedisConnectionFailureException("IT: 模拟 Redis 故障");
        }));
        assertNoRows(link);
    }

    // 6. 不同 gid 的主表分片路由；映射表根据短链 URL 路由。
    @Test
    void test06_twoDifferentGidsRouteCorrectly() {
        ShortLinkDO a = link(gid());
        ShortLinkDO b = link(gid());
        assertNotEquals(a.getGid(), b.getGid());
        tx().execute(status -> { insertPair(a); insertPair(b); return null; });
        assertEquals(1L, countMain(a));
        assertEquals(1L, countMain(b));
        assertEquals(1L, countGoto(a));
        assertEquals(1L, countGoto(b));
    }

    // 7. 真正访问当前 Redis，测试读写和过期时间。
    @Test
    void test07_realRedisReadWriteWithTtl() {
        String key = "short-link:it:mysql:" + UUID.randomUUID();
        redisKeys.add(key);
        redis.opsForValue().set(key, "connected", Duration.ofMinutes(2));
        assertEquals("connected", redis.opsForValue().get(key));
        Long ttl = redis.getExpire(key);
        assertNotNull(ttl);
        assertTrue(ttl > 0, "Redis TTL 应为正数");
    }

    private ShortLinkCreateRespDTO createByRealService(boolean byLock) {
        String gid = gid();
        String origin = localOrigin + "/service-" + UUID.randomUUID();
        ShortLinkCreateReqDTO request = ShortLinkCreateReqDTO.builder()
                .domain(defaultDomain)
                .originUrl(origin)
                .gid(gid)
                .createdType(1)
                .validDateType(0)
                .validDate(null)
                .describe("IT-MYSQL-SERVICE")
                .build();
        ShortLinkCreateRespDTO response = byLock
                ? linkService.createShortLinkByLock(request)
                : linkService.createShortLink(request);
        assertNotNull(response);
        String full = response.getFullShortUrl().replaceFirst("^https?://", "");
        urlsAndGids.put(full, gid);
        redisKeys.add(String.format(RedisKeyConstant.GOTO_SHORT_LINK_KEY, full));
        assertEquals(1L, linkMapper.selectCount(Wrappers.<ShortLinkDO>lambdaQuery()
                .eq(ShortLinkDO::getGid, gid)
                .eq(ShortLinkDO::getFullShortUrl, full)).longValue());
        assertEquals(1L, gotoMapper.selectCount(Wrappers.<ShortLinkGotoDO>lambdaQuery()
                .eq(ShortLinkGotoDO::getFullShortUrl, full)).longValue());
        assertEquals(origin, redis.opsForValue().get(
                String.format(RedisKeyConstant.GOTO_SHORT_LINK_KEY, full)));
        return response;
    }

    // 8. Spring 代理真实执行 createShortLink，并用 MockMvc 验证缓存命中后的 HTTP 302。
    @Test
    void test08_realServiceCreateAndHttpRedirect() throws Exception {
        ShortLinkCreateRespDTO response = createByRealService(false);
        String full = response.getFullShortUrl().replaceFirst("^https?://", "");
        String suffix = full.substring(full.lastIndexOf('/') + 1);
        String origin = response.getOriginUrl();
        // 关键：MockMvc 默认 serverName=localhost, 与短链保存域名不同。
        // 显式设置域名和端口，否则请求会命中不存在的短链。
        String[] hostPort = defaultDomain.split(":", 2);
        mockMvc.perform(get("/" + suffix).with(request -> {
                    request.setServerName(hostPort[0]);
                    request.setServerPort(hostPort.length == 2 ? Integer.parseInt(hostPort[1]) : 80);
                    return request;
                }))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", origin));
    }

    // 9. Spring 代理 + 真实 Redisson 分布式锁 + MySQL 两表 + Redis。
    @Test
    void test09_realServiceByLockCreatesConsistentData() {
        createByRealService(true);
    }
}
