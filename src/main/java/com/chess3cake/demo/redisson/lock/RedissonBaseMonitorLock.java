/**
 * Copyright (c) 2013-2022 Nikita Koksharov
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.chess3cake.demo.redisson.lock;

import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.BatchOptions;
import org.redisson.api.BatchResult;
import org.redisson.api.RFuture;
import org.redisson.client.RedisException;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.LongCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.RedisCommand;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.client.protocol.convertor.IntegerReplayConvertor;
import org.redisson.client.protocol.decoder.MapValueDecoder;
import org.redisson.client.protocol.decoder.StringListReplayDecoder;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.command.CommandBatchService;
import org.redisson.connection.MasterSlaveEntry;
import org.redisson.misc.CompletableFutureWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * Base class for implementing distributed locks
 *
 * @author Danila Varatyntsev
 * @author Nikita Koksharov
 */
public abstract class RedissonBaseMonitorLock extends RedissonExpirable implements RMonitorLock {



    public static class ExpirationEntry {

        private final Map<String, Integer> monitorIds = new LinkedHashMap<>();
        private volatile Timeout timeout;

        public ExpirationEntry() {
            super();
        }

        public synchronized void addMonitorId(String monitorId) {
            monitorIds.compute(monitorId, (t, counter) -> {
                counter = Optional.ofNullable(counter).orElse(0);
                counter++;
                return counter;
            });
        }

        public synchronized boolean hasNoMonitors() {
            return monitorIds.isEmpty();
        }

        public synchronized String getFirstMonitorId() {
            if (monitorIds.isEmpty()) {
                return null;
            }
            return monitorIds.keySet().iterator().next();
        }

        public synchronized void removeMonitorId(String monitorId) {
            monitorIds.compute(monitorId, (t, counter) -> {
                if (counter == null) {
                    return null;
                }
                counter--;
                if (counter == 0) {
                    return null;
                }
                return counter;
            });
        }

        public void setTimeout(Timeout timeout) {
            this.timeout = timeout;
        }

        public Timeout getTimeout() {
            return timeout;
        }

    }

    private static final Logger log = LoggerFactory.getLogger(RedissonBaseMonitorLock.class);

    private static final ConcurrentMap<String, ExpirationEntry> EXPIRATION_RENEWAL_MAP = new ConcurrentHashMap<>();
    protected long internalLockLeaseTime;

    final String id;
    final String entryName;

    final CommandAsyncExecutor commandExecutor;
    final String monitorId;

    public RedissonBaseMonitorLock(CommandAsyncExecutor commandExecutor, String name, String monitorId) {
        super(commandExecutor, name);
        this.commandExecutor = commandExecutor;
        this.id = commandExecutor.getConnectionManager().getId();
        this.internalLockLeaseTime = commandExecutor.getConnectionManager().getCfg().getLockWatchdogTimeout();
        this.entryName = id + ":" + name;
        this.monitorId = monitorId;
    }

    public String getMonitorId() {
        return monitorId;
    }

    protected String getEntryName() {
        return entryName;
    }

    protected String getLockName(String monitorId) {
        return id + ":" + monitorId;
    }

    private void renewExpiration() {
        ExpirationEntry ee = EXPIRATION_RENEWAL_MAP.get(getEntryName());
        if (ee == null) {
            return;
        }

        Timeout task = commandExecutor.getConnectionManager().newTimeout(new TimerTask() {
            @Override
            public void run(Timeout timeout) throws Exception {
                ExpirationEntry ent = EXPIRATION_RENEWAL_MAP.get(getEntryName());
                if (ent == null) {
                    return;
                }
                String monitorId = ent.getFirstMonitorId();
                if (monitorId == null) {
                    return;
                }

                CompletionStage<Boolean> future = renewExpirationAsync(monitorId);
                future.whenComplete((res, e) -> {
                    if (e != null) {
                        log.error("Can't update lock {} expiration", getRawName(), e);
                        EXPIRATION_RENEWAL_MAP.remove(getEntryName());
                        return;
                    }

                    if (res) {
                        // reschedule itself
                        renewExpiration();
                    } else {
                        cancelExpirationRenewal(null);
                    }
                });
            }
        }, internalLockLeaseTime / 3, TimeUnit.MILLISECONDS);

        ee.setTimeout(task);
    }

    protected void scheduleExpirationRenewal(String monitorId) {
        ExpirationEntry entry = new ExpirationEntry();
        ExpirationEntry oldEntry = EXPIRATION_RENEWAL_MAP.putIfAbsent(getEntryName(), entry);
        if (oldEntry != null) {
            oldEntry.addMonitorId(monitorId);
        } else {
            entry.addMonitorId(monitorId);
            try {
                renewExpiration();
            } finally {
                if (Thread.currentThread().isInterrupted()) {
                    cancelExpirationRenewal(monitorId);
                }
            }
        }
    }

    protected CompletionStage<Boolean> renewExpirationAsync(String monitorId) {
        return evalWriteAsync(getRawName(), LongCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                "if (redis.call('hexists', KEYS[1], ARGV[2]) == 1) then " +
                        "redis.call('pexpire', KEYS[1], ARGV[1]); " +
                        "return 1; " +
                        "end; " +
                        "return 0;",
                Collections.singletonList(getRawName()),
                internalLockLeaseTime, getLockName(monitorId));
    }

    protected void cancelExpirationRenewal(String monitorId) {
        ExpirationEntry task = EXPIRATION_RENEWAL_MAP.get(getEntryName());
        if (task == null) {
            return;
        }

        if (monitorId != null) {
            task.removeMonitorId(monitorId);
        }

        if (monitorId == null || task.hasNoMonitors()) {
            Timeout timeout = task.getTimeout();
            if (timeout != null) {
                timeout.cancel();
            }
            EXPIRATION_RENEWAL_MAP.remove(getEntryName());
        }
    }

    protected <T> RFuture<T> evalWriteAsync(String key, Codec codec, RedisCommand<T> evalCommandType, String script, List<Object> keys, Object... params) {
        MasterSlaveEntry entry = commandExecutor.getConnectionManager().getEntry(getRawName());

        CompletionStage<Map<String, String>> replicationFuture = CompletableFuture.completedFuture(Collections.emptyMap());
        if (!(commandExecutor instanceof CommandBatchService) && entry != null && entry.getAvailableSlaves() > 0) {
            replicationFuture = commandExecutor.writeAsync(entry, null, RedisCommands.INFO_REPLICATION);
        }
        CompletionStage<T> resFuture = replicationFuture.thenCompose(r -> {
            Integer availableSlaves = Integer.valueOf(r.getOrDefault("connected_slaves", "0"));

            CommandBatchService executorService = createCommandBatchService(availableSlaves);
            RFuture<T> result = executorService.evalWriteAsync(key, codec, evalCommandType, script, keys, params);
            if (commandExecutor instanceof CommandBatchService) {
                return result;
            }

            RFuture<BatchResult<?>> future = executorService.executeAsync();
            CompletionStage<T> f = future.handle((res, ex) -> {
                if (ex != null) {
                    throw new CompletionException(ex);
                }
                if (commandExecutor.getConnectionManager().getCfg().isCheckLockSyncedSlaves()
                        && res.getSyncedSlaves() == 0 && availableSlaves > 0) {
                    throw new CompletionException(
                            new IllegalStateException("None of slaves were synced"));
                }

                return commandExecutor.getNow(result.toCompletableFuture());
            });
            return f;
        });
        return new CompletableFutureWrapper<>(resFuture);
    }

    private CommandBatchService createCommandBatchService(int availableSlaves) {
        if (commandExecutor instanceof CommandBatchService) {
            return (CommandBatchService) commandExecutor;
        }

        BatchOptions options = BatchOptions.defaults()
                .syncSlaves(availableSlaves, 1, TimeUnit.SECONDS);

        return new CommandBatchService(commandExecutor, options);
    }

    protected void acquireFailed(long waitTime, TimeUnit unit, String monitorId) {
        commandExecutor.get(acquireFailedAsync(waitTime, unit, monitorId));
    }

    protected void trySuccessFalse(String currentMonitorId, CompletableFuture<Boolean> result) {
        acquireFailedAsync(-1, null, currentMonitorId).whenComplete((res, e) -> {
            if (e == null) {
                result.complete(false);
            } else {
                result.completeExceptionally(e);
            }
        });
    }

    protected CompletableFuture<Void> acquireFailedAsync(long waitTime, TimeUnit unit, String  monitorId) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isLocked() {
        return isExists();
    }

    @Override
    public RFuture<Boolean> isLockedAsync() {
        return isExistsAsync();
    }

    @Override
    public boolean isHeldByCurrentMonitor() {
        return isHeldByMonitor(monitorId);
    }

    @Override
    public boolean isHeldByMonitor(String monitorId) {
        RFuture<Boolean> future = commandExecutor.writeAsync(getRawName(), LongCodec.INSTANCE, RedisCommands.HEXISTS, getRawName(), getLockName(monitorId));
        return get(future);
    }

    private static final RedisCommand<Integer> HGET = new RedisCommand<Integer>("HGET", new MapValueDecoder(), new IntegerReplayConvertor(0));

    public RFuture<Integer> getHoldCountAsync() {
        return commandExecutor.writeAsync(getRawName(), LongCodec.INSTANCE, HGET, getRawName(), getLockName(monitorId));
    }

    @Override
    public int getHoldCount() {
        return get(getHoldCountAsync());
    }

    @Override
    public RFuture<Boolean> deleteAsync() {
        return forceUnlockAsync();
    }

    @Override
    public RFuture<Void> unlockAsync() {
        return unlockAsync(monitorId);
    }

    @Override
    public RFuture<Void> unlockAsync(String monitorId) {
        RFuture<Boolean> future = unlockInnerAsync(monitorId);

        CompletionStage<Void> f = future.handle((opStatus, e) -> {
            cancelExpirationRenewal(monitorId);

            if (e != null) {
                throw new CompletionException(e);
            }
            if (opStatus == null) {
                IllegalMonitorStateException cause = new IllegalMonitorStateException("attempt to unlock lock, not locked by current thread by node id: "
                        + id + " monitorId: " + monitorId);
                throw new CompletionException(cause);
            }

            return null;
        });

        return new CompletableFutureWrapper<>(f);
    }

    @Override
    public void unlock() {
        try {
            get(unlockAsync(monitorId));
        } catch (RedisException e) {
            if (e.getCause() instanceof IllegalMonitorStateException) {
                throw (IllegalMonitorStateException) e.getCause();
            } else {
                throw e;
            }
        }

//        Future<Void> future = unlockAsync();
//        future.awaitUninterruptibly();
//        if (future.isSuccess()) {
//            return;
//        }
//        if (future.cause() instanceof IllegalMonitorStateException) {
//            throw (IllegalMonitorStateException)future.cause();
//        }
//        throw commandExecutor.convertException(future);
    }

    @Override
    public boolean forceUnlock() {
        return get(forceUnlockAsync());
    }

    protected abstract RFuture<Boolean> unlockInnerAsync(String monitorId);

    @Override
    public RFuture<Void> lockAsync() {
        return lockAsync(-1, null);
    }

    @Override
    public RFuture<Void> lockAsync(long leaseTime, TimeUnit unit) {
        return lockAsync(leaseTime, unit, monitorId);
    }

    @Override
    public RFuture<Void> lockAsync(String currentMonitorId) {
        return lockAsync(-1, null, currentMonitorId);
    }



    @Override
    public RFuture<Boolean> tryLockAsync() {
        return tryLockAsync(monitorId);
    }

    @Override
    public RFuture<Boolean> tryLockAsync(long waitTime, TimeUnit unit) {
        return tryLockAsync(waitTime, -1, unit);
    }

    @Override
    public RFuture<Boolean> tryLockAsync(long waitTime, long leaseTime, TimeUnit unit) {
        return tryLockAsync(waitTime, leaseTime, unit, monitorId);
    }
    private static final RedisCommand<List<String>> HKEYS = new RedisCommand<>("HKEYS", new MapValueDecoder(new StringListReplayDecoder()));

    @Override
    public String getHeldMonitorId() {
        RFuture<List<String>> future = commandExecutor.writeAsync(getRawName(), StringCodec.INSTANCE, HKEYS, getRawName());
        return get(future)
                .stream()
                .filter(StringUtils::isNotBlank)
                //key前缀
                .filter(key -> StringUtils.startsWith(key, id + ":"))
                //切割
                .map(key -> StringUtils.substringAfter(key, ":"))
                .findFirst()
                .orElse(null);
    }
}
