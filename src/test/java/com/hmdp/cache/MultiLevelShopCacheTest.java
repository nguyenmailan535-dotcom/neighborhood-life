package com.hmdp.cache;

import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MultiLevelShopCacheTest {
    @Mock
    private Cache<Long, ShopCacheValue> localCache;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private Function<Long, Shop> databaseFallback;

    private MultiLevelShopCache cache;

    @BeforeEach
    void setUp() {
        cache = new MultiLevelShopCache(localCache, redisTemplate, redissonClient);
        ReflectionTestUtils.setField(cache, "localEnabled", true);
    }

    @Test
    void returnsCaffeineHitWithoutTouchingRedis() {
        Shop shop = new Shop().setId(1L).setName("hot shop");
        when(localCache.getIfPresent(1L)).thenReturn(ShopCacheValue.present(shop));

        assertEquals(shop, cache.query(1L, databaseFallback));

        verify(redisTemplate, never()).opsForValue();
        verify(databaseFallback, never()).apply(anyLong());
    }

    @Test
    void promotesRedisHitIntoCaffeine() {
        Shop shop = new Shop().setId(2L).setName("redis shop");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_SHOP_KEY + 2L)).thenReturn(JSONUtil.toJsonStr(shop));

        assertEquals(shop, cache.query(2L, databaseFallback));

        verify(localCache).put(eq(2L), any(ShopCacheValue.class));
        verify(databaseFallback, never()).apply(anyLong());
    }

    @Test
    void serializesColdMissWithRedissonAndWarmsBothLevels() throws Exception {
        Shop shop = new Shop().setId(3L).setName("database shop");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CACHE_SHOP_KEY + 3L)).thenReturn(null);
        when(redissonClient.getLock(any())).thenReturn(lock);
        when(lock.tryLock(200, 10, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(databaseFallback.apply(3L)).thenReturn(shop);

        assertEquals(shop, cache.query(3L, databaseFallback));

        verify(valueOperations).set(eq(CACHE_SHOP_KEY + 3L), any(String.class), anyLong(),
                eq(TimeUnit.MINUTES));
        verify(localCache).put(eq(3L), any(ShopCacheValue.class));
        verify(lock).unlock();
    }
}
