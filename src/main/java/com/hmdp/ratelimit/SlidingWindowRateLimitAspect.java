package com.hmdp.ratelimit;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.UUID;

@Aspect
@Component
public class SlidingWindowRateLimitAspect {
    private static final DefaultRedisScript<Long> SCRIPT;

    static {
        SCRIPT = new DefaultRedisScript<>();
        SCRIPT.setLocation(new ClassPathResource("sliding-window-rate-limit.lua"));
        SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redisTemplate;

    @Value("${app.rate-limit.multiplier:1}")
    private int limitMultiplier = 1;

    public SlidingWindowRateLimitAspect(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Around("@annotation(com.hmdp.ratelimit.SlidingWindowRateLimit) || "
            + "@annotation(com.hmdp.ratelimit.SlidingWindowRateLimits)")
    public Object enforce(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        SlidingWindowRateLimit[] policies = method.getAnnotationsByType(SlidingWindowRateLimit.class);
        HttpServletRequest request = currentRequest();
        long now = System.currentTimeMillis();

        for (SlidingWindowRateLimit policy : policies) {
            int effectiveLimit = policy.limit() * Math.max(1, limitMultiplier);
            String subject = subject(policy.dimension(), request);
            if (subject == null) {
                continue;
            }
            String key = "rate:sliding:" + policy.dimension().name().toLowerCase()
                    + ":" + subject;
            Long remaining = redisTemplate.execute(
                    SCRIPT,
                    Collections.singletonList(key),
                    Long.toString(now),
                    Long.toString(policy.windowSeconds() * 1000L),
                    Integer.toString(effectiveLimit),
                    now + "-" + UUID.randomUUID());
            if (remaining == null || remaining < 0L) {
                return Result.fail("请求过于频繁，请稍后重试");
            }
        }
        return joinPoint.proceed();
    }

    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            throw new IllegalStateException("Rate limiting requires an HTTP request");
        }
        return attributes.getRequest();
    }

    private String subject(RateLimitDimension dimension, HttpServletRequest request) {
        if (dimension == RateLimitDimension.API) {
            return request.getMethod() + ":" + request.getRequestURI();
        }
        if (dimension == RateLimitDimension.IP) {
            String forwarded = request.getHeader("X-Forwarded-For");
            return forwarded == null || forwarded.trim().isEmpty()
                    ? request.getRemoteAddr()
                    : forwarded.split(",")[0].trim();
        }
        UserDTO user = UserHolder.getUser();
        return user == null || user.getId() == null ? null : user.getId().toString();
    }
}
