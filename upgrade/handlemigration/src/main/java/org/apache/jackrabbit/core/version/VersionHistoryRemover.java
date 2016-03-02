/*
 *  Copyright 2012-2013 Hippo B.V. (http://www.onehippo.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.jackrabbit.core.version;

import javax.jcr.RepositoryException;
import javax.jcr.version.VersionHistory;

import org.hippoecm.repository.decorating.SessionDecorator;
import org.hippoecm.repository.decorating.VersionHistoryDecorator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VersionHistoryRemover {

    private static final Logger log = LoggerFactory.getLogger(VersionHistoryRemover.class);

    private VersionHistoryRemover() {
    }

    public static void removeVersionHistory(VersionHistory versionHistory) {
        try {
            final VersionHistoryImpl versionHistoryImpl = (VersionHistoryImpl) VersionHistoryDecorator.unwrap(versionHistory);
            final InternalVersionHistoryImpl internalVersionHistory = (InternalVersionHistoryImpl) versionHistoryImpl.getInternalVersionHistory();
            final InternalVersionManager versionManager = internalVersionHistory.getVersionManager();
            versionManager.removeVersionHistory(SessionDecorator.unwrap(versionHistory.getSession()), internalVersionHistory);
        } catch (RepositoryException e) {
            log.warn("History item not removed", e);
        }
    }

}
