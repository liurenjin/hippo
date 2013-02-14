/*
 *  Copyright 2013 Hippo B.V. (http://www.onehippo.com)
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
package org.onehippo.repository.scheduling;

import java.util.Map;

import javax.jcr.Credentials;
import javax.jcr.LoginException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

/**
 * Context object containing operational information and helper objects for use by
 * {@link RepositoryJob}s to do their job.
 */
public class RepositoryJobExecutionContext {

    private final Session systemSession;
    private final Map<String, String> attributes;

    public RepositoryJobExecutionContext(Session systemSession, Map<String, String> attributes) {
        this.systemSession = systemSession;
        this.attributes = attributes;
    }

    /**
     * Get a session with the given credentials.
     */
    public Session getSession(Credentials credentials) throws LoginException, RepositoryException {
        return systemSession.impersonate(credentials);
    }

    /**
     * Get an attribute value. You can pass attributes to this context object through
     * {@link RepositoryJobInfo#addAttribute(String, String)}.
     * @param name  the name of the attribute.
     * @return  the attribute value associated with name.
     */
    public String getAttribute(String name) {
        return attributes.get(name);
    }
}
