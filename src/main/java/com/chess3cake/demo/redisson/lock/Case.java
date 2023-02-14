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
        //we use resource-id as lock-name, user-id as monitor-id
        //if the lock try lock successfully, the current user can use the resource
        //if the lock failed to try lock, the resource is being used by other user

        String resourceId = getResourceId();

        String currentUserId = getCurrentUserId();

        Config config = new Config();
        DemoRedissonClient redisson = DemoRedissonClient.create(config);

        RedissonMonitorLock lock = redisson.getMonitorLock(resourceId, currentUserId);
        if (lock.tryLock()) {
            try {
                //locked by current monitor id

                // current user get the resource

            } finally {
                lock.unlock();
            }
        } else {
            //locked by another monitor id
            String otherUserId = lock.getHeldMonitorId();
            Object otherUser = getUser(otherUserId);
            //other user get the resource
            //do something..
        }

    }


    static Object getUser(String monitorId) {
        //return by monitor id
        return new Object();
    }

    static String getResourceId() {
        return UUID.randomUUID().toString();
    }

    static String getCurrentUserId() {
        return UUID.randomUUID().toString();
    }


}
