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

package com.nageoffer.shortlink.project.dto.req;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ShortLinkCreateReqDTOTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void cleanup() {
        factory.close();
    }

    // 构造一份默认合法的请求参数
    private ShortLinkCreateReqDTO createValidRequest() {
        return ShortLinkCreateReqDTO.builder()
                .domain("nurl.ink:8001")
                .originUrl("https://www.baidu.com")
                .gid("XMQIfR")
                .createdType(1)
                .validDateType(0)
                .validDate(null)
                .describe("JUnit自动化测试")
                .build();
    }

    // 断言指定字段出现校验错误
    private void assertInvalid(
            ShortLinkCreateReqDTO request,
            String field) {

        Set<ConstraintViolation<ShortLinkCreateReqDTO>> violations =
                validator.validate(request);

        assertTrue(
                violations.stream().anyMatch(v ->
                        field.equals(v.getPropertyPath().toString())),
                "预期字段 [" + field + "] 校验失败，实际：" + violations
        );
    }

    // 断言所有参数校验均通过
    private void assertValid(ShortLinkCreateReqDTO request) {

        Set<ConstraintViolation<ShortLinkCreateReqDTO>> violations =
                validator.validate(request);

        assertTrue(
                violations.isEmpty(),
                "合法参数不应触发校验错误：" + violations
        );
    }

    // ============================
    // Bug-001：原始 URL 参数校验
    // ============================

    @Test
    void test01_InvalidOriginUrl() {
        ShortLinkCreateReqDTO request = createValidRequest();
        request.setOriginUrl("abc123");

        assertInvalid(request, "originUrl");
    }

    @Test
    void test02_EmptyOriginUrl() {
        ShortLinkCreateReqDTO request = createValidRequest();
        request.setOriginUrl("");

        assertInvalid(request, "originUrl");
    }

    // ============================
    // Bug-002：分组 gid 参数校验
    // ============================

    @Test
    void test03_NullGid() {
        ShortLinkCreateReqDTO request = createValidRequest();
        request.setGid(null);

        assertInvalid(request, "gid");
    }

    @Test
    void test04_EmptyGid() {
        ShortLinkCreateReqDTO request = createValidRequest();
        request.setGid("");

        assertInvalid(request, "gid");
    }

    // ============================
    // Bug-003：有效期组合参数校验
    // ============================

    // 自定义有效期，但时间为空
    @Test
    void test05_CustomDateNull() {
        ShortLinkCreateReqDTO request = createValidRequest();
        request.setValidDateType(1);
        request.setValidDate(null);

        assertInvalid(request, "validDateCombination");
    }

    // 自定义有效期，但时间已经过去
    @Test
    void test06_CustomDatePast() {
        ShortLinkCreateReqDTO request = createValidRequest();

        request.setValidDateType(1);
        request.setValidDate(
                new Date(System.currentTimeMillis() - 3600000)
        );

        assertInvalid(request, "validDateCombination");
    }

    // 自定义有效期，未来一小时
    @Test
    void test07_CustomDateFuture() {
        ShortLinkCreateReqDTO request = createValidRequest();

        request.setValidDateType(1);
        request.setValidDate(
                new Date(System.currentTimeMillis() + 3600000)
        );

        assertValid(request);
    }

    // 永久有效，不设置过期时间
    @Test
    void test08_PermanentDateNull() {
        ShortLinkCreateReqDTO request = createValidRequest();

        request.setValidDateType(0);
        request.setValidDate(null);

        assertValid(request);
    }

    // 永久有效，却设置了过期时间
    @Test
    void test09_PermanentWithDate() {
        ShortLinkCreateReqDTO request = createValidRequest();

        request.setValidDateType(0);
        request.setValidDate(
                new Date(System.currentTimeMillis() + 3600000)
        );

        assertInvalid(request, "validDateCombination");
    }

    // 有效期类型为空
    @Test
    void test10_NullValidDateType() {
        ShortLinkCreateReqDTO request = createValidRequest();

        request.setValidDateType(null);

        assertInvalid(request, "validDateType");
    }

    // 有效期类型超出合法范围
    @Test
    void test11_InvalidValidDateType() {
        ShortLinkCreateReqDTO request = createValidRequest();

        request.setValidDateType(2);

        assertInvalid(request, "validDateType");
    }
}
