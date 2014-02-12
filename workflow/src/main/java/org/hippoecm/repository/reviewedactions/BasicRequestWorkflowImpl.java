/*
 *  Copyright 2014 Hippo B.V. (http://www.onehippo.com)
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
package org.hippoecm.repository.reviewedactions;

import java.io.Serializable;
import java.rmi.RemoteException;
import java.util.Collections;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.hippoecm.repository.api.WorkflowException;

/**
 * @deprecated since CMS 7.9, use/configure {@link org.onehippo.repository.documentworkflow.DocumentWorkflowImpl} instead.
 */
@Deprecated
public class BasicRequestWorkflowImpl extends AbstractReviewedActionsWorkflow implements BasicRequestWorkflow {

    public BasicRequestWorkflowImpl() throws RemoteException {
    }

    @Override
    public Map<String, Serializable> hints() throws WorkflowException {
        Map<String, Serializable> hints = super.hints();
        try {
            Node node = getNode();
            String id = node.getIdentifier();
            Map<String, Map<String, Serializable>> requests = (Map<String, Map<String, Serializable>>) hints.get("requests");
            if (requests != null) {
                hints = requests.get(id);
            }
            else {
                hints = Collections.EMPTY_MAP;
            }
            return hints;
        } catch (RepositoryException e) {
            throw new WorkflowException("Unable to build request hints", e);
        }
    }

    // BasicRequestWorkflow implementation

    @Override
    public void cancelRequest() throws WorkflowException, RemoteException, RepositoryException {
        documentWorkflow.cancelRequest(getNode().getIdentifier());
    }

}
