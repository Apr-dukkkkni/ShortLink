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

package com.nageoffer.shortlink.project.dao.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data //自动生成get set
@Builder //建造者模式，方便链式创建对象。
@NoArgsConstructor //生成无参构造函数
@AllArgsConstructor //生成全参构造函数，所有字段作为参数的构造方法。
@TableName("short_link_sequence")
public class ShortLinkSequenceDO {

    /**
     * 业务标识，例如 short_link
     */
    @TableId
    private String bizTag;

    /**
     * 当前已经分配出去的最大 ID
     */
    private Long maxId;

    /**
     * 每次申请多少个 ID
     */
    private Integer step;

    /**
     * 更新时间
     */
    private Date updateTime;
}