package com.hmdp.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(SlidingWindowRateLimits.class)
public @interface SlidingWindowRateLimit {
    RateLimitDimension dimension();

    int limit();

    int windowSeconds();
}
