package com.chess3cake.demo.redisson.lock;

import org.redisson.config.Config;

import java.util.UUID;

/**
 * <p>
 *
 * </p>
 *
 * @author chess3cake
 * @since 2023/2/14 12:56
 */
public class Case {

    public static void main(String[] args) {
        String lockName = generateLockName();
        String monitorId = generateMonitorId();
        Config config = new Config();
        DemoRedissonClient redisson = DemoRedissonClient.create(config);

        RedissonMonitorLock lock = redisson.getMonitorLock(lockName, monitorId);
        if (lock.tryLock()) {
            try {
                //locked by current monitor id

            } finally {
                lock.unlock();
            }
        } else {
            //locked by another monitor id
            String otherMonitorId = lock.getHeldMonitorId();
            Object otherMonitor = getMonitor(otherMonitorId);
            //do something..


        }

    }


    static Object getMonitor(String monitorId) {
        //return by monitor id
        return new Object();
    }

    static String generateLockName() {
        return UUID.randomUUID().toString();
    }

    static String generateMonitorId() {
        return UUID.randomUUID().toString();
    }


}
