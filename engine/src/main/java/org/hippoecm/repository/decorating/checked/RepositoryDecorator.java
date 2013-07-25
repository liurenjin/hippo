/*
 *  Copyright 2008 Hippo.
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
package org.hippoecm.repository.decorating.checked;

import javax.jcr.Credentials;
import javax.jcr.LoginException;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;

/**
 * Simple {@link Repository Repository} decorator.
 */
public class RepositoryDecorator implements Repository {
    @SuppressWarnings("unused")
    private static final String SVN_ID = "$Id$";

    private DecoratorFactory factory;

    private Repository repository;

    protected RepositoryDecorator(DecoratorFactory factory, Repository repository) {
        this.factory = factory;
        this.repository = repository;
    }

    public static Repository unwrap(Repository repository) {
        if (repository == null) {
            return null;
        }
        if (repository instanceof RepositoryDecorator) {
            ((RepositoryDecorator)repository).check();
            repository = ((RepositoryDecorator)repository).repository;
        }
        return repository;
    }

    /**
     * Forwards the method call to the underlying repository.
     */
    public String[] getDescriptorKeys() {
        check();
        return repository.getDescriptorKeys();
    }

    /**
     * Forwards the method call to the underlying repository.
     */
    public String getDescriptor(String key) {
        check();
        return repository.getDescriptor(key);
    }

    /**
     * Forwards the method call to the underlying repository. The returned
     * session is wrapped into a session decorator using the decorator factory.
     *
     * @return decorated session
     */
    public Session login(Credentials credentials, String workspaceName) throws LoginException,
            NoSuchWorkspaceException, RepositoryException {
        check();
        Session session = repository.login(credentials, workspaceName);
        return factory.getSessionDecorator(this, session, credentials, workspaceName);
    }

    /**
     * Calls <code>login(credentials, null)</code>.
     *
     * @return decorated session
     * @see #login(Credentials, String)
     */
    public Session login(Credentials credentials) throws LoginException, NoSuchWorkspaceException, RepositoryException {
        check();
        return login(credentials, null);
    }

    /**
     * Calls <code>login(null, workspaceName)</code>.
     *
     * @return decorated session
     * @see #login(Credentials, String)
     */
    public Session login(String workspaceName) throws LoginException, NoSuchWorkspaceException, RepositoryException {
        check();
        return login(null, workspaceName);
    }

    /**
     * Calls <code>login(null, null)</code>.
     *
     * @return decorated session
     * @see #login(Credentials, String)
     */
    public Session login() throws LoginException, NoSuchWorkspaceException, RepositoryException {
        check();
        return login(null, null);
    }

    public boolean isStandardDescriptor(String key) {
        check();
        return repository.isStandardDescriptor(key);
    }

    public boolean isSingleValueDescriptor(String key) {
        check();
        return repository.isSingleValueDescriptor(key);
    }

    public Value getDescriptorValue(String key) {
        check();
        return repository.getDescriptorValue(key);
    }

    public Value[] getDescriptorValues(String key) {
        check();
        return repository.getDescriptorValues(key);
    }

    protected void check() {
    }
}