package com.admin.controller;

import com.admin.common.annotation.RequireRole;
import com.admin.common.aop.LogAnnotation;
import com.admin.common.dto.ForwardChainDto;
import com.admin.common.lang.R;
import com.admin.common.utils.JwtUtil;
import com.admin.service.ForwardChainService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin
@RequestMapping("/api/v1/forward-chain")
public class ForwardChainController {
    @Autowired private ForwardChainService service;

    @LogAnnotation @RequireRole @PostMapping("/list")
    public R list() { return service.listChains(JwtUtil.getUserIdFromToken().longValue()); }

    @LogAnnotation @RequireRole @PostMapping("/update-order")
    public R updateOrder(@RequestBody OrderRequest body) { return service.updateOrder(body.ids()); }

    @LogAnnotation @RequireRole @PostMapping("/probe")
    public R probe(@RequestBody ProbeRequest body) { return service.requestProbe(body.id()); }

    @LogAnnotation @RequireRole @PostMapping("/create")
    public R create(@Validated @RequestBody ForwardChainDto dto) {
        return service.createChain(dto, JwtUtil.getUserIdFromToken().longValue());
    }

    @LogAnnotation @RequireRole @PostMapping("/update")
    public R update(@Validated @RequestBody ForwardChainDto dto) { return service.updateChain(dto); }

    @LogAnnotation @RequireRole @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> body) { return service.deleteChain(Long.valueOf(body.get("id").toString())); }

    @LogAnnotation @RequireRole @PostMapping("/set-status")
    public R setStatus(@RequestBody Map<String, Object> body) { return service.setStatus(Long.valueOf(body.get("id").toString()), Integer.valueOf(body.get("status").toString())); }

    public record OrderRequest(List<Long> ids) {}
    public record ProbeRequest(Long id) {}
}
