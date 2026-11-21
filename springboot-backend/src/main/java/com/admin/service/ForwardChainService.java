package com.admin.service;

import com.admin.common.dto.ForwardChainDto;
import com.admin.common.lang.R;
import com.admin.entity.ForwardChain;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface ForwardChainService extends IService<ForwardChain> {
    R listChains(Long currentAdminId);
    R updateOrder(List<Long> ids);
    R requestProbe(Long id);
    R createChain(ForwardChainDto dto, Long userId);
    R updateChain(ForwardChainDto dto);
    R deleteChain(Long id);
    R setStatus(Long id, Integer status);
}
