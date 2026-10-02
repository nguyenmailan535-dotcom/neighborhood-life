package com.hmdp.cache;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.entity.Shop;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

/**
 * Caffeine -> Redis -> MySQL cache chain for hot shop data.
 *
 * Redis stores a logical expiry envelope and keeps the stale value longer than the logical TTL.
 * One node obtains a Redisson lock and rebuilds asynchronously while other nodes keep serving the
 * stale value. Empty values protect MySQL from penetration and randomized TTLs avoid synchronized
 * expiry.
 */
@Slf4j
@Component
public class MultiLevelShopCache {
    private static final int TTL_JITTER_MINUTES = 5;
    private static final ExecutorService REBUILD_POOL = Executors.newFixedThreadPool(4);

    private final Cache<Long, ShopCacheValue> localCache;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;

    @Value("${app.cache.local-enabled:true}")
    private boolean localEnabled;

    public MultiLevelShopCache(
            Cache<Long, ShopCacheValue> localCache,
            StringRedisTemplate redisTemplate,
            RedissonClient redissonClient) {
        this.localCache = localCache;
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
    }

    public Shop query(Long id, Function<Long, Shop> databaseFallback) {
        if (localEnabled) {
            ShopCacheValue local = localCache.getIfPresent(id);
            if (local != null) {
                return local.getShop();
            }
        }

        String key = CACHE_SHOP_KEY + id;
        String cached = redisTemplate.opsForValue().get(key);
        RedisLookup lookup = decode(cached);
        if (lookup.hit) {
            cacheLocally(id, lookup.shop);
            if (lookup.expired && lookup.shop != null) {
                rebuildAsynchronously(id, databaseFallback);
            }
            return lookup.shop;
        }

        RLock lock = redissonClient.getLock(LOCK_SHOP_KEY + id);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(200, 10, TimeUnit.SECONDS);
            if (!acquired) {
                return retryRedisOnce(id, databaseFallback);
            }

            RedisLookup doubleCheck = decode(redisTemplate.opsForValue().get(key));
            if (doubleCheck.hit) {
                cacheLocally(id, doubleCheck.shop);
                return doubleCheck.shop;
            }

            Shop shop = databaseFallback.apply(id);
            writeRedis(id, shop);
            cacheLocally(id, shop);
            return shop;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while rebuilding shop cache", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public void invalidate(Long id) {
        localCache.invalidate(id);
        redisTemplate.delete(CACHE_SHOP_KEY + id);
    }

    public void warm(Long id, Shop shop) {
        writeRedis(id, shop);
        cacheLocally(id, shop);
    }

    private Shop retryRedisOnce(Long id, Function<Long, Shop> databaseFallback)
            throws InterruptedException {
        for (int attempt = 0; attempt < 4; attempt++) {
            Thread.sleep(25L);
            RedisLookup retry = decode(redisTemplate.opsForValue().get(CACHE_SHOP_KEY + id));
            if (retry.hit) {
                cacheLocally(id, retry.shop);
                return retry.shop;
            }
        }
        // Availability fallback: a short lock wait must not turn Redis contention into a 5xx.
        return databaseFallback.apply(id);
    }

    private void rebuildAsynchronously(Long id, Function<Long, Shop> databaseFallback) {
        REBUILD_POOL.submit(() -> {
            RLock lock = redissonClient.getLock(LOCK_SHOP_KEY + id);
            if (!lock.tryLock()) {
                return;
            }
            try {
                Shop fresh = databaseFallback.apply(id);
                writeRedis(id, fresh);
                cacheLocally(id, fresh);
            } catch (RuntimeException e) {
                log.error("Failed to rebuild hot shop cache, shopId={}", id, e);
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        });
    }

    private void writeRedis(Long id, Shop shop) {
        String key = CACHE_SHOP_KEY + id;
        if (shop == null) {
            redisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return;
        }

        long jitter = ThreadLocalRandom.current().nextLong(TTL_JITTER_MINUTES + 1L);
        long logicalTtl = CACHE_SHOP_TTL + jitter;
        RedisData<Shop> data = new RedisData<>();
        data.setData(shop);
        data.setExpireTime(LocalDateTime.now().plusMinutes(logicalTtl));
        // Keep stale data available long enough for asynchronous rebuilding.
        redisTemplate.opsForValue().set(
                key,
                JSONUtil.toJsonStr(data),
                logicalTtl * 2,
                TimeUnit.MINUTES);
    }

    private RedisLookup decode(String json) {
        if (json == null) {
            return RedisLookup.miss();
        }
        if (StrUtil.isBlank(json)) {
            return RedisLookup.hit(null, false);
        }
        JSONObject object = JSONUtil.parseObj(json);
        if (!object.containsKey("expireTime") || !object.containsKey("data")) {
            // Backward compatibility for cache values written by the old Redis-only implementation.
            return RedisLookup.hit(JSONUtil.toBean(object, Shop.class), false);
        }
        RedisData<?> redisData = JSONUtil.toBean(object, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        boolean expired = redisData.getExpireTime().isBefore(LocalDateTime.now());
        return RedisLookup.hit(shop, expired);
    }

    private void cacheLocally(Long id, Shop shop) {
        if (!localEnabled) {
            return;
        }
        localCache.put(id, shop == null ? ShopCacheValue.missing() : ShopCacheValue.present(shop));
    }

    private static final class RedisLookup {
        private final boolean hit;
        private final Shop shop;
        private final boolean expired;

        private RedisLookup(boolean hit, Shop shop, boolean expired) {
            this.hit = hit;
            this.shop = shop;
            this.expired = expired;
        }

        private static RedisLookup miss() {
            return new RedisLookup(false, null, false);
        }

        private static RedisLookup hit(Shop shop, boolean expired) {
            return new RedisLookup(true, shop, expired);
        }
    }
}
