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