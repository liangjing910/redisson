/**
 * Copyright 2016 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

import org.redisson.api.RFuture;
import org.redisson.api.RLock;
import org.redisson.client.codec.LongCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.client.protocol.RedisStrictCommand;
import org.redisson.command.CommandExecutor;
import org.redisson.pubsub.LockPubSub;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

/**
 * Lock will be removed automatically if client disconnects.
 *
 * @author Nikita Koksharov
 *
 */
public class RedissonReadLock extends RedissonLock implements RLock {

    private final CommandExecutor commandExecutor;

    protected RedissonReadLock(CommandExecutor commandExecutor, String name, UUID id) {
        super(commandExecutor, name, id);
        this.commandExecutor = commandExecutor;
    }

    @Override
    String getChannelName() {
        return "redisson_rwlock__{" + getName() + "}";
    }
    
    String getWriteLockName(long threadId) {
        return super.getLockName(threadId) + ":write";
    }

    @Override
    <T> RFuture<T> tryLockInnerAsync(long leaseTime, TimeUnit unit, long threadId, RedisStrictCommand<T> command) {
        internalLockLeaseTime = unit.toMillis(leaseTime);

        return commandExecutor.evalWriteAsync(getName(), LongCodec.INSTANCE, command,
                                "local mode = redis.call('hget', KEYS[1], 'mode'); " +
                                "if (mode == false) then " +
                                  "redis.call('hset', KEYS[1], 'mode', 'read'); " +
                                  "redis.call('hset', KEYS[1], ARGV[2], 1); " +
                                  "redis.call('set', KEYS[1] .. ':timeout:1', 1); " +
                                  "redis.call('pexpire', KEYS[1] .. ':timeout:1', ARGV[1]); " +
                                  "redis.call('pexpire', KEYS[1], ARGV[1]); " +
                                  "return nil; " +
                                "end; " +
                                "if (mode == 'read') or (mode == 'write' and redis.call('hexists', KEYS[1], ARGV[3]) == 1) then " +
                                  "local ind = redis.call('hincrby', KEYS[1], ARGV[2], 1); " +
                                  "redis.call('set', KEYS[1] .. ':timeout:' .. ind, 1); " +
                                  "redis.call('pexpire', KEYS[1] .. ':timeout:' .. ind, ARGV[1]); " +
                                  "redis.call('pexpire', KEYS[1], ARGV[1]); " +
                                  "return nil; " +
                                "end;" +
                                "return redis.call('pttl', KEYS[1]);",
                        Arrays.<Object>asList(getName()), internalLockLeaseTime, getLockName(threadId), getWriteLockName(threadId));
    }

    @Override
    public void unlock() {
        Boolean opStatus = commandExecutor.evalWrite(getName(), LongCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                                "local mode = redis.call('hget', KEYS[1], 'mode'); " +
                                "if (mode == false) then " +
                                    "redis.call('publish', KEYS[2], ARGV[1]); " +
                                    "return 1; " +
                                "end; " +
                                "local lockExists = redis.call('hexists', KEYS[1], ARGV[2]); " +
                                "if (lockExists == 0) then " +
                                    "return nil;" +
                                "else " +
                                    "local counter = redis.call('hincrby', KEYS[1], ARGV[2], -1); " +
                                    "redis.call('del', KEYS[1] .. ':timeout:' .. (counter+1)); " +
                                    "if (counter > 0) then " +
                                        "local maxRemainTime = -3; " + 
                                        "for i=counter, 1, -1 do " + 
                                            "local remainTime = redis.call('pttl', KEYS[1] .. ':timeout:' .. i); " + 
                                            "maxRemainTime = math.max(remainTime, maxRemainTime);" + 
                                        "end; " + 
                                        "if maxRemainTime > 0 then " +
                                            "redis.call('pexpire', KEYS[1], maxRemainTime); " + 
                                        "else " +
                                            "redis.call('hdel', KEYS[1], ARGV[2]); " +
                                            "if (redis.call('hlen', KEYS[1]) == 1) then " +
                                                "redis.call('del', KEYS[1]); " +
                                                "redis.call('publish', KEYS[2], ARGV[1]); " +
                                            "end; " +
                                        "end;" +
                                        "return 0; " +
                                    "else " +
                                        "redis.call('hdel', KEYS[1], ARGV[2]); " +
                                        "if (redis.call('hlen', KEYS[1]) == 1) then " +
                                            "redis.call('del', KEYS[1]); " +
                                            "redis.call('publish', KEYS[2], ARGV[1]); " +
                                        "end; " +
                                        "return 1; "+
                                    "end; " +
                                "end; " +
                                "return nil; ",
                        Arrays.<Object>asList(getName(), getChannelName()), LockPubSub.unlockMessage, getLockName(Thread.currentThread().getId()));
        if (opStatus == null) {
            throw new IllegalMonitorStateException("attempt to unlock read lock, not locked by current thread by node id: "
                    + id + " thread-id: " + Thread.currentThread().getId());
        }
        if (opStatus) {
            cancelExpirationRenewal();
        }
    }

    @Override
    public Condition newCondition() {
        throw new UnsupportedOperationException();
    }

    @Override
    public RFuture<Boolean> forceUnlockAsync() {
        RFuture<Boolean> result = commandExecutor.evalWriteAsync(getName(), LongCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                "if (redis.call('hget', KEYS[1], 'mode') == 'read') then " +
                    "redis.call('del', KEYS[1]); " +
                    "redis.call('publish', KEYS[2], ARGV[1]); " +
                    "return 1; " +
                "else " +
                    "return 0; " +
                "end;",
                Arrays.<Object>asList(getName(), getChannelName()), LockPubSub.unlockMessage);

          result.addListener(new FutureListener<Boolean>() {
              @Override
              public void operationComplete(Future<Boolean> future) throws Exception {
                  if (future.isSuccess() && future.getNow()) {
                      cancelExpirationRenewal();
                  }
              }
          });

          return result;
    }

    @Override
    public boolean isLocked() {
        String res = commandExecutor.write(getName(), StringCodec.INSTANCE, RedisCommands.HGET, getName(), "mode");
        return "read".equals(res);
    }

}
