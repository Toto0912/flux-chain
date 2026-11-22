package com.admin.controller;

import com.admin.common.aop.LogAnnotation;
import com.admin.common.annotation.RequireRole;
import com.admin.common.dto.ForwardDto;
import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.lang.R;
import com.admin.service.ForwardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author QAQ
 * @since 2025-06-03
 */
@RestController
@CrossOrigin
@RequestMapping("/api/v1/forward")
public class ForwardController extends BaseController {

    @Autowired
    private ForwardService forwardService;
    private final Semaphore configurationRequests = new Semaphore(16);
    private final Semaphore cleanupRequests = new Semaphore(8);
    private final Semaphore healthStatusRequests = new Semaphore(16);

    @LogAnnotation
    @PostMapping("/create")
    public R create(@Validated @RequestBody ForwardDto forwardDto) {
        return withPermit(configurationRequests, "转发配置操作繁忙，请稍后重试", () -> forwardService.createForward(forwardDto));
    }

    @LogAnnotation
    @PostMapping("/list")
    public R readAll() {
        return forwardService.getAllForwards();
    }

    @LogAnnotation
    @PostMapping("/update")
    public R update(@Validated @RequestBody ForwardUpdateDto forwardUpdateDto) {
        return withPermit(configurationRequests, "转发配置操作繁忙，请稍后重试", () -> forwardService.updateForward(forwardUpdateDto));
    }

    @LogAnnotation
    @PostMapping("/delete")
    public R delete(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return withPermit(cleanupRequests, "转发清理操作繁忙，请稍后重试", () -> forwardService.deleteForward(id));
    }

    @LogAnnotation
    @PostMapping("/force-delete")
    public R forceDelete(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return withPermit(cleanupRequests, "转发清理操作繁忙，请稍后重试", () -> forwardService.forceDeleteForward(id));
    }

    @LogAnnotation
    @PostMapping("/pause")
    public R pause(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return withPermit(cleanupRequests, "转发清理操作繁忙，请稍后重试", () -> forwardService.pauseForward(id));
    }

    @LogAnnotation
    @PostMapping("/resume")
    public R resume(@RequestBody Map<String, Object> params) {
        Long id = Long.valueOf(params.get("id").toString());
        return withPermit(configurationRequests, "转发配置操作繁忙，请稍后重试", () -> forwardService.resumeForward(id));
    }

    /**
     * 转发诊断功能
     * @param params 包含forwardId的参数
     * @return 诊断结果
     */
    @LogAnnotation
    @PostMapping("/diagnose")
    public R diagnoseForward(@RequestBody Map<String, Object> params) {
        Long forwardId = Long.valueOf(params.get("forwardId").toString());
        return forwardService.diagnoseForward(forwardId);
    }

    @PostMapping("/target-health")
    public R targetHealth(@RequestBody Map<String, Long> params) {
        Long forwardId = params.get("forwardId");
        if (forwardId == null) return R.err("转发 ID 不能为空");
        return withPermit(healthStatusRequests, "探测结果查询繁忙，请稍后重试", () -> forwardService.getTargetHealth(forwardId));
    }

    private R withPermit(Semaphore permits, String busy, Supplier<R> operation) {
        if (!permits.tryAcquire()) return R.err(busy);
        try {
            return operation.get();
        } finally {
            permits.release();
        }
    }

    /**
     * 更新转发排序
     * @param params 包含forwards数组的参数，每个元素包含id和inx
     * @return 更新结果
     */
    @LogAnnotation
    @PostMapping("/update-order")
    public R updateForwardOrder(@RequestBody Map<String, Object> params) {
        return forwardService.updateForwardOrder(params);
    }

}
