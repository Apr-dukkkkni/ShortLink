package com.nageoffer.shortlink.project.dao;

import com.nageoffer.shortlink.project.dao.entity.ShortLinkSequenceDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ShortLinkSequenceMapper {

    /**
     * 原子更新max_id：max_id = max_id + step，返回影响行数
     * 利用mysql行锁，保证并发下不会拿到重复号段
     */
    int updateMaxIdByBizTag(@Param("bizTag") String bizTag, @Param("step") int step);

    /**
     * 查询当前业务号段记录
     */
    ShortLinkSequenceDO selectByBizTag(@Param("bizTag") String bizTag);
}