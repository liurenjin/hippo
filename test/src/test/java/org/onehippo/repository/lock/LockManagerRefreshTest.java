/*
 * Copyright 2017 Hippo B.V. (http://www.onehippo.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onehippo.repository.lock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.Test;
import org.onehippo.cms7.services.lock.LockException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.onehippo.repository.lock.db.DbLockManager.CREATE_STATEMENT;
import static org.onehippo.repository.lock.db.DbLockManager.TABLE_NAME_LOCK;

public class LockManagerRefreshTest extends AbstractLockManagerTest {


    @Test
    public void locks_gets_refreshed_while_still_in_use() throws Exception {
        if (dataSource == null) {
            // db test only
            return;
        }
        final String key = "123";
        final LockRunnable runnable = new LockRunnable(key, true, 60);
        final Thread lockThread = new Thread(runnable);

        lockThread.start();
        // give lockThread time to lock
        Thread.sleep(100);

        dbRowAssertion(key, "RUNNING", CLUSTER_NODE_ID, lockThread.getName());

        setExpireTime(key, 10);
        long expires = getExpireTime(key);
        assertTrue("Expires time expected to be in the future", expires > System.currentTimeMillis());

        // within 5 seconds the DbLockRefresher must have refreshed the expires time
        long start = System.currentTimeMillis();
        while (expires == getExpireTime(key)) {
            if ((System.currentTimeMillis() - start) > 10000) {
                fail("Within 5 seconds DbLockRefresher should had bumped the expires time but it didn't do it after 6 seconds");
            }
            Thread.sleep(100);
        }

        runnable.keepAlive = false;
        // after the thread is finished, the lock manager should have no locks any more
        lockThread.join();
    }

    @Test
    public void only_running_or_aborting_locks_for_current_cluster_node_get_refreshed() throws Exception {
        if (dataSource == null) {
            // db test only
            return;
        }
        final String key1 = "123";
        final LockRunnable runnable = new LockRunnable(key1, true, 60);
        final Thread lockThread = new Thread(runnable);

        lockThread.start();

        final String key2 = "456";
        final LockRunnable runnable2 = new LockRunnable(key2, true, 60, true);
        final Thread lockThread2 = new Thread(runnable2);

        lockThread2.start();
        // give lockThreads time to lock
        Thread.sleep(100);

        // Now create manually one more database row: one that has RUNNING status for other cluster node : this one should not be refreshed
        long expirationTimeOtherClusterNodeLock = System.currentTimeMillis() + 10000;
        String key3 = "abc";
        insertDataRowLock(key3, "otherClusterNode", "otherThread", expirationTimeOtherClusterNodeLock);

        setExpireTime(key1, 10);
        long expiresBefore1 = getExpireTime(key1);
        setExpireTime(key2, 10);
        long expiresBefore2 = getExpireTime(key2);

        // above we have created a running lock for current cluster. Now add one in status ABORT (which we keep in status
        // ABORT by not unlocking in the interrupt and keeping the Thread alive and thus REFRESH should update the expiration time
        lockManager.abort("456");


        // within 5 seconds the DbLockRefresher must have refreshed the expires1 time
        long start = System.currentTimeMillis();
        while (expiresBefore1 == getExpireTime(key1)) {
            if ((System.currentTimeMillis() - start) > 10000) {
                fail("Within 5 seconds DbLockRefresher should had bumped the expires1 time but it didn't do it after 6 seconds");
            }
            Thread.sleep(100);
        }

        // assert the ABORT lock also has been updated for its expirationTime
        long expire2After = getExpireTime(key2);
        assertFalse("The expiration time of the row in state ABORT should had been updated. Even though it is in state ABORT," +
                " as long as the containing Thread is alive and did not unlock, the lock should be refreshed", expiresBefore2 == expire2After);


        long expireAfter3 = getExpireTime(key3);
        assertEquals("The expiration time of a lock in possession of a different cluster node should not be refreshed",
                expirationTimeOtherClusterNodeLock, expireAfter3);
    }



    public static final String GET_EXPIRE_STATEMENT = "SELECT * FROM " + TABLE_NAME_LOCK  + " WHERE lockKey=?";
    public static final String SET_EXPIRE_STATEMENT = "UPDATE " + TABLE_NAME_LOCK  + " SET expirationTime=? WHERE lockKey=?";

    private long getExpireTime(final String key) throws SQLException {
        try (Connection connection = dataSource.getConnection()){
            final PreparedStatement getExpireStatement = connection.prepareStatement(GET_EXPIRE_STATEMENT);
            getExpireStatement.setString(1, key);
            ResultSet resultSet = getExpireStatement.executeQuery();
            assertTrue("Should had one db result",resultSet.next());
            return resultSet.getLong("expirationTime");
        }
    }

    private void setExpireTime(final String key, final int expireInSeconds) throws SQLException {
        try (Connection connection = dataSource.getConnection()){
            final PreparedStatement setExpireStatement = connection.prepareStatement(SET_EXPIRE_STATEMENT);
            setExpireStatement.setLong(1, System.currentTimeMillis() + expireInSeconds * 1000);
            setExpireStatement.setString(2, key);
            int changed = setExpireStatement.executeUpdate();
            assertEquals("setExpireTime should had modified 1 row", 1, changed);
        }
    }

    protected class LockRunnable implements Runnable {

        private String key;
        private volatile boolean keepAlive;
        private int refreshRateSeconds;
        private boolean ignoreInterruption;

        LockRunnable(final String key , final boolean keepAlive,
                     final int refreshRateSeconds) {
            this(key, keepAlive, refreshRateSeconds, false);
        }

        LockRunnable(final String key , final boolean keepAlive,
                     final int refreshRateSeconds, final boolean ignoreInterruption) {
            this.key = key;
            this.keepAlive = keepAlive;
            this.refreshRateSeconds = refreshRateSeconds;
            this.ignoreInterruption = ignoreInterruption;
        }

        @Override
        public void run() {
            try {
                lockManager.lock(key, refreshRateSeconds);
                while (keepAlive) {
                    Thread.sleep(25);
                }
            } catch (LockException | InterruptedException e) {
                if (e instanceof InterruptedException && ignoreInterruption) {
                    while (keepAlive) {
                        try {
                            // reset the interrupted status
                            Thread.sleep(25);
                        } catch (InterruptedException e1) {
                            // ignore : Can be interrupted again by the LockThreadInterrupter
                        }
                    }
                }
                fail(e.toString());
            }
        }
    }



}
