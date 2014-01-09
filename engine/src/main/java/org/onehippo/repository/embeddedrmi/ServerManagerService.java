/*
 * Copyright 2012-2013 Hippo B.V. (http://www.onehippo.com)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *  http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onehippo.repository.embeddedrmi;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import javax.jcr.LoginException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.onehippo.repository.JackrabbitRepository;
import org.onehippo.repository.ManagerServiceFactory;

public class ServerManagerService extends UnicastRemoteObject implements RemoteManagerService {
    JackrabbitRepository repository;
    ServerDocumentManager documentManager = null;
    ServerWorkflowManager workflowManager = null;
    int port;

    public ServerManagerService(JackrabbitRepository repository, int port) throws RemoteException {
        this.repository = repository;
        this.port = port;
    }

    public RemoteDocumentManager getDocumentManager(String sessionName) throws RepositoryException, RemoteException {
        if (documentManager == null) {
            Session localSession = repository.getSession(sessionName);
            documentManager = new ServerDocumentManager(ManagerServiceFactory.getManagerService(localSession).getDocumentManager());
        }
        return documentManager;
    }

    public RemoteWorkflowManager getWorkflowManager(String sessionName) throws RepositoryException, RemoteException {
        try {
            if (workflowManager == null) {
                Session localSession = repository.getSession(sessionName);
                workflowManager = new ServerWorkflowManager(ManagerServiceFactory.getManagerService(localSession).getWorkflowManager(), port);
            }
            return workflowManager;
        } catch (LoginException ex) {
            throw ex;
        } catch (RepositoryException ex) {
            throw ex;
        }
    }
}