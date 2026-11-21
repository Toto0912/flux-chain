package com.admin.mapper;

import com.admin.entity.ForwardChain;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface ForwardChainMapper extends BaseMapper<ForwardChain> {
    @Select("""
            SELECT c.*, u.`user` AS userName
            FROM forward_chain c
            LEFT JOIN `user` u ON u.id = c.user_id
            ORDER BY c.sort_order ASC, c.id DESC
            """)
    List<ForwardChain> selectDisplayList();

    @Select("SELECT id FROM forward_chain ORDER BY id FOR UPDATE")
    List<Long> lockChainIds();

    @Update("""
            <script>
            UPDATE forward_chain
            SET sort_order = CASE id
                <foreach collection="ids" item="id" index="position">
                    WHEN #{id} THEN #{position}
                </foreach>
            END
            WHERE id IN
                <foreach collection="ids" item="id" open="(" separator="," close=")">
                    #{id}
                </foreach>
            </script>
            """)
    void updateDisplayOrder(@Param("ids") List<Long> ids);
}
