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

package com.nageoffer.shortlink.project.controller;

import com.nageoffer.shortlink.project.dto.req.ShortLinkCreateReqDTO;
import com.nageoffer.shortlink.project.dto.resp.ShortLinkCreateRespDTO;
import com.nageoffer.shortlink.project.service.ShortLinkService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.MediaType;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ShortLinkControllerTest {

    private static final String API =
            "/api/short-link/v1/create";

    private static final String URL =
            "https://www.baidu.com";

    private static final String GID = "XMQIfR";

    private static final String FUTURE =
            "2099-01-01 00:00:00";

    private static final String PAST =
            "2020-01-01 00:00:00";

    @Mock
    private ShortLinkService shortLinkService;

    @InjectMocks
    private ShortLinkController controller;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    @BeforeEach
    void setUp() {

        // 使用真正的 Bean Validation
        LocalValidatorFactoryBean validator =
                new LocalValidatorFactoryBean();

        validator.afterPropertiesSet();

        // 测试实际 Controller，但隔离 Service
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setValidator(validator)
                .build();
    }

    // =====================================
    // 构造测试 JSON
    // =====================================

    private String json(
            String originUrl,
            String gid,
            Integer validDateType,
            String validDate) {

        ObjectNode node = objectMapper.createObjectNode();

        node.put("domain", "nurl.ink:8001");
        node.put("createdType", 1);
        node.put("describe", "Controller自动化测试");

        if (originUrl == null) {
            node.putNull("originUrl");
        } else {
            node.put("originUrl", originUrl);
        }

        if (gid == null) {
            node.putNull("gid");
        } else {
            node.put("gid", gid);
        }

        if (validDateType == null) {
            node.putNull("validDateType");
        } else {
            node.put("validDateType", validDateType);
        }

        if (validDate == null) {
            node.putNull("validDate");
        } else {
            node.put("validDate", validDate);
        }

        return node.toString();
    }

    // =====================================
    // 批量非法参数测试数据
    // =====================================

    static Stream<Arguments> invalidCases() {
        return Stream.of(

                // Bug-001：URL 校验
                Arguments.of(
                        "URL为null",
                        null, GID, 0, null
                ),

                Arguments.of(
                        "URL为空",
                        "", GID, 0, null
                ),

                Arguments.of(
                        "URL格式错误",
                        "abc123", GID, 0, null
                ),

                Arguments.of(
                        "URL缺少域名",
                        "http://", GID, 0, null
                ),

                Arguments.of(
                        "不支持FTP协议",
                        "ftp://example.com", GID, 0, null
                ),

                Arguments.of(
                        "URL超过1024字符",
                        "https://example.com/" + "a".repeat(1100),
                        GID, 0, null
                ),

                // Bug-002：分组校验
                Arguments.of(
                        "gid为null",
                        URL, null, 0, null
                ),

                Arguments.of(
                        "gid为空字符串",
                        URL, "", 0, null
                ),

                Arguments.of(
                        "gid只有空格",
                        URL, "   ", 0, null
                ),

                // Bug-003：有效期校验
                Arguments.of(
                        "有效期类型为null",
                        URL, GID, null, null
                ),

                Arguments.of(
                        "有效期类型为-1",
                        URL, GID, -1, null
                ),

                Arguments.of(
                        "有效期类型为2",
                        URL, GID, 2, null
                ),

                Arguments.of(
                        "自定义有效期为空",
                        URL, GID, 1, null
                ),

                Arguments.of(
                        "自定义有效期为过去时间",
                        URL, GID, 1, PAST
                ),

                Arguments.of(
                        "永久有效却指定过期时间",
                        URL, GID, 0, FUTURE
                )
        );
    }

    // =====================================
    // 15组非法参数：全部应返回400
    // =====================================

    @ParameterizedTest(name = "{index}. {0}")
    @MethodSource("invalidCases")
    void shouldRejectInvalidRequest(
            String name,
            String originUrl,
            String gid,
            Integer validDateType,
            String validDate) throws Exception {

        String body = json(
                originUrl,
                gid,
                validDateType,
                validDate
        );

        mockMvc.perform(
                        post(API)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                )
                .andExpect(status().isBadRequest());

        // 关键断言：
        // 参数非法时，不允许进入创建业务
        verifyNoInteractions(shortLinkService);
    }

    // =====================================
    // 非法JSON：同样不允许进入Service
    // =====================================

    @Test
    void shouldRejectMalformedJson() throws Exception {

        mockMvc.perform(
                        post(API)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{invalid-json")
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(shortLinkService);
    }

    // =====================================
    // 正常情况1：永久有效
    // =====================================

    @Test
    void shouldCreatePermanentShortLink()
            throws Exception {

        mockSuccessResponse();

        String body = json(
                URL, GID, 0, null
        );

        mockMvc.perform(
                        post(API)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("0"));

        // 合法请求应该进入Service
        verify(shortLinkService, times(1))
                .createShortLink(argThat(req ->
                        req != null
                                && Integer.valueOf(0).equals(
                                req.getValidDateType())
                                && req.getValidDate() == null
                ));
    }

    // =====================================
    // 正常情况2：自定义未来时间
    // =====================================

    @Test
    void shouldCreateCustomValidShortLink()
            throws Exception {

        mockSuccessResponse();

        String body = json(
                URL, GID, 1, FUTURE
        );

        mockMvc.perform(
                        post(API)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("0"));

        verify(shortLinkService, times(1))
                .createShortLink(argThat(req ->
                        req != null
                                && Integer.valueOf(1).equals(
                                req.getValidDateType())
                                && req.getValidDate() != null
                ));
    }

    // =====================================
    // 模拟Service正常返回
    // =====================================

    private void mockSuccessResponse() {

        when(shortLinkService.createShortLink(
                any(ShortLinkCreateReqDTO.class)
        )).thenReturn(
                ShortLinkCreateRespDTO.builder()
                        .gid(GID)
                        .originUrl(URL)
                        .fullShortUrl(
                                "http://nurl.ink:8001/test123")
                        .build()
        );
    }
}
