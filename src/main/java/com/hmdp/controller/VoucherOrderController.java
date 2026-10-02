package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.ratelimit.RateLimitDimension;
import com.hmdp.ratelimit.SlidingWindowRateLimit;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Autowired
    private IVoucherOrderService voucherOrderService;

    @PostMapping("seckill/{id}")
    @SlidingWindowRateLimit(dimension = RateLimitDimension.API, limit = 1000, windowSeconds = 1)
    @SlidingWindowRateLimit(dimension = RateLimitDimension.IP, limit = 30, windowSeconds = 1)
    @SlidingWindowRateLimit(dimension = RateLimitDimension.USER, limit = 3, windowSeconds = 1)
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }
}
