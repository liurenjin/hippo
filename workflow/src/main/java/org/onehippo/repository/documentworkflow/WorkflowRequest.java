/*
 *  Copyright 2008-2017 Hippo B.V. (http://www.onehippo.com)
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
package org.onehippo.repository.documentworkflow;

import java.util.Date;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.hippoecm.repository.HippoStdPubWfNodeType;
import org.hippoecm.repository.api.Document;

/**
 * WorkflowRequest provides the model object for a Hippo document workflow request operation to the DocumentWorkflow
 * SCXML state machine.
 */
public class WorkflowRequest extends Request {

    public WorkflowRequest(Node node) throws RepositoryException {
        super(node);
    }

    public WorkflowRequest(String type, Node sibling, DocumentVariant document, String username) throws RepositoryException {
        super(Request.newRequestNode(sibling.getParent()));
        setStringProperty(HippoStdPubWfNodeType.HIPPOSTDPUBWF_TYPE, type);
        setStringProperty(HippoStdPubWfNodeType.HIPPOSTDPUBWF_USERNAME, username);
        if (document != null) {
            getCheckedOutNode().setProperty(HippoStdPubWfNodeType.HIPPOSTDPUBWF_DOCUMENT, document.getNode());
        }
    }

    public String getWorkflowType() throws RepositoryException {
        String type = getType();
        if (type == null) {
            return null;
        }
        switch (type) {
            case HippoStdPubWfNodeType.SCHEDPUBLISH:
                return HippoStdPubWfNodeType.PUBLISH;
            case HippoStdPubWfNodeType.SCHEDDEPUBLISH:
                return HippoStdPubWfNodeType.DEPUBLISH;
            case HippoStdPubWfNodeType.SCHEDPUBLISHDEPUBLISH:
                return HippoStdPubWfNodeType.PUBLISHDEPUBLISH;
            default:
                return type;
        }
    }

    public String getOwner() throws RepositoryException {
        return getStringProperty(HippoStdPubWfNodeType.HIPPOSTDPUBWF_USERNAME);
    }

    public void setRejected(DocumentVariant stale, String reason) throws RepositoryException {
        setStringProperty(HippoStdPubWfNodeType.HIPPOSTDPUBWF_TYPE, HippoStdPubWfNodeType.REJECTED);
        if (stale != null) {
            setNodeProperty(HippoStdPubWfNodeType.HIPPOSTDPUBWF_DOCUMENT, stale.getNode());
        } else {
            setNodeProperty(HippoStdPubWfNodeType.HIPPOSTDPUBWF_DOCUMENT, null);
        }
        if (reason != null && reason.length() == 0) {
            // empty reason should not be stored.
            reason = null;
        }
        setStringProperty(HippoStdPubWfNodeType.HIPPOSTDPUBWF_REASON, reason);
    }

    public void setRejected(String reason) throws RepositoryException {
        setRejected(null, reason);
    }

    public Document getReference() throws RepositoryException {
        if (hasNode() && getNode().hasProperty(HippoStdPubWfNodeType.HIPPOSTDPUBWF_DOCUMENT)) {
            return new Document(getNode().getProperty(HippoStdPubWfNodeType.HIPPOSTDPUBWF_DOCUMENT).getNode());
        }
        return null;
    }
}
