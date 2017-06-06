/*
 *  Copyright 2017 Hippo B.V. (http://www.onehippo.com)
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
package org.onehippo.cm.engine;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.jcr.ItemExistsException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeType;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.jackrabbit.core.NodeImpl;
import org.hippoecm.repository.api.HippoNode;
import org.hippoecm.repository.api.NodeNameCodec;
import org.hippoecm.repository.decorating.NodeDecorator;
import org.hippoecm.repository.util.JcrUtils;
import org.hippoecm.repository.util.NodeIterable;
import org.hippoecm.repository.util.PropertyIterable;
import org.onehippo.cm.model.ActionType;
import org.onehippo.cm.model.ConfigurationItemCategory;
import org.onehippo.cm.model.ContentDefinition;
import org.onehippo.cm.model.DefinitionNode;
import org.onehippo.cm.model.DefinitionProperty;
import org.onehippo.cm.model.DefinitionType;
import org.onehippo.cm.model.PropertyOperation;
import org.onehippo.cm.model.PropertyType;
import org.onehippo.cm.model.Value;
import org.onehippo.cm.model.ValueType;
import org.onehippo.cm.model.impl.AbstractDefinitionImpl;
import org.onehippo.cm.model.impl.ConfigDefinitionImpl;
import org.onehippo.cm.model.impl.ConfigSourceImpl;
import org.onehippo.cm.model.impl.ConfigurationModelImpl;
import org.onehippo.cm.model.impl.ConfigurationNodeImpl;
import org.onehippo.cm.model.impl.ConfigurationPropertyImpl;
import org.onehippo.cm.model.impl.ContentDefinitionImpl;
import org.onehippo.cm.model.impl.ContentSourceImpl;
import org.onehippo.cm.model.impl.DefinitionNodeImpl;
import org.onehippo.cm.model.impl.DefinitionPropertyImpl;
import org.onehippo.cm.model.impl.GroupImpl;
import org.onehippo.cm.model.impl.ModuleImpl;
import org.onehippo.cm.model.impl.ProjectImpl;
import org.onehippo.cm.model.impl.ValueImpl;
import org.onehippo.cm.model.util.SnsUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.jackrabbit.JcrConstants.JCR_MIXINTYPES;
import static org.apache.jackrabbit.JcrConstants.JCR_PRIMARYTYPE;
import static org.apache.jackrabbit.JcrConstants.JCR_UUID;
import static org.hippoecm.repository.HippoStdNodeType.HIPPOSTD_STATESUMMARY;
import static org.hippoecm.repository.api.HippoNodeType.HIPPO_PATHS;
import static org.hippoecm.repository.api.HippoNodeType.HIPPO_RELATED;
import static org.onehippo.cm.model.ActionType.APPEND;
import static org.onehippo.cm.model.ActionType.DELETE;
import static org.onehippo.cm.model.Constants.YAML_EXT;
import static org.onehippo.cm.model.ValueType.REFERENCE;
import static org.onehippo.cm.model.ValueType.WEAKREFERENCE;
import static org.onehippo.cm.model.util.SnsUtils.createIndexedName;

/**
 * Applies definition nodes to JCR
 */
public class JcrContentProcessingService {

    private static final Logger logger = LoggerFactory.getLogger(JcrContentProcessingService.class);

    private final ValueProcessor valueProcessor;
    private final Collection<Pair<DefinitionProperty, Node>> unprocessedReferences = new ArrayList<>();

    private static final String[] knownDerivedPropertyNames = new String[]{
            HIPPO_RELATED,
            HIPPO_PATHS,
            HIPPOSTD_STATESUMMARY
    };

    public JcrContentProcessingService(final ValueProcessor valueProcessor) {
        this.valueProcessor = valueProcessor;
    }

    /**
     * Import definition under the rootNode
     *
     * @param modelNode
     * @param parentNode
     */
    public synchronized void importNode(final DefinitionNode modelNode, final Node parentNode, final ActionType actionType) throws RepositoryException, IOException {
        if (actionType == DELETE) {
            throw new IllegalArgumentException("DELETE action is not supported for import operation");
        }

        final DefinitionNodeImpl newNode = constructNewParentNode(modelNode, parentNode.getPath());

        final Session session = parentNode.getSession();
        validateAppendAction(newNode.getPath(), actionType, session);

        applyNode(newNode, parentNode, actionType);
    }

    /**
     * Validate if node exists and current action type is APPEND
     * @param nodePath Node path
     * @param actionType current action type
     * @param session current session
     * @throws RepositoryException if node exists and action type is APPEND
     */
    private void validateAppendAction(final String nodePath, final ActionType actionType, final Session session) throws RepositoryException {
        final boolean nodeExists = session.nodeExists(nodePath);
        if (nodeExists && actionType == APPEND) {
            throw new ItemExistsException(String.format("Node already exists at path %s", nodePath));
        }
    }

    /**
     * Create new definition node under given path path
     * @param modelNode {@link DefinitionNode} source node
     * @param path new path
     * @return Node under the new path
     */
    private DefinitionNodeImpl constructNewParentNode(final DefinitionNode modelNode, final String path) throws RepositoryException {

        final String newPath = constructNodePath(path, modelNode.getName());

        final DefinitionNodeImpl node = (DefinitionNodeImpl) modelNode;
        final DefinitionNodeImpl newNode = new DefinitionNodeImpl(newPath, modelNode.getName(), node.getDefinition());
        newNode.getModifiableNodes().putAll(node.getModifiableNodes());
        newNode.getModifiableProperties().putAll(node.getModifiableProperties());
        newNode.setOrderBefore(node.getOrderBefore());
        newNode.setIgnoreReorderedChildren(node.getIgnoreReorderedChildren());
        return newNode;
    }

    private String constructNodePath(final String path, final String nodeName) {
        return path.equals("/") ? "/" + nodeName : path + "/" + nodeName;
    }

    /**
     * Export specified node
     *
     * @param node
     * @return
     */
    public ModuleImpl exportNode(final Node node) throws RepositoryException {
        final ModuleImpl module = new ModuleImpl("export-module", new ProjectImpl("export-project", new GroupImpl("export-group")));
        module.setConfigResourceInputProvider(new JcrResourceInputProvider(node.getSession()));
        module.setContentResourceInputProvider(module.getConfigResourceInputProvider());
        final ContentSourceImpl contentSource = module.addContentSource(NodeNameCodec.decode(node.getName())+YAML_EXT);
        final ContentDefinitionImpl contentDefinition = contentSource.addContentDefinition();

        exportNode(node, contentDefinition);

        return module;
    }

    public DefinitionNodeImpl exportNode(final Node node, final ContentDefinitionImpl contentDefinition) throws RepositoryException {
        if (isVirtual(node)) {
            throw new ConfigurationRuntimeException("Virtual node cannot be exported: " + node.getPath());
        }

        // Creating a definition with path 'rooted' at the node itself, without possible SNS index: we're not supporting indexed path elements
        final DefinitionNodeImpl definitionNode = new DefinitionNodeImpl("/"+node.getName(), node.getName(), contentDefinition);
        contentDefinition.setNode(definitionNode);

        processProperties(node, definitionNode);

        for (final Node childNode : new NodeIterable(node.getNodes())) {
            exportNode(childNode, definitionNode);
        }
        return definitionNode;
    }

    public DefinitionNodeImpl exportNode(final Node sourceNode, final DefinitionNodeImpl parentNode) throws RepositoryException {

        if (!isVirtual(sourceNode)) {
            final DefinitionNodeImpl definitionNode = parentNode.addNode(createNodeName(sourceNode));

            processProperties(sourceNode, definitionNode);

            for (final Node childNode : new NodeIterable(sourceNode.getNodes())) {
                exportNode(childNode, definitionNode);
            }
            return definitionNode;
        }
        return null;
    }

    private boolean isVirtual(final Node node) throws RepositoryException {
        return ((HippoNode)node).isVirtual();
    }

    private String createNodeName(final Node sourceNode) throws RepositoryException {
        final String name = sourceNode.getName();
        if (sourceNode.getIndex() > 1) {
            return name+"["+sourceNode.getIndex()+"]";
        } else {
            if (sourceNode.getDefinition().allowsSameNameSiblings() && sourceNode.getParent().hasNode(name+"[2]")) {
                return name+"[1]";
            }
        }
        return name;
    }

    private void processProperties(final Node sourceNode, final DefinitionNodeImpl definitionNode) throws RepositoryException {

        processPrimaryTypeAndMixins(sourceNode, definitionNode);

        for (final Property property : new PropertyIterable(sourceNode.getProperties())) {
            if (property.getName().equals(JCR_PRIMARYTYPE) || property.getName().equals(JCR_MIXINTYPES)) {
                continue; //Already processed those properties
            }

            if (isKnownDerivedPropertyName(property.getName())) {
                continue;
            }

            exportProperty(property, definitionNode);
        }
    }

    private void exportProperty(final Property property, DefinitionNodeImpl definitionNode) throws RepositoryException {
        if (property.isMultiple()) {
            definitionNode.addProperty(property.getName(), ValueType.fromJcrType(property.getType()),
                    valueProcessor.valuesFrom(property, definitionNode));
        } else {
            final ValueImpl value = valueProcessor.valueFrom(property, definitionNode);
            final DefinitionPropertyImpl targetProperty = definitionNode.addProperty(property.getName(), value);
            value.setParent(targetProperty);
        }
    }

    private void processPrimaryTypeAndMixins(final Node sourceNode, final DefinitionNodeImpl definitionNode) throws RepositoryException {

        final Property primaryTypeProperty = sourceNode.getProperty(JCR_PRIMARYTYPE);
        final ValueImpl value = valueProcessor.valueFrom(primaryTypeProperty, definitionNode);
        definitionNode.addProperty(primaryTypeProperty.getName(), value);

        final NodeType[] mixinNodeTypes = sourceNode.getMixinNodeTypes();
        if (mixinNodeTypes.length > 0) {
            final List<ValueImpl> values = new ArrayList<>();
            for (final NodeType mixinNodeType : mixinNodeTypes) {
                values.add(new ValueImpl(mixinNodeType.getName()));
            }
            definitionNode.addProperty(JCR_MIXINTYPES, ValueType.STRING, values.toArray(new ValueImpl[values.size()]));
        }
    }

    public void exportConfigNodeDelta(final Session session, final String jcrPath, final ConfigSourceImpl configSource,
                                final ConfigurationModelImpl model) throws RepositoryException {

        ConfigurationNodeImpl configNode = model.getConfigurationRootNode();
        Node jcrNode = session.getRootNode();

        boolean deletedNode = false;
        final String[] pathSegments = jcrPath.substring(1).split("/");
        int pathIndex = 0;
        for (; pathIndex < pathSegments.length; pathIndex++) {
            final String childName = pathSegments[pathIndex];

            if (childName.contains("[")) {
                // we cannot create a definition path for an indexed name
                break;
            }

            final String indexedChildName = createIndexedName(childName);
            if (!jcrNode.hasNode(indexedChildName)) {
                deletedNode = true;
                break;
            }

            configNode = configNode.getNode(indexedChildName);
            if (configNode == null) {
                // not a delta: no config defined yet
                break;
            }

            jcrNode = jcrNode.getNode(indexedChildName);
        }
        final String definitionNodePath = jcrNode.getPath() + (deletedNode ? "/" + pathSegments[pathIndex] : "");
        DefinitionNodeImpl definitionNode = getOrCreateConfigDefinitionNodeForPath(definitionNodePath, configSource);

        if (deletedNode) {
            definitionNode.setDelete(true);
            return;
        }

        if (configNode != null) {
            for (; pathIndex < pathSegments.length; pathIndex++) {
                final String childName = pathSegments[pathIndex];

                DefinitionNodeImpl childDefNode = definitionNode.getNodes().get(childName);
                if (childDefNode == null) {
                    childDefNode = definitionNode.addNode(childName);
                }
                definitionNode = childDefNode;

                final String indexedChildName = createIndexedName(childName);
                if (!jcrNode.hasNode(indexedChildName)) {
                    deletedNode = true;
                    break;
                }

                configNode = configNode.getNode(indexedChildName);
                if (configNode == null) {
                    // not a delta: no config defined yet
                    break;
                }
            }
        }
        if (deletedNode) {
            definitionNode.setDelete(true);
            return;
        }

        if (configNode == null) {
            processProperties(jcrNode, definitionNode);
            for (final Node childNode : new NodeIterable(jcrNode.getNodes())) {
                exportNode(childNode, definitionNode);
            }
        } else {
            exportConfigNodeDelta(jcrNode, definitionNode, configNode);
        }
        if (definitionNode.isEmpty()) {
            // TODO: remove empty delta definitionNode as the change seemingly was a false positive
        }
    }

    private DefinitionNodeImpl getOrCreateConfigDefinitionNodeForPath(final String path, final ConfigSourceImpl configSource) {
        ConfigDefinitionImpl definition = null;
        for (AbstractDefinitionImpl def : configSource.getModifiableDefinitions()) {
            if (def.getType() == DefinitionType.CONFIG) {
                ConfigDefinitionImpl configDef = (ConfigDefinitionImpl)def;
                if (path.startsWith(configDef.getNode().getPath())) {
                    if (definition == null) {
                        definition = configDef;
                    } else if (configDef.getNode().getPath().length() > definition.getNode().getPath().length()) {
                        definition = configDef;
                    }
                }
            }
        }
        if (definition == null) {
            definition = configSource.addConfigDefinition();
            definition.setNode(new DefinitionNodeImpl(path, StringUtils.substringAfterLast(path, "/"), definition));
        }
        return definition.getNode();
    }

    private void exportPropertiesDelta(final Node jcrNode, final DefinitionNodeImpl definitionNode,
                                       final ConfigurationNodeImpl configNode) throws RepositoryException {

        exportPrimaryTypeDelta(jcrNode, definitionNode, configNode);
        exportMixinsDelta(jcrNode, definitionNode, configNode);

        for (final Property jcrProperty : new PropertyIterable(jcrNode.getProperties())) {
            if (jcrProperty.getName().equals(JCR_PRIMARYTYPE) || jcrProperty.getName().equals(JCR_MIXINTYPES)) {
                continue;
            }
            if (isKnownDerivedPropertyName(jcrProperty.getName())) {
                continue;
            }
            if (configNode.getChildPropertyCategory(jcrProperty.getName()) != ConfigurationItemCategory.CONFIG) {
                // skip RUNTIME property
                continue;
            }
            ConfigurationPropertyImpl configProperty = configNode.getProperty(jcrProperty.getName());
            if (configProperty == null) {
                // full export
                exportProperty(jcrProperty, definitionNode);
            } else {
                exportPropertyDelta(jcrProperty, configProperty, definitionNode);
            }
        }
    }

    private void exportPrimaryTypeDelta(final Node jcrNode, final DefinitionNodeImpl definitionNode,
                                        final ConfigurationNodeImpl configNode) throws RepositoryException {
        if (!jcrNode.getPrimaryNodeType().getName().equals(configNode)) {
            final Property primaryTypeProperty = jcrNode.getProperty(JCR_PRIMARYTYPE);
            final ValueImpl value = valueProcessor.valueFrom(primaryTypeProperty, definitionNode);
            definitionNode.addProperty(primaryTypeProperty.getName(), value).setOperation(PropertyOperation.OVERRIDE);
        }
    }

    private void exportMixinsDelta(final Node jcrNode, final DefinitionNodeImpl definitionNode,
                                   final ConfigurationNodeImpl configNode) throws RepositoryException {
        final ConfigurationPropertyImpl mixinsProperty = configNode.getProperty(JCR_MIXINTYPES);
        final NodeType[] mixinNodeTypes = jcrNode.getMixinNodeTypes();
        if (mixinNodeTypes.length > 0) {
            final Set<String> jcrMixins = new HashSet<>();
            for (final NodeType mixinNodeType : mixinNodeTypes) {
                jcrMixins.add(mixinNodeType.getName());
            }
            PropertyOperation op = null;
            if (mixinsProperty != null) {
                if (Arrays.stream(mixinsProperty.getValues()).anyMatch(v -> !jcrMixins.contains(v.getString()))) {
                    op = PropertyOperation.OVERRIDE;
                } else {
                    Arrays.stream(mixinsProperty.getValues()).forEach(v->jcrMixins.remove(v.getString()));
                    if (!jcrMixins.isEmpty()) {
                        op = PropertyOperation.ADD;
                    }
                }
            }
            if (!jcrMixins.isEmpty()) {
                DefinitionPropertyImpl propertyDef = definitionNode .addProperty(JCR_MIXINTYPES,
                        ValueType.STRING,jcrMixins.stream().map(ValueImpl::new).toArray(ValueImpl[]::new));
                if (op != null) {
                    propertyDef.setOperation(op);
                }
            }
        } else if (mixinsProperty != null) {
            definitionNode.addProperty(JCR_MIXINTYPES, ValueType.STRING, new ValueImpl[0])
                    .setOperation(PropertyOperation.DELETE);
        }
    }

    private void exportPropertyDelta(final Property property, final ConfigurationPropertyImpl configProperty,
                                     DefinitionNodeImpl definitionNode) throws RepositoryException {
        // TODO: export property delta (replace or add)
    }

    private void exportConfigNodeDelta(final Node jcrNode, final DefinitionNodeImpl parentDefinitionNode,
                                       final ConfigurationNodeImpl configNode) throws RepositoryException {

        // TODO: depth-first export of delta, to prevent creating unnecessary empty children when the change is only
        // TODO  on a high(er) path

//        final DefinitionNodeImpl definitionNode = parentDefinitionNode.addNode(createNodeName(jcrNode));

        // TODO: create 'dummy' wrapper definitionNode which only creates the real definitionNode (and possible
        // TODO  intermediate parents 'on demand', when needed
        final DefinitionNodeImpl wrapperDefinitionNode = null;

        exportPropertiesDelta(jcrNode, wrapperDefinitionNode, configNode);

        // TODO: compute child nodes delta first, see also the 'inverse' at ConfigurationConfigService.computeAndWriteChildNodesDelta(...)
        //       ...
        // TODO  then recursing into child processing itself, something like below
        //       ...
        /*
        for (final Node childNode : new NodeIterable(jcrNode.getNodes())) {
            final ConfigurationNodeImpl childConfigNode = configNode.getNode(createIndexedName(childNode));
            if (childConfigNode == null) {
                processProperties(childNode, wrapperDefinitionNode);
                exportNode(childNode, definitionNode);
            } else {
                exportConfigNodeDelta(childNode, wrapperDefinitionNode, childConfigNode);
            }
        }
        */
    }

    /**
     * Append definition node using specified action strategy
     *
     * @param definitionNode
     * @param actionType
     * @throws RepositoryException
     */
    public synchronized void apply(final DefinitionNode definitionNode, final ActionType actionType, final Session session) throws RepositoryException {
        if (actionType == null) {
            throw new IllegalArgumentException("Action type cannot be null");
        }

        try {
            validateAppendAction(definitionNode.getPath(), actionType, session);
            if (actionType == DELETE && !session.nodeExists(definitionNode.getPath())) {
                return;
            }

            final Node parentNode = calculateParentNode(definitionNode, session);
            applyNode(definitionNode, parentNode, actionType);
            applyUnprocessedReferences();
        } catch (Exception e) {
            logger.error(String.format("Content definition processing failed: %s", definitionNode.getName()), e);
            if (e instanceof RepositoryException) {
                throw (RepositoryException) e;
            } else {
                throw new RuntimeException(e);
            }
        }
    }

    private Node calculateParentNode(DefinitionNode definitionNode, final Session session) throws RepositoryException {
        String path = Paths.get(definitionNode.getPath()).getParent().toString();
        return session.getNode(path);
    }

    private void applyNode(final DefinitionNode definitionNode, final Node parentNode, final ActionType actionType) throws RepositoryException, IOException {

        final Session session = parentNode.getSession();
        final String nodePath = constructNodePath(parentNode.getPath(), definitionNode.getName());
        final boolean nodeExists = session.nodeExists(nodePath);

        if (nodeExists) {
            switch (actionType) {
                case APPEND:
                    //Happens only in case when subnode is autocreated
                case RELOAD:
                    session.getNode(nodePath).remove();
                    break;
                case DELETE:
                    session.getNode(nodePath).remove();
                    return;
                default:
                    throw new IllegalArgumentException(String.format("Action type '%s' is not supported", actionType));
            }
        }

        final Node jcrNode = addNode(parentNode, definitionNode);
        applyProperties(definitionNode, jcrNode);
        applyChildNodes(definitionNode, jcrNode, actionType);
    }

    private void applyChildNodes(final DefinitionNode modelNode, final Node jcrNode, final ActionType actionType) throws RepositoryException, IOException {
        logger.debug(String.format("processing node '%s' defined in %s.", modelNode.getPath(), modelNode.getOrigin()));
        for (final String name : modelNode.getNodes().keySet()) {
            final DefinitionNode modelChild = modelNode.getNode(name);
            applyNode(modelChild, jcrNode, actionType);
        }
    }

    /**
     * Adding a child node with optionally a configured jcr:uuid
     * <p>
     * If a configured uuid already is in use, a warning will be logged and a new jcr:uuid will be generated instead.
     * </p>
     *
     * @param parentNode the parent node for the child node
     * @param modelNode  the configuration for the child node
     * @return the new JCR Node
     * @throws Exception
     */
    private Node addNode(final Node parentNode, final DefinitionNode modelNode) throws RepositoryException {
        final String name = SnsUtils.getUnindexedName(modelNode.getName());
        final String primaryType = getPrimaryType(modelNode);
        final DefinitionProperty uuidProperty = modelNode.getProperty(JCR_UUID);
        if (uuidProperty != null) {
            final String uuid = uuidProperty.getValue().getString();
            if (!isUuidInUse(uuid, parentNode.getSession())) {
                // uuid not in use: create node with the requested uuid
                final NodeImpl parentNodeImpl = (NodeImpl) NodeDecorator.unwrap(parentNode);
                return parentNodeImpl.addNodeWithUuid(name, primaryType, uuid);
            }
            logger.warn(String.format("Specified jcr:uuid %s for node '%s' defined in %s already in use: "
                            + "a new jcr:uuid will be generated instead.",
                    uuid, modelNode.getPath(), modelNode.getOrigin()));
        }
        // create node with a new uuid
        return parentNode.addNode(name, primaryType);
    }

    private boolean isUuidInUse(final String uuid, final Session session) throws RepositoryException {
        try {
            session.getNodeByIdentifier(uuid);
            return true;
        } catch (ItemNotFoundException e) {
            return false;
        }
    }

    private String getPrimaryType(final DefinitionNode modelNode) {
        if (modelNode.getProperty(JCR_PRIMARYTYPE) == null) {
            final String msg = String.format(
                    "Failed to process node '%s' defined in %s: cannot add child node '%s': %s property missing.",
                    modelNode.getPath(), modelNode.getOrigin(), modelNode.getPath(), JCR_PRIMARYTYPE);
            throw new RuntimeException(msg);
        }

        return modelNode.getProperty(JCR_PRIMARYTYPE).getValue().getString();
    }

    private void applyProperties(final DefinitionNode source, final Node targetNode) throws RepositoryException, IOException {
        applyPrimaryAndMixinTypes(source, targetNode);

        for (DefinitionProperty modelProperty : source.getProperties().values()) {
            if (isReferenceTypeProperty(modelProperty)) {
                unprocessedReferences.add(Pair.of(modelProperty, targetNode));
            } else {
                applyProperty(modelProperty, targetNode);
            }
        }
    }

    private void applyPrimaryAndMixinTypes(final DefinitionNode source, final Node target) throws RepositoryException {
        final List<String> jcrMixinTypes = Arrays.stream(target.getMixinNodeTypes())
                .map(NodeType::getName)
                .collect(Collectors.toList());

        final List<String> modelMixinTypes = new ArrayList<>();
        final DefinitionProperty modelProperty = source.getProperty(JCR_MIXINTYPES);
        if (modelProperty != null) {
            for (Value value : modelProperty.getValues()) {
                modelMixinTypes.add(value.getString());
            }
        }

        for (String modelMixinType : modelMixinTypes) {
            if (jcrMixinTypes.contains(modelMixinType)) {
                jcrMixinTypes.remove(modelMixinType);
            } else {
                target.addMixin(modelMixinType);
            }
        }

        final String modelPrimaryType = source.getProperty(JCR_PRIMARYTYPE).getValue().getString();
        final String jcrPrimaryType = target.getPrimaryNodeType().getName();
        if (!jcrPrimaryType.equals(modelPrimaryType)) {
            target.setPrimaryType(modelPrimaryType);
        }

        for (String mixinType : jcrMixinTypes) {
            target.removeMixin(mixinType);
        }
    }

    private void applyProperty(final DefinitionProperty modelProperty, final Node jcrNode) throws RepositoryException, IOException {
        final Property jcrProperty = JcrUtils.getPropertyIfExists(jcrNode, modelProperty.getName());

        if (jcrProperty != null && jcrProperty.getDefinition().isProtected()) {
            return;
        }

        if (isKnownDerivedPropertyName(modelProperty.getName())) {
            return;
        }

        final List<Value> modelValues = new ArrayList<>();
        if (modelProperty.getType() == PropertyType.SINGLE) {
            collectVerifiedValue(modelProperty, modelProperty.getValue(), modelValues, jcrNode.getSession());
        } else {
            for (Value value : modelProperty.getValues()) {
                collectVerifiedValue(modelProperty, value, modelValues, jcrNode.getSession());
            }
        }

        try {
            if (modelProperty.getType() == PropertyType.SINGLE) {
                if (modelValues.size() > 0) {
                    jcrNode.setProperty(modelProperty.getName(), valueProcessor.valueFrom(modelValues.get(0), jcrNode.getSession()));
                }
            } else {
                jcrNode.setProperty(modelProperty.getName(), valueProcessor.valuesFrom(modelValues, jcrNode.getSession()));
            }
        } catch (RepositoryException e) {
            String msg = String.format(
                    "Failed to process property '%s' defined in %s: %s",
                    modelProperty.getPath(), modelProperty.getOrigin(), e.getMessage());
            throw new RuntimeException(msg, e);
        }
    }

    private void collectVerifiedValue(final DefinitionProperty modelProperty, final Value value, final List<Value> modelValues,
                                      final Session session)
            throws RepositoryException {
        if (isReferenceTypeProperty(modelProperty)) {
            final String uuid = getVerifiedReferenceIdentifier(modelProperty, value, session);
            if (uuid != null) {
                modelValues.add(new VerifiedReferenceValue(value, uuid));
            }
        } else {
            modelValues.add(value);
        }
    }

    private String getVerifiedReferenceIdentifier(final DefinitionProperty modelProperty,
                                                  final Value modelValue,
                                                  final Session session)
            throws RepositoryException {
        String identifier = modelValue.getString();
        if (modelValue.isPath()) {
            String nodePath = identifier;
            if (!nodePath.startsWith("/")) {
                // path reference is relative to content definition root path
                final String rootPath = ((ContentDefinition) modelValue.getParent().getDefinition()).getNode().getPath();
                final StringBuilder pathBuilder = new StringBuilder(rootPath);
                if (!StringUtils.EMPTY.equals(nodePath)) {
                    if (!"/".equals(rootPath)) {
                        pathBuilder.append("/");
                    }
                    pathBuilder.append(nodePath);
                }
                nodePath = pathBuilder.toString();
            }
            // lookup node identifier by node path
            try {
                identifier = session.getNode(nodePath).getIdentifier();
            } catch (PathNotFoundException e) {
                logger.warn(String.format("Path reference '%s' for property '%s' defined in %s not found: skipping.",
                        nodePath, modelProperty.getPath(), modelProperty.getOrigin()));
                return null;
            }
        } else {
            try {
                session.getNodeByIdentifier(identifier);
            } catch (ItemNotFoundException e) {
                logger.warn(String.format("Reference %s for property '%s' defined in %s not found: skipping.",
                        identifier, modelProperty.getPath(), modelProperty.getOrigin()));
                return null;
            }
        }
        return identifier;
    }


    private boolean isReferenceTypeProperty(final DefinitionProperty modelProperty) {
        return (modelProperty.getValueType() == REFERENCE || modelProperty.getValueType() == WEAKREFERENCE);
    }

    private boolean isKnownDerivedPropertyName(final String modelPropertyName) {
        return ArrayUtils.contains(knownDerivedPropertyNames, modelPropertyName);
    }

    private void applyUnprocessedReferences() throws Exception {
        for (Pair<DefinitionProperty, Node> unprocessedReference : unprocessedReferences) {
            final DefinitionProperty DefinitionProperty = unprocessedReference.getLeft();
            final Node jcrNode = unprocessedReference.getRight();
            applyProperty(DefinitionProperty, jcrNode);
        }
    }
}
