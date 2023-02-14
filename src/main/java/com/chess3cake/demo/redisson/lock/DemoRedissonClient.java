package com.chess3cake.demo.redisson.lock;

import org.redisson.Redisson;
import org.redisson.config.Config;

/**
 * <p>
 *
 * </p>
 *
 * @author chess3cake
 * @since 2023/2/14 13:02
 */
public class DemoRedissonClient extends Redisson {
    protected DemoRedissonClient(Config config) {
        super(config);
    }

    public static DemoRedissonClient create(Config config) {
        return new DemoRedissonClient(config);
    }

    public RedissonMonitorLock getMonitorLock(String lockName, String monitorId) {
        return new RedissonMonitorLock(getCommandExecutor(), lockName, monitorId);
    }
}
