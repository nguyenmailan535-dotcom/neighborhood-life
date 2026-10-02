package com.hmdp.cache;

import com.hmdp.entity.Shop;

/** Caffeine does not permit null values, so misses use an explicit value object. */
public final class ShopCacheValue {
    private static final ShopCacheValue MISSING = new ShopCacheValue(null);

    private final Shop shop;

    private ShopCacheValue(Shop shop) {
        this.shop = shop;
    }

    public static ShopCacheValue present(Shop shop) {
        return new ShopCacheValue(shop);
    }

    public static ShopCacheValue missing() {
        return MISSING;
    }

    public Shop getShop() {
        return shop;
    }

    public boolean isPresent() {
        return shop != null;
    }
}
