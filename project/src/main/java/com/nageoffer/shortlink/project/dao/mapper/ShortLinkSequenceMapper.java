package com.nageoffer.shortlink.project.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.shortlink.project.dao.entity.ShortLinkSequenceDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ShortLinkSequenceMapper
        extends BaseMapper<ShortLinkSequenceDO> {

    /**
     * 查询当前号段配置，并对这一行加排他锁。
     *
     * 必须放在事务中调用。
     */
    @Select("""
            SELECT
                biz_tag,
                max_id,
                step,
                update_time
            FROM short_link_sequence
            WHERE biz_tag = #{bizTag}
            FOR UPDATE
            """)
    ShortLinkSequenceDO selectByBizTagForUpdate(
            @Param("bizTag") String bizTag
    );

    /**
     * 更新最大 ID
     */
    @Update("""
            UPDATE short_link_sequence
            SET max_id = #{newMaxId},
                update_time = NOW()
            WHERE biz_tag = #{bizTag}
            """)
    int updateMaxId(
            @Param("bizTag") String bizTag,
            @Param("newMaxId") Long newMaxId
    );
}