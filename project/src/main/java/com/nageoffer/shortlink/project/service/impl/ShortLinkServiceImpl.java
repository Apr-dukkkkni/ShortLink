package com.nageoffer.shortlink.project.service.impl;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.text.StrBuilder;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import cn.hutool.core.lang.UUID;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.nageoffer.shortlink.project.common.convention.exception.ClientException;
import com.nageoffer.shortlink.project.common.convention.exception.ServiceException;
import com.nageoffer.shortlink.project.common.enums.ValidDateTypeEnum;
import com.nageoffer.shortlink.project.config.GotoDomainWhiteListConfiguration;
import com.nageoffer.shortlink.project.dao.entity.ShortLinkDO;
import com.nageoffer.shortlink.project.dao.entity.ShortLinkGotoDO;
import com.nageoffer.shortlink.project.dao.mapper.ShortLinkGotoMapper;
import com.nageoffer.shortlink.project.dao.mapper.ShortLinkMapper;
import com.nageoffer.shortlink.project.dto.biz.ShortLinkStatsRecordDTO;
import com.nageoffer.shortlink.project.dto.req.ShortLinkBatchCreateReqDTO;
import com.nageoffer.shortlink.project.dto.req.ShortLinkCreateReqDTO;
import com.nageoffer.shortlink.project.dto.req.ShortLinkPageReqDTO;
import com.nageoffer.shortlink.project.dto.req.ShortLinkUpdateReqDTO;
import com.nageoffer.shortlink.project.dto.resp.*;
import com.nageoffer.shortlink.project.mq.producer.ShortLinkStatsSaveProducer;
import com.nageoffer.shortlink.project.service.ShortLinkService;
import com.nageoffer.shortlink.project.toolkit.HashUtil;
import com.nageoffer.shortlink.project.toolkit.LinkUtil;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.nageoffer.shortlink.project.common.constant.RedisKeyConstant.*;


@Slf4j
@Service
@RequiredArgsConstructor
public class ShortLinkServiceImpl extends ServiceImpl<ShortLinkMapper,ShortLinkDO> implements ShortLinkService{

    private final GotoDomainWhiteListConfiguration gotoDomainWhiteListConfiguration;
    private final RBloomFilter<String> shortUriCreateCachePenetrationBloomFilter;
    private final RBloomFilter<String> shortUrlRBloomFilter;
    private final ShortLinkGotoMapper shortLinkGotoMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final ShortLinkStatsSaveProducer shortLinkStatsSaveProducer;


    @Value("${short-link.domain.default}")
    private  String createShortLinkDefaultDomain;


    @Override
    @Transactional(rollbackFor = Exception.class)


    public ShortLinkCreateRespDTO createShortLink(ShortLinkCreateReqDTO requestParam){
        verificationWhitelist(requestParam.getOriginUrl());

        String shortUrl = generateSuffix(requestParam);
        String  fullShortUrl = StrBuilder.create(createShortLinkDefaultDomain).append("/").append(shortUrl).toString();

        ShortLinkDO shortLinkDO = buildShortLinkDO(requestParam, shortUrl, fullShortUrl);

        //落库
        saveSingleShortLink(shortLinkDO, requestParam);

        return ShortLinkCreateRespDTO.builder()
                .fullShortUrl("http://"+fullShortUrl)
                .originUrl(requestParam.getOriginUrl())
                .gid(requestParam.getGid())
                .build();
    }

    public ShortLinkCreateRespDTO createShortLinkByLock(ShortLinkCreateReqDTO requestParam){
        verificationWhitelist(requestParam.getOriginUrl());

        String originUrl = requestParam.getOriginUrl();
        String lockKey = String.format("short-link:lock:create:%s",
                DigestUtils.md5DigestAsHex(originUrl.getBytes(StandardCharsets.UTF_8)));

        RLock lock = redissonClient.getLock(lockKey);
        Boolean isLocked = false;
        try{
            isLocked = lock.tryLock(3,30,TimeUnit.SECONDS);
            if(!isLocked){
                throw new ClientException("系统繁忙");
            }
            String shortUri = generateSuffixByLock(requestParam);
            String fullShortUri = StrBuilder.create(createShortLinkDefaultDomain).append("/").append(shortUri).toString();

            ShortLinkDO shortLinkDO = buildShortLinkDO(requestParam, shortUri, fullShortUri);
            ShortLinkGotoDO shortLinkGotoDO = ShortLinkGotoDO.builder()
                    .fullShortUrl(fullShortUri).gid(requestParam.getGid()).build();
            try {
                baseMapper.insert(shortLinkDO);
                shortLinkGotoMapper.insert(shortLinkGotoDO);
            }catch (DuplicateKeyException ex){
                throw new ServiceException(String.format("短链接：%s 生成重复，请重试", fullShortUri));
            }
            stringRedisTemplate.opsForValue().set(
                    String.format(GOTO_SHORT_LINK_KEY,fullShortUri),
                    requestParam.getOriginUrl(),
                    LinkUtil.getLinkCacheValidTime(requestParam.getValidDate()),
                    TimeUnit.MILLISECONDS
            );
            shortUrlRBloomFilter.add(fullShortUri);

            return ShortLinkCreateRespDTO.builder()
                    .fullShortUrl("http://" + fullShortUri)
                    .originUrl(requestParam.getOriginUrl())
                    .gid(requestParam.getGid())
                    .build();
        }catch (InterruptedException e){
            Thread.currentThread().interrupt();
            throw new ClientException("系统中断");
        }finally {
            if(isLocked && lock.isHeldByCurrentThread()){
                lock.unlock();
            }
        }


    }

    public ShortLinkBatchCreateRespDTO batchCreateShortLink(ShortLinkBatchCreateReqDTO requestParam){
        List<String> originUrls = requestParam.getOriginUrls();
        List<String> describes = requestParam.getDescribes();
        if(originUrls.size() != describes.size()){
            throw new ClientException("URL数量与描述数量不一致");
        }

        List<ShortLinkBaseInfoRespDTO> result = new ArrayList<>(originUrls.size());
        List<ShortLinkDO> insertlist = new ArrayList<>(originUrls.size());

        for(int i = 0 ; i < originUrls.size() ; i++){
            ShortLinkCreateReqDTO singleReq = BeanUtil.toBean(requestParam , ShortLinkCreateReqDTO.class);
            singleReq.setOriginUrl(originUrls.get(i));
            singleReq.setDescribe(describes.get(i));

            String shortUri = generateSuffix(singleReq);
            String fullShortUrl = StrBuilder.create(createShortLinkDefaultDomain).append("/").append(shortUri).toString();

            ShortLinkDO shortLinkDO = buildShortLinkDO(singleReq, shortUri, fullShortUrl);
            insertlist.add(shortLinkDO);

            result.add(ShortLinkBaseInfoRespDTO.builder()
                    .fullShortUrl(fullShortUrl)
                    .originUrl(originUrls.get(i))
                    .describe(describes.get(i))
                    .build());
        }
        if(!insertlist.isEmpty()){
            boolean success = this.saveBatch(insertlist,500);
            if(!success){
                throw new ServiceException("批量创建短链接失败");
            }
        }
        for (ShortLinkDO shortLinkDO : insertlist){
            stringRedisTemplate.opsForValue().set(
                    String.format(GOTO_SHORT_LINK_KEY, shortLinkDO.getFullShortUrl()),
                    shortLinkDO.getOriginUrl(),
                    LinkUtil.getLinkCacheValidTime(requestParam.getValidDate()),
                    TimeUnit.MILLISECONDS
            );
            shortUrlRBloomFilter.add(shortLinkDO.getFullShortUrl());
        }
        return ShortLinkBatchCreateRespDTO.builder()
                .total(result.size())
                .baseLinkInfos(result)
                .build();
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateShortLink(ShortLinkUpdateReqDTO requestParam){
        verificationWhitelist(requestParam.getOriginUrl());
        LambdaQueryWrapper<ShortLinkDO> queryWrapper = Wrappers.lambdaQuery(ShortLinkDO.class)
                .eq(ShortLinkDO::getGid, requestParam.getGid())
                .eq(ShortLinkDO::getFullShortUrl, requestParam.getFullShortUrl())
                .eq(ShortLinkDO::getDelFlag, 0)
                .eq(ShortLinkDO::getEnableStatus, 0);
        ShortLinkDO hasShortLinkDO = baseMapper.selectOne(queryWrapper);
        if(hasShortLinkDO == null){
            throw new ClientException("短链接不存在");
        }
        if (Objects.equals(hasShortLinkDO.getGid(), requestParam.getGid())){
            LambdaUpdateWrapper<ShortLinkDO> updateWrapper = Wrappers.lambdaUpdate(ShortLinkDO.class)
                    .eq(ShortLinkDO::getFullShortUrl, requestParam.getFullShortUrl())
                    .eq(ShortLinkDO::getGid, requestParam.getGid())
                    .eq(ShortLinkDO::getDelFlag, 0)
                    .eq(ShortLinkDO::getEnableStatus, 0)
                    .set(Objects.equals(requestParam.getValidDateType(), ValidDateTypeEnum.PERMANENT.getType()), ShortLinkDO::getValidDate, null);
            ShortLinkDO shortLinkDO = ShortLinkDO.builder()
                    .domain(hasShortLinkDO.getDomain())
                    .shortUri(hasShortLinkDO.getShortUri())
                    .favicon(Objects.equals(requestParam.getOriginUrl(),hasShortLinkDO.getOriginUrl()) ? hasShortLinkDO.getFavicon() : getFavicon(requestParam.getOriginUrl()))
                    .createdType(hasShortLinkDO.getCreatedType())
                    .gid(requestParam.getGid())
                    .originUrl(requestParam.getOriginUrl())
                    .describe(requestParam.getDescribe())
                    .validDateType(requestParam.getValidDateType())
                    .validDate(requestParam.getValidDate())
                    .build();
            baseMapper.update(shortLinkDO, updateWrapper);
        }else{
            RReadWriteLock rReadWriteLock = redissonClient.getReadWriteLock(String.format(LOCK_GID_UPDATE_KEY,requestParam.getFullShortUrl()));
            RLock rLock = rReadWriteLock.writeLock();
            rLock.lock();
            try{
                LambdaUpdateWrapper<ShortLinkDO> linkUpdateWrapper = Wrappers.lambdaUpdate(ShortLinkDO.class)
                        .eq(ShortLinkDO::getFullShortUrl, requestParam.getFullShortUrl())
                        .eq(ShortLinkDO::getGid, hasShortLinkDO.getGid())
                        .eq(ShortLinkDO::getDelFlag, 0)
                        .eq(ShortLinkDO::getDelTime, 0L)
                        .eq(ShortLinkDO::getEnableStatus, 0);
                ShortLinkDO delShortLinkDO = ShortLinkDO.builder()
                        .delTime(System.currentTimeMillis())
                        .build();
                delShortLinkDO.setDelFlag(1);//为什么同样都是delShortLinkDO的字段，但是要单独设置删除标志，而不是在上边的builder中一起设置？
                //因为delFlag的字段并不是ShortLinkDO的亲生字段，而是它继承的父类BaseDO
                //多表复用，规则统一，抽进BaseDO，业务字段下沉
                baseMapper.update(delShortLinkDO,linkUpdateWrapper);
                ShortLinkDO shortLinkDO = ShortLinkDO.builder()
                        .domain(createShortLinkDefaultDomain)
                        .originUrl(requestParam.getOriginUrl())
                        .gid(requestParam.getGid())
                        .createdType(hasShortLinkDO.getCreatedType())
                        .validDateType(requestParam.getValidDateType())
                        .validDate(requestParam.getValidDate())
                        .describe(requestParam.getDescribe())
                        .shortUri(hasShortLinkDO.getShortUri())
                        .enableStatus(hasShortLinkDO.getEnableStatus())
                        .totalPv(hasShortLinkDO.getTotalPv())
                        .totalUv(hasShortLinkDO.getTotalUv())
                        .totalUip(hasShortLinkDO.getTotalUip())
                        .fullShortUrl(hasShortLinkDO.getFullShortUrl())
                        .favicon(Objects.equals(requestParam.getOriginUrl(), hasShortLinkDO.getOriginUrl()) ? hasShortLinkDO.getFavicon() : getFavicon(requestParam.getOriginUrl()))
                        .delTime(0L)
                        .build();
                baseMapper.insert(shortLinkDO);
                LambdaQueryWrapper<ShortLinkGotoDO> linkGotoQueryWrapper = Wrappers.lambdaQuery(ShortLinkGotoDO.class)
                        .eq(ShortLinkGotoDO::getFullShortUrl, requestParam.getFullShortUrl())
                        .eq(ShortLinkGotoDO::getGid, hasShortLinkDO.getGid());
                ShortLinkGotoDO shortLinkGotoDO = shortLinkGotoMapper.selectOne(linkGotoQueryWrapper);
                shortLinkGotoMapper.delete(linkGotoQueryWrapper);
                shortLinkGotoDO.setGid(requestParam.getGid());
                shortLinkGotoMapper.insert(shortLinkGotoDO);
            }finally {
                rLock.unlock();
            }
        }
        if(!Objects.equals(hasShortLinkDO.getValidDateType(), requestParam.getValidDateType())
                || !Objects.equals(hasShortLinkDO.getValidDate(), requestParam.getValidDate())
                || !Objects.equals(hasShortLinkDO.getOriginUrl(), requestParam.getOriginUrl())){
            stringRedisTemplate.delete(String.format(GOTO_SHORT_LINK_KEY, requestParam.getFullShortUrl()));
            Date currentDate = new Date();
            if (hasShortLinkDO.getValidDate() != null && hasShortLinkDO.getValidDate().before(currentDate)){
                if(Objects.equals(requestParam.getValidDateType(), ValidDateTypeEnum.PERMANENT.getType())
                        || requestParam.getValidDate().after(currentDate)){
                    stringRedisTemplate.delete(String.format(GOTO_SHORT_LINK_KEY, requestParam.getFullShortUrl()));
                }
            }

        }
    }

    @Override
    public IPage<ShortLinkPageRespDTO> pageShortLink(ShortLinkPageReqDTO requestParam){
        //`<ShortLinkPageRespDTO>`：泛型，代表分页里面**每一条数据封装成 ShortLinkPageRespDTO**
        IPage<ShortLinkDO> resultPage = baseMapper.pageLink(requestParam);
        return resultPage.convert(each -> {
            ShortLinkPageRespDTO result = BeanUtil.toBean(each, ShortLinkPageRespDTO.class);
            result.setDomain("http://" + result.getDomain());
            return result;
        });
    }

    @Override
    public List<ShortLinkGroupCountQueryRespDTO> listGroupShortLinkCount(List<String> requestParam){
        QueryWrapper<ShortLinkDO> queryWrapper = Wrappers.query(new ShortLinkDO())
                .select("gid as gid, count(*) as shortLinkCount")
                .in("gid", requestParam)
                .eq("enable_status", 0)
                .eq("del_flag", 0)
                .eq("del_time", 0L)
                .groupBy("gid");
        List<Map<String, Object>> shortLinkDOList = baseMapper.selectMaps(queryWrapper);
        return BeanUtil.copyToList(shortLinkDOList,ShortLinkGroupCountQueryRespDTO.class);

    }


    @SneakyThrows
    @Override
    public void restoreUrl(String shortUri, ServletRequest request, ServletResponse response){
        //`ServletRequest` 是**Java Web 最顶层请求接口**，代表**浏览器发给服务器的一次 HTTP 请求对象**。
        String serverName = request.getServerName();
        String serverPort = Optional.of(request.getServerPort())
                .filter(each -> !Objects.equals(each, 80) && !Objects.equals(each,443))
                .map(String::valueOf)
                .map(each -> ":" + each)
                .orElse("");
        //等价于
//        int port = request.getServerPort();
//        String serverPort;
//        if (port == 80 || port == 443){
//            serverPort = "";
//        }else{
//            serverPort = ":" + port;
//        }
        String fullShortUrl = serverName + serverPort + "/" + shortUri;
        //redis 缓存
        String originalLink = stringRedisTemplate.opsForValue().get(String.format(GOTO_SHORT_LINK_KEY,fullShortUrl));
        if (StrUtil.isNotBlank((originalLink))){
            shortLinkStats((buildLinkStatsRecordAndSetUser(fullShortUrl,request,response)));
            ((HttpServletResponse) response).sendRedirect(originalLink);
            return;
        }
        //Redis空值缓存
        String gotoIsNullShortLink = stringRedisTemplate.opsForValue().get(String.format(GOTO_IS_NULL_SHORT_LINK_KEY, fullShortUrl));
        if (StrUtil.isNotBlank(gotoIsNullShortLink)) {
//            在短链接系统中，如果用户访问了一个不存在的短码，直接返回一个冷冰冰的 404 页面体验很差。所以通常会：
//            服务器返回 302 重定向。
//            浏览器跳转到一个友好的提示页面（/page/notfound），比如“该链接已失效，请重新获取”。
//            这个页面通常返回 200 状态码，但告诉用户链接不存在。
            ((HttpServletResponse) response).sendRedirect("/page/notfound");
            return;
        }
        RLock lock = redissonClient.getLock(String.format(LOCK_GOTO_SHORT_LINK_KEY, fullShortUrl));
        lock.lock();
        try{
            originalLink = stringRedisTemplate.opsForValue().get(String.format(GOTO_SHORT_LINK_KEY, fullShortUrl));
            if (StrUtil.isNotBlank(originalLink)){
                shortLinkStats(buildLinkStatsRecordAndSetUser(fullShortUrl, request, response));
                ((HttpServletResponse) response).sendRedirect(originalLink);
                return;
            }
            gotoIsNullShortLink = stringRedisTemplate.opsForValue().get(String.format(GOTO_IS_NULL_SHORT_LINK_KEY, fullShortUrl));
            if (StrUtil.isNotBlank(gotoIsNullShortLink)){
                ((HttpServletResponse) response).sendRedirect("/page/notfound");
                return;
            }
//            第一层校验“短码真伪（是否存在路由）”；
//
//            第二层校验“短码状态（是否过期/被删）”
            LambdaQueryWrapper<ShortLinkGotoDO> linkGotoQueryWrapper = Wrappers.lambdaQuery(ShortLinkGotoDO.class)
                    .eq(ShortLinkGotoDO::getFullShortUrl, fullShortUrl);
            ShortLinkGotoDO shortLinkGotoDO = shortLinkGotoMapper.selectOne(linkGotoQueryWrapper);
            if(shortLinkGotoDO == null ){
                stringRedisTemplate.opsForValue().set(String.format(GOTO_IS_NULL_SHORT_LINK_KEY,fullShortUrl), "-", 30, TimeUnit.MINUTES);
                ((HttpServletResponse) response).sendRedirect("/page/notfound");
                return;
            }
            LambdaQueryWrapper<ShortLinkDO> queryWrapper = Wrappers.lambdaQuery(ShortLinkDO.class)
                    .eq(ShortLinkDO::getGid, shortLinkGotoDO.getGid())
                    .eq(ShortLinkDO::getFullShortUrl, fullShortUrl)
                    .eq(ShortLinkDO::getDelFlag, 0)
                    .eq(ShortLinkDO::getEnableStatus, 0);
            ShortLinkDO shortLinkDO = baseMapper.selectOne(queryWrapper);
            if (shortLinkDO == null || (shortLinkDO.getValidDate() != null && shortLinkDO.getValidDate().before(new Date()))){
                stringRedisTemplate.opsForValue().set(String.format(GOTO_IS_NULL_SHORT_LINK_KEY, fullShortUrl), "-", 30 ,TimeUnit.MINUTES);
                ((HttpServletResponse) response).sendRedirect("/page/notfound");
                return;
            }
            stringRedisTemplate.opsForValue().set(
                    String.format(GOTO_SHORT_LINK_KEY,fullShortUrl),
                    shortLinkDO.getOriginUrl(),
                    LinkUtil.getLinkCacheValidTime(shortLinkDO.getValidDate()),
                    TimeUnit.MILLISECONDS
            );
        }finally {
                lock.unlock();
        }
    }


    private ShortLinkStatsRecordDTO buildLinkStatsRecordAndSetUser(String fullShortUrl, ServletRequest request, ServletResponse response){
        AtomicBoolean uvFirstFlag = new AtomicBoolean();
        Cookie[] cookies = ((HttpServletRequest) request).getCookies();
        AtomicReference<String> uv = new AtomicReference<>();
        //只是【定义变量 + 定义一个 Runnable 任务】，没有执行、没有判断
        Runnable addResponseCookieTask = () -> {
            uv.set(UUID.fastUUID().toString());
            Cookie uvCookie = new Cookie("uv", uv.get());
            uvCookie.setMaxAge(60*60*24*30);
            uvCookie.setPath(StrUtil.sub(fullShortUrl, fullShortUrl.indexOf("/"), fullShortUrl.length()));
            ((HttpServletResponse) response).addCookie(uvCookie);
            uvFirstFlag.set(Boolean.TRUE);
            stringRedisTemplate.opsForSet().add(SHORT_LINK_STATS_UV_KEY + fullShortUrl, uv.get());
        };
        if(ArrayUtil.isNotEmpty(cookies)) {
            Arrays.stream(cookies)
                    .filter(each -> Objects.equals(each.getName(), "uv"))
//            1. `"uv"`（字符串常量，filter 这里用的）
//            Cookie 的**名字**，写死的，永远不变，代表我们定义的 Cookie 的 key。
//            2. `AtomicReference<String> uv uv = new AtomicReference<>();`（变量）
//            用来存放 Cookie 的**value**，也就是 UUID 访客编号，这个是动态变化的。
                    .findFirst()
                    .map(Cookie::getValue)
                    .ifPresentOrElse(each -> {
                        uv.set(each);
                        //uvAdded = 1新访客 0 旧访客
                        Long uvAdded = stringRedisTemplate.opsForSet().add(SHORT_LINK_STATS_UV_KEY + fullShortUrl, each);
                        uvFirstFlag.set(uvAdded != null && uvAdded > 0L);
                        //满足set里边的条件，uvFirstFlag = true
                    }, addResponseCookieTask); //有Cookie数组，但是Cookie里面找不到uv
        }else{
            addResponseCookieTask.run();//cookies == null，浏览器本次请求**完全没有携带任何Cookie**
        }
        String remoteAddr = LinkUtil.getActualIp((HttpServletRequest) request);
        String os = LinkUtil.getOs(((HttpServletRequest) request));
        String browser = LinkUtil.getBrowser((HttpServletRequest) request);
        String device = LinkUtil.getDevice((HttpServletRequest) request);
        String network = LinkUtil.getNetwork((HttpServletRequest) request);
        Long uipAdded = stringRedisTemplate.opsForSet().add(SHORT_LINK_STATS_UIP_KEY + fullShortUrl, remoteAddr);
        boolean uipFirstFlag = uipAdded != null && uipAdded >0L;
        return ShortLinkStatsRecordDTO.builder()
                .fullShortUrl(fullShortUrl)
                .uv(uv.get())
                .uvFirstFlag(uvFirstFlag.get())
                .uipFirstFlag(uipFirstFlag)
                .remoteAddr(remoteAddr)
                .os(os)
                .browser(browser)
                .device(device)
                .network(network)
                .currentDate(new Date())
                .build();



    }


    private String generateSuffix (ShortLinkCreateReqDTO requestParam){
        int customGenerateCount = 0;
        String shortUri;
        while (true) {
            if (customGenerateCount > 10) {
                throw new ServiceException("短链接频繁生成，请稍后再试");
            }
            String originUrl = requestParam.getOriginUrl();
            originUrl += UUID.randomUUID().toString();
            // 短链接哈希算法生成冲突问题如何解决？详情查看：https://nageoffer.com/shortlink/question
            shortUri = HashUtil.hashToBase62(originUrl);
            // 判断短链接是否存在为什么不使用Set结构？详情查看：https://nageoffer.com/shortlink/question
            // 如果布隆过滤器挂了，里边存的数据全丢失了，怎么恢复呢？详情查看：https://nageoffer.com/shortlink/question
            if (!shortUriCreateCachePenetrationBloomFilter.contains(createShortLinkDefaultDomain + "/" + shortUri)) {
                break;
            }
            customGenerateCount++;
        }
        return shortUri;
    }

    private String generateShortUrl(String originUrl){
        int customGenerateCount = 0;
        String shortUrl;
        while(true){
            if(customGenerateCount >= 10){
                throw new RuntimeException("业务繁忙");
            }
            String urlWithSalt = originUrl + UUID.randomUUID().toString();
            shortUrl = HashUtil.hashToBase62(createShortLinkDefaultDomain + "/" + originUrl);
            if(!shortUriCreateCachePenetrationBloomFilter.contains(shortUrl)){
                break;
            }
            customGenerateCount++;
        }
        return shortUrl;
    }

    @Override
    public void shortLinkStats(ShortLinkStatsRecordDTO statsRecord){
        Map<String, String> producerMap = new HashMap<>();
        producerMap.put("statsRecord", JSON.toJSONString(statsRecord));
        shortLinkStatsSaveProducer.send(producerMap);
    }


    private String generateSuffixByLock(ShortLinkCreateReqDTO requestParam){
        int count = 0;
        String shortUri;
        while(true){
            if(count > 10){
                throw new ServiceException("请求频繁。");
            }
            String originUrl = requestParam.getOriginUrl();
            String temp = originUrl + UUID.randomUUID().toString();
            shortUri = HashUtil.hashToBase62(temp);
            if(!shortUrlRBloomFilter.contains(createShortLinkDefaultDomain + "/" + shortUri)){
                break;
            }
            count++;
        }
        return shortUri;
    }


    @SneakyThrows
    private String getFavicon (String url){
        URL targetUrl = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) targetUrl.openConnection();
        connection.setRequestMethod("GET");
        int ahc = connection.getResponseCode();
        if(HttpURLConnection.HTTP_OK == ahc){
            Document document = Jsoup.connect(url).get();
            Element find = document.select("link [rel~=(?!)^(shortcut )?icon").first();
            if(find != null){
                return find.attr("abs:href");
            }
        }
        return null;
    }



    private ShortLinkDO buildShortLinkDO (ShortLinkCreateReqDTO requestParam, String shortUrl, String fullShortUrl){
        return ShortLinkDO.builder().domain(createShortLinkDefaultDomain)
                .originUrl(requestParam.getOriginUrl())
                .gid(requestParam.getGid())
                .createdType(requestParam.getCreatedType())
                .validDateType(requestParam.getValidDateType())
                .validDate(requestParam.getValidDate())
                .describe(requestParam.getDescribe())
                .shortUri(shortUrl)
                .enableStatus(0)
                .todayPv(0)
                .totalUv(0)
                .totalUip(0)
                .delTime(0L)
                .fullShortUrl(fullShortUrl)
                .favicon(getFavicon(requestParam.getOriginUrl()))
                .build();
    }



    private void verificationWhitelist(String original){
        if(StrUtil.isBlank(original)){
            throw new RuntimeException("输入的链接有误");
        }
        Boolean enable = gotoDomainWhiteListConfiguration.getEnable();
        if(enable == null || !enable ){
            return ;
        }
        String domain = LinkUtil.extractDomain(original);
        List<String> whiteList = gotoDomainWhiteListConfiguration.getDetails();
        if(StrUtil.isBlank(domain) && whiteList.contains(domain)){
            String allowName = gotoDomainWhiteListConfiguration.getNames();
            throw new RuntimeException("只可以跳转如下名单：" + allowName);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    private void saveSingleShortLink(ShortLinkDO shortLinkDO, ShortLinkCreateReqDTO requestParam){
        ShortLinkGotoDO shortLinkGotoDO = ShortLinkGotoDO.builder()
                .fullShortUrl(shortLinkDO.getFullShortUrl())
                .gid(requestParam.getGid()).build();

        try {
            baseMapper.insert(shortLinkDO);
            shortLinkGotoMapper.insert(shortLinkGotoDO);
        }catch (RuntimeException ex){
            throw new ServiceException(String.format("短链接：%s 生成重复，请重试", shortLinkDO.getFullShortUrl()));
        }

        stringRedisTemplate.opsForValue().set(
                String.format(GOTO_SHORT_LINK_KEY , shortLinkDO.getFullShortUrl()),
                requestParam.getOriginUrl(),
                LinkUtil.getLinkCacheValidTime(requestParam.getValidDate()),
                TimeUnit.MILLISECONDS
        );
        shortUrlRBloomFilter.add(shortLinkDO.getShortUri());
    }

}