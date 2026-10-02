package com.hmdp.rebbitmq;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class SeckillPendingMessageStore {
    public static final String PENDING_KEY = "seckill:pending:orders";

    private final StringRedisTemplate redisTemplate;

    public SeckillPendingMessageStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void markConfirmed(String orderId) {
        redisTemplate.opsForHash().delete(PENDING_KEY, orderId);
    }

    public Map<Object, Object> list(int limit) {
        Map<Object, Object> all = redisTemplate.opsForHash().entries(PENDING_KEY);
        if (all == null || all.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Object, Object> entry : all.entrySet()) {
            result.put(entry.getKey(), entry.getValue());
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }
}
