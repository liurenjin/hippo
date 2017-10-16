/*
 * Copyright 2015-2017 Hippo B.V. (http://www.onehippo.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onehippo.repository.locking;


import javax.jcr.RepositoryException;
import javax.jcr.lock.Lock;
import javax.jcr.lock.LockException;

/**
 * JCR locking is deprecated, use {@link org.onehippo.cms7.services.lock.LockManager} instead. Creating a (cluster wide)
 * lock with {@link org.onehippo.cms7.services.lock.LockManager} can be achieved as follows:
 * <code>
 *     <pre>
 *        final LockManager lockManager = HippoServiceRegistry.getService(LockManager.class);
 *        try {
 *            lockManager.lock(key);
 *            // do locked work
 *        } catch (LockException e) {
 *            log.info("{} already locked", key);
 *        } finally {
 *            lockManager.unlock(key);
 *        }
 *     </pre>
 * </code>
 * @deprecated since 5.0.3
 */
@Deprecated
public interface HippoLock extends Lock {

    /**
     * Starts a keep-alive that refreshes the lock before expiring.
     * The lock timeout must be more than 10 seconds.
     *
     * @throws LockException if the lock is not live, has no timeout, or has a timeout of less than 10 seconds
     * @throws RepositoryException if another error occurs
     */
    void startKeepAlive() throws LockException, RepositoryException;

    /**
     * Stops the previously started keep-alive on this lock.
     * If no keep-alive is active on this lock this method has no effect.
     */
    void stopKeepAlive();

}
