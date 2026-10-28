package com.admin.service;

import com.admin.common.dto.ForwardChainDto;
import com.admin.common.lang.R;
import com.admin.entity.ForwardChain;
import com.baomidou.mybatisplus.extension.service.IService;

public interface ForwardChainService extends IService<ForwardChain> {
    R listChains();
    R createChain(ForwardChainDto dto);
    R updateChain(ForwardChainDto dto);
    R deleteChain(Long id);
    R setStatus(Long id, Integer status);
}
