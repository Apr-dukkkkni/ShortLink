package com.nageoffer.shortlink.project.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("short_link_sequence")
public class ShortLinkSequenceDO {
    private Long id;
    private String bizTag;
    private Long maxId;
    private Integer step;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}