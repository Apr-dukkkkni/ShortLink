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

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

import java.util.Date;

/**
 * 短链接创建请求对象
 * 公众号：马丁玩编程，回复：加群，添加马哥微信（备注：link）获取项目资料
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShortLinkCreateReqDTO {

    /**
     * 域名
     */
    private String domain;

    /**
     * 原始链接
     */
    @NotBlank(message = "原始链接不能为空")
    @Size(max = 1024, message = "原始链接长度不能超过1024字符")
    @URL(message = "原始链接格式不正确")
    @Pattern(
            regexp = "(?i)^https?://[^\\s/?#]+(?:[/?#].*)?$",
            message = "原始链接必须使用http或https协议且包含域名"
    )
    private String originUrl;

    /**
     * 分组标识
     */
    @NotBlank(message = "分组标识不能为空")
    private String gid;

    /**
     * 创建类型 0：接口创建 1：控制台创建
     */
    private Integer createdType;

    /**
     * 有效期类型 0：永久有效 1：自定义
     */
    @NotNull(message = "有效期类型不能为空")
    @Min(value = 0, message = "有效期类型只支持0或1")
    @Max(value = 1, message = "有效期类型只支持0或1")
    private Integer validDateType;

    /**
     * 有效期
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date validDate;

    /**
     * 描述
     */
    private String describe;

    /**
     * 0: 永久有效，不应设置到期时间；1: 自定义，到期时间必须在未来。
     * validDateType 为空的情况单独由 @NotNull 校验。
     */
    @JsonIgnore
    @AssertTrue(message = "有效期参数不合法：永久有效不能设置过期时间，自定义有效期必须指定未来时间")
    public boolean isValidDateCombination() {
        if (validDateType == null) {
            return true;
        }
        if (validDateType == 0) {
            return validDate == null;
        }
        if (validDateType == 1) {
            return validDate != null && validDate.after(new Date());
        }
        return true;
    }
}
