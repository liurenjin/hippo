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
package org.hippoecm.repository.util;

import java.util.Arrays;

import javax.jcr.LoginException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NodeDefinition;
import javax.jcr.nodetype.NodeType;
import javax.jcr.nodetype.NodeTypeManager;

import org.onehippo.cms7.services.HippoServiceRegistry;
import org.onehippo.repository.RepositoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NodeInfo {

    private static NodeTypeManager nodeTypeManager;

    private final String name;

    private final NodeType nodeType;
    private final String nodeTypeName;
    private final NodeType[] mixinTypes;
    private final String[] mixinTypeNames;
    private final int index;

    /**
     * @deprecated since 2.26.13, use {@link #NodeInfo(String, int, javax.jcr.nodetype.NodeType, javax.jcr.nodetype.NodeType[])}
     */
    @Deprecated
    public NodeInfo(String name, int index, String nodeTypeName, String[] mixinTypeNames) {
        final Logger log = LoggerFactory.getLogger(NodeInfo.class);
        final String message = "This constructor is deprecated and very inefficient, please use alternative constructor";
        if (log.isDebugEnabled()) {
            log.warn(message, new Exception());
        } else {
            log.warn(message + ". Stack trace on debug level");
        }
        this.name = name;
        this.index = index;
        this.nodeTypeName = nodeTypeName;
        this.mixinTypeNames = mixinTypeNames;
        try {
            this.nodeType = getNodeTypeManager().getNodeType(nodeTypeName);
            this.mixinTypes = new NodeType[mixinTypeNames.length];
            for (int i = 0; i < mixinTypeNames.length; i++) {
                mixinTypes[i] = getNodeTypeManager().getNodeType(mixinTypeNames[i]);
            }
        } catch (RepositoryException e) {
            throw new IllegalStateException("Can't initialize types", e);
        }
    }

    public NodeInfo(String name, int index, NodeType nodeType, NodeType[] mixinTypes) {
        this.name = name;
        this.nodeType = nodeType;
        this.nodeTypeName = nodeType.getName();
        this.index = index;
        this.mixinTypes = mixinTypes;
        this.mixinTypeNames = new String[mixinTypes.length];
        for (int i = 0; i < mixinTypes.length; i++) {
            this.mixinTypeNames[i] = mixinTypes[i].getName();
        }
    }

    public NodeInfo(Node child) throws RepositoryException {
        this(child.getName(), child.getIndex(), JcrUtils.getPrimaryNodeType(child), JcrUtils.getMixinNodeTypes(child));
    }

    public String getName() {
        return name;
    }

    public int getIndex() {
        return index;
    }

    public NodeType getNodeType() { return nodeType; }

    public String getNodeTypeName() {
        return nodeTypeName;
    }

    public NodeType[] getMixinTypes() {
        return mixinTypes;
    }

    public String[] getMixinNames() {
        return mixinTypeNames;
    }

    public NodeDefinition getApplicableChildNodeDef(NodeType[] parentTypes) throws ConstraintViolationException {
        NodeDefinition residualDefinition = null;
        for (NodeType parentType : parentTypes) {
            for (NodeDefinition nodeDef : parentType.getChildNodeDefinitions()) {
                if (nodeDef.getName().equals(getName())) {
                    if (!hasRequiredPrimaryNodeType(nodeDef)) {
                        continue;
                    }
                    return nodeDef;
                } else if ("*".equals(nodeDef.getName())) {
                    if (!hasRequiredPrimaryNodeType(nodeDef)) {
                        continue;
                    }
                    residualDefinition = nodeDef;
                }
            }
        }
        if (residualDefinition != null) {
            return residualDefinition;
        }
        throw new ConstraintViolationException("Cannot set property " + this.getName());
    }

    public boolean hasApplicableChildNodeDef(NodeType[] parentTypes) {
        try {
            getApplicableChildNodeDef(parentTypes);
            return true;
        } catch (ConstraintViolationException e) {
            return false;
        }
    }

    private boolean hasRequiredPrimaryNodeType(final NodeDefinition definition) {
        for (String primaryNodeTypeName : definition.getRequiredPrimaryTypeNames()) {
            if (!nodeType.isNodeType(primaryNodeTypeName)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "NodeInfo[" + getName() + '[' + getIndex() + "](type=" + getNodeTypeName() + ", mixins=" + Arrays.toString(getMixinNames()) +")]";
    }

    private static NodeTypeManager getNodeTypeManager() throws RepositoryException {
        if (nodeTypeManager == null) {
            final RepositoryService repository = HippoServiceRegistry.getService(RepositoryService.class);
            nodeTypeManager = repository.login().getWorkspace().getNodeTypeManager();
        }
        return nodeTypeManager;
    }
}