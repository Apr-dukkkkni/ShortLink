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

import com.nageoffer.shortlink.project.toolkit.LinkUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShortLinkWhitelistTest {

    @Mock
    private GotoDomainWhiteListConfiguration configuration;

    @InjectMocks
    private ShortLinkServiceImpl shortLinkService;

    // 调用 Service 中的私有白名单校验方法
    private void checkWhitelist(String url) {
        ReflectionTestUtils.invokeMethod(
                shortLinkService,
                "verificationWhitelist",
                url
        );
    }

    // 验证请求被拒绝，并且有合理的错误提示
    private void assertRejected(String url) {

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> checkWhitelist(url),
                "预期应该拒绝：" + url
        );

        String message = exception.getMessage();

        assertNotNull(
                message,
                "拒绝请求时应该提供错误信息"
        );

        assertTrue(
                message.contains("名单")
                        || message.contains("域名")
                        || message.contains("链接"),
                "错误提示不合理：" + message
        );
    }

    // ===================================
    // 1. 白名单开启，但允许列表为空
    // ===================================

    @Test
    void test01_WhitelistEnabled() {

        when(configuration.getEnable())
                .thenReturn(true);

        when(configuration.getDetails())
                .thenReturn(Collections.emptyList());

        // 开启白名单但没有允许域名，应拒绝
        assertRejected("https://www.baidu.com");
    }

    // ===================================
    // 2. 白名单关闭
    // ===================================

    @Test
    void test02_WhitelistDisabled() {

        when(configuration.getEnable())
                .thenReturn(false);

        // 关闭白名单后，不应限制目标域名
        assertDoesNotThrow(
                () -> checkWhitelist(
                        "https://www.example.com"
                )
        );

        // 不应该再读取白名单内容
        verify(configuration, never()).getDetails();
    }

    // ===================================
    // 3. 允许域名
    // ===================================


    @Test
    void test03_AllowedDomain() {

        String url = "https://www.baidu.com";

        // 使用项目实际的域名提取规则
        String extractedDomain = LinkUtil.extractDomain(url);

        System.out.println("原始URL：" + url);
        System.out.println("提取域名：" + extractedDomain);

        assertNotNull(extractedDomain);
        assertFalse(extractedDomain.isBlank());

        when(configuration.getEnable())
                .thenReturn(true);

        when(configuration.getDetails())
                .thenReturn(List.of(extractedDomain));

        // 提取后的域名已经存在于白名单中，应放行
        assertDoesNotThrow(
                () -> checkWhitelist(url)
        );
    }


    // ===================================
    // 4. 禁止域名
    // ===================================

    @Test
    void test04_BlockedDomain() {

        when(configuration.getEnable())
                .thenReturn(true);

        when(configuration.getDetails())
                .thenReturn(
                        List.of("www.baidu.com", "www.qq.com")
                );

        // 不在白名单中，应该拒绝
        assertRejected("https://www.example.com");
    }

    // ===================================
    // 5. 域名为空
    // ===================================

    @Test
    void test05_EmptyDomain() {

        when(configuration.getEnable())
                .thenReturn(true);

        // 存在协议，但没有主机名
        // 应该明确拒绝，而不是空指针异常
        assertRejected("https://");
    }
}
