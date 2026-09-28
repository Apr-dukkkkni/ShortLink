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

package com.nageoffer.shortlink.project.common.convention.exception;

import com.nageoffer.shortlink.project.common.convention.errorcode.BaseErrorCode;
import com.nageoffer.shortlink.project.common.convention.errorcode.IErrorCode;

import java.util.Optional;

/**
 * 服务端异常
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：link）获取项目资料
 */
public class ServiceException extends AbstractException {

    //// 1. 只传错误信息，使用默认错误码 BaseErrorCode.SERVICE_ERROR
    public ServiceException(String message) {
        this(message, null, BaseErrorCode.SERVICE_ERROR);
    }

    // 2. 传入错误码枚举（推荐，统一错误码）
    public ServiceException(IErrorCode errorCode) {
        this(null, errorCode);
    }

    // 3. 自定义消息 + 错误码
    public ServiceException(String message, IErrorCode errorCode) {
        this(message, null, errorCode);
    }

    // 4. 完整构造：消息、原始异常、错误码
    public ServiceException(String message, Throwable throwable, IErrorCode errorCode) {
        super(Optional.ofNullable(message).orElse(errorCode.message()), throwable, errorCode);
    }

    @Override
    public String toString() {
        return "ServiceException{" +
                "code='" + errorCode + "'," +
                "message='" + errorMessage + "'" +
                '}';
    }
}

