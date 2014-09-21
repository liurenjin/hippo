/*
 *  Copyright 2008-2014 Hippo B.V. (http://www.onehippo.com)
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
package org.hippoecm.repository.gallery.impl;

import java.io.Serializable;
import java.rmi.RemoteException;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.nodetype.NodeType;

import org.hippoecm.repository.api.Document;
import org.hippoecm.repository.api.NodeNameCodec;
import org.hippoecm.repository.ext.InternalWorkflow;
import org.hippoecm.repository.gallery.GalleryWorkflow;

import static org.hippoecm.repository.api.HippoNodeType.HIPPO_AVAILABILITY;
import static org.hippoecm.repository.api.HippoNodeType.HIPPO_DISCRIMINATOR;
import static org.hippoecm.repository.api.HippoNodeType.HIPPO_PATHS;
import static org.hippoecm.repository.api.HippoNodeType.NT_HANDLE;
import static org.onehippo.repository.util.JcrConstants.JCR_DATA;
import static org.onehippo.repository.util.JcrConstants.JCR_LAST_MODIFIED;
import static org.onehippo.repository.util.JcrConstants.JCR_MIME_TYPE;
import static org.onehippo.repository.util.JcrConstants.MIX_REFERENCEABLE;
import static org.onehippo.repository.util.JcrConstants.MIX_VERSIONABLE;
import static org.onehippo.repository.util.JcrConstants.NT_BASE;

public class GalleryWorkflowImpl implements InternalWorkflow, GalleryWorkflow {

    private Session rootSession;
    private Node subject;

    public GalleryWorkflowImpl(Session userSession, Session rootSession, Node subject) throws RemoteException {
        this.subject = subject;
        this.rootSession = rootSession;
    }

    public Map<String,Serializable> hints() {
        return null;
    }

    public List<String> getGalleryTypes() throws RemoteException, RepositoryException {
        List<String> list = new LinkedList<String>();
        Value[] values = subject.getProperty("hippostd:gallerytype").getValues();
        for (final Value value : values) {
            list.add(value.getString());
        }
        return list;
    }

    public Document createGalleryItem(String name, String type) throws RemoteException, RepositoryException {
        Node document, node, folder = rootSession.getNodeByIdentifier(subject.getIdentifier());
        Calendar timestamp = Calendar.getInstance();
        timestamp.setTime(new Date());
        name = NodeNameCodec.encode(name);
        node = folder.addNode(name, NT_HANDLE);
        node.addMixin(MIX_REFERENCEABLE);
        node.setProperty(HIPPO_DISCRIMINATOR, new Value[0]);
        node = document = node.addNode(name, type);
        node.addMixin(MIX_VERSIONABLE);
        node.setProperty(HIPPO_AVAILABILITY, new String[] { "live", "preview" });
        node.setProperty(HIPPO_PATHS, new String[0]);

        NodeType primaryType = node.getPrimaryNodeType();
        String primaryItemName = primaryType.getPrimaryItemName();
        while (primaryItemName == null && !NT_BASE.equals(primaryType.getName())) {
            for (NodeType nt : primaryType.getSupertypes()) {
                if (nt.getPrimaryItemName() != null) {
                    primaryItemName = nt.getPrimaryItemName();
                    break;
                }
                if (nt.isNodeType(NT_BASE)) {
                    primaryType = nt;
                }
            }
        }
        if (primaryItemName != null) {
            if (!node.hasNode(primaryItemName)) {
                node = node.addNode(primaryItemName);
            } else {
                node = node.getNode(primaryItemName);
            }
            node.setProperty(JCR_DATA, "");
            node.setProperty(JCR_MIME_TYPE, "application/octet-stream");
            node.setProperty(JCR_LAST_MODIFIED, timestamp);
        } else {
            throw new ItemNotFoundException("No primary item definition found");
        }
        folder.save();
        return new Document(document);
    }
}
