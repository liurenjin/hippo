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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import javax.jcr.Binary;
import javax.jcr.ItemNotFoundException;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.ValueFactory;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.onehippo.cm.model.ConfigurationProperty;
import org.onehippo.cm.model.ContentDefinition;
import org.onehippo.cm.model.DefinitionProperty;
import org.onehippo.cm.model.ModelItem;
import org.onehippo.cm.model.ModelProperty;
import org.onehippo.cm.model.Value;
import org.onehippo.cm.model.ValueType;
import org.onehippo.cm.model.impl.DefinitionNodeImpl;
import org.onehippo.cm.model.impl.SourceImpl;
import org.onehippo.cm.model.impl.ValueImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static javax.jcr.PropertyType.BINARY;
import static javax.jcr.PropertyType.BOOLEAN;
import static javax.jcr.PropertyType.DATE;
import static javax.jcr.PropertyType.DECIMAL;
import static javax.jcr.PropertyType.DOUBLE;
import static javax.jcr.PropertyType.LONG;
import static javax.jcr.PropertyType.NAME;
import static javax.jcr.PropertyType.PATH;
import static javax.jcr.PropertyType.REFERENCE;
import static javax.jcr.PropertyType.STRING;
import static javax.jcr.PropertyType.URI;
import static javax.jcr.PropertyType.WEAKREFERENCE;
import static org.hippoecm.repository.HippoStdNodeType.HIPPOSTD_STATESUMMARY;
import static org.hippoecm.repository.api.HippoNodeType.HIPPO_PATHS;
import static org.hippoecm.repository.api.HippoNodeType.HIPPO_RELATED;

/**
 * Config {@link Value} -> JCR {@link javax.jcr.Value} converter
 */
public abstract class ValueProcessor {
    private static final Logger log = LoggerFactory.getLogger(ValueProcessor.class);
    private static final String[] knownDerivedPropertyNames = new String[] {
            HIPPO_RELATED,
            HIPPO_PATHS,
            HIPPOSTD_STATESUMMARY,
    };

    public static boolean isKnownDerivedPropertyName(final String modelPropertyName) {
        return ArrayUtils.contains(knownDerivedPropertyNames, modelPropertyName);
    }

    /**
     * Creates array of {@link javax.jcr.Value} based on {@link Value} list
     * @param modelValues - list of model values
     * @return
     * @throws Exception
     */
    public static javax.jcr.Value[] valuesFrom(final List<Value> modelValues, final Session session) throws RepositoryException, IOException {
        final javax.jcr.Value[] jcrValues = new javax.jcr.Value[modelValues.size()];
        for (int i = 0; i < jcrValues.length; i++) {
            jcrValues[i] = valueFrom(modelValues.get(i), session);
        }
        return jcrValues;
    }

    public static javax.jcr.Value[] valuesFrom(final ModelItem modelItem,
                                         final List<Value> modelValues,
                                         final Session session) throws RepositoryException, IOException {
        final javax.jcr.Value[] jcrValues = new javax.jcr.Value[modelValues.size()];
        for (int i = 0; i < jcrValues.length; i++) {
            jcrValues[i] = valueFrom(modelItem, modelValues.get(i), session);
        }
        return jcrValues;
    }

    public static javax.jcr.Value valueFrom(final ModelItem modelItem,
                                      final Value modelValue, final Session session)
            throws RepositoryException, IOException {

        final ValueType type = modelValue.getType();

        try {
            return valueFrom(modelValue, session);
        } catch (RuntimeException ex) {
                final String msg = String.format(
                        "Failed to process property '%s' defined in %s: unsupported value type '%s'.",
                        modelItem.getPath(), modelItem.getOrigin(), type);
                throw new RuntimeException(msg, ex);
        }
    }

    public static boolean valueIsIdentical(final ModelItem modelItem,
                                     final Value modelValue,
                                     final javax.jcr.Value jcrValue) throws RepositoryException, IOException {
        try {
            return valueIsIdentical(modelValue, jcrValue);
        } catch (RuntimeException e) {
            final String msg = String.format(
                    "Failed to process property '%s' defined in %s: unsupported value type '%s'.",
                    modelItem.getPath(), modelItem.getOrigin(), modelValue.getType());
            throw new RuntimeException(msg);
        }
    }

    /**
     * Creates {@link javax.jcr.Value} based on {@link Value}
     * @param modelValue - model value
     * @return {@link javax.jcr.Value}
     * @throws Exception
     */
    public static javax.jcr.Value valueFrom(final Value modelValue, final Session session) throws RepositoryException, IOException {
        final ValueFactory factory = session.getValueFactory();
        final ValueType type = modelValue.getType();

        switch (type) {
            case STRING:
                return factory.createValue(getStringValue(modelValue));
            case BINARY:
                final Binary binary = factory.createBinary(getBinaryInputStream(modelValue));
                try {
                    return factory.createValue(binary);
                } finally {
                    binary.dispose();
                }
            case LONG:
                return factory.createValue((Long) modelValue.getObject());
            case DOUBLE:
                return factory.createValue((Double) modelValue.getObject());
            case DATE:
                return factory.createValue((Calendar) modelValue.getObject());
            case BOOLEAN:
                return factory.createValue((Boolean) modelValue.getObject());
            case URI:
            case NAME:
            case PATH:
            case REFERENCE:
            case WEAKREFERENCE:
                // REFERENCE and WEAKREFERENCE type values already are resolved to hold a validated uuid
                return factory.createValue(modelValue.getString(), type.ordinal());
            case DECIMAL:
                return factory.createValue((BigDecimal) modelValue.getObject());
            default:
                DefinitionProperty parentProperty = modelValue.getParent();
                final String msg = String.format(
                        "Failed to process property '%s' defined in %s: unsupported value type '%s'.",
                        parentProperty.getPath(), parentProperty.getOrigin(), type);
                throw new RuntimeException(msg);
        }
    }

    private static boolean valueIsIdentical(final Value modelValue,
                                     final javax.jcr.Value jcrValue) throws RepositoryException, IOException {
        if (modelValue.getType().ordinal() != jcrValue.getType()) {
            return false;
        }

        switch (modelValue.getType()) {
            case STRING:
                return getStringValue(modelValue).equals(jcrValue.getString());
            case BINARY:
                try (final InputStream modelInputStream = getBinaryInputStream(modelValue)) {
                    final Binary jcrBinary = jcrValue.getBinary();
                    try (final InputStream jcrInputStream = jcrBinary.getStream()) {
                        return IOUtils.contentEquals(modelInputStream, jcrInputStream);
                    } finally {
                        jcrBinary.dispose();
                    }
                }
            case LONG:
                return modelValue.getObject().equals(jcrValue.getLong());
            case DOUBLE:
                return modelValue.getObject().equals(jcrValue.getDouble());
            case DATE:
                return modelValue.getObject().equals(jcrValue.getDate());
            case BOOLEAN:
                return modelValue.getObject().equals(jcrValue.getBoolean());
            case URI:
            case NAME:
            case PATH:
            case REFERENCE:
            case WEAKREFERENCE:
                // REFERENCE and WEAKREFERENCE type values already are resolved to hold a validated uuid
                return modelValue.getString().equals(jcrValue.getString());
            case DECIMAL:
                return modelValue.getObject().equals(jcrValue.getDecimal());
            default:
                final String msg = String.format(
                        "Failed to process property '%s' defined in %s: unsupported value type '%s'.",
                        modelValue.getParent().getPath(), modelValue.getParent().getOrigin(), modelValue.getType());
                throw new RuntimeException(msg);
        }
    }

    public static boolean valueIsIdentical(final Value v1, final Value v2) throws IOException {
        // Type equality at the property level is sufficient, no need to check for type equality at value level.

        switch (v1.getType()) {
            case STRING:
                return getStringValue(v1).equals(getStringValue(v2));
            case BINARY:
                try (final InputStream v1InputStream = getBinaryInputStream(v1);
                     final InputStream v2InputStream = getBinaryInputStream(v2)) {
                    return IOUtils.contentEquals(v1InputStream, v2InputStream);
                }
            case URI:
            case NAME:
            case PATH:
            case REFERENCE:
            case WEAKREFERENCE:
                return v1.getString().equals(v2.getString());
            default:
                return v1.getObject().equals(v2.getObject());
        }
    }

    public static boolean propertyIsIdentical(final ModelProperty p1, final ModelProperty p2) throws IOException {
        return p1.getType() == p2.getType()
                && p1.getValueType() == p2.getValueType()
                && valuesAreIdentical(p1, p2);
    }

    public static boolean valuesAreIdentical(final ModelProperty p1, final ModelProperty p2) throws IOException {
        if (p1.isMultiple()) {
            final Value[] v1 = p1.getValues();
            final Value[] v2 = p2.getValues();

            if (v1.length != v2.length) {
                return false;
            }
            for (int i = 0; i < v1.length; i++) {
                if (!valueIsIdentical(v1[i], v2[i])) {
                    return false;
                }
            }
            return true;
        }
        return valueIsIdentical(p1.getValue(), p2.getValue());
    }

    private static String getStringValue(final Value modelValue) throws IOException {
        if (modelValue.isResource()) {
            try (final InputStream inputStream = modelValue.getResourceInputStream()) {
                return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
            }
        } else {
            return modelValue.getString();
        }
    }

    private static InputStream getBinaryInputStream(final Value modelValue) throws IOException {
        return modelValue.isResource() ? modelValue.getResourceInputStream() : new ByteArrayInputStream((byte[]) modelValue.getObject());
    }

    public static ValueImpl[] valuesFrom(final Property property, final DefinitionNodeImpl definitionNode) throws RepositoryException {
        final javax.jcr.Value[] jcrValues = property.getValues();
        final ValueImpl[] values = new ValueImpl[jcrValues.length];
        for (int i = 0; i < jcrValues.length; i++) {
            values[i] = valueFrom(property, jcrValues[i], i, definitionNode);
        }
        return values;
    }

    public static ValueImpl valueFrom(final Property property, final DefinitionNodeImpl definitionNode) throws RepositoryException {
        return valueFrom(property, property.getValue(), 0, definitionNode);
    }

    private static ValueImpl valueFrom(final Property property, final javax.jcr.Value jcrValue, final int valueIndex,
                                final DefinitionNodeImpl definitionNode) throws RepositoryException {

        String indexPostfix = "";
        if (property.isMultiple()) {
            indexPostfix = Integer.toString(valueIndex);
        }
        switch (jcrValue.getType()) {
            case STRING:
                return new ValueImpl(jcrValue.getString());
            case BINARY:
                // TODO: we need a JCR based ValueFileMapper to derive a sensible resource path *here*
                // TODO: without we only can do a dumb default mapping, using 'data.bin' or 'data[valueIndex].bin'
                ValueImpl valueImpl = new ValueImpl("data"+indexPostfix+".bin", ValueType.BINARY, true, false);
                SourceImpl source = definitionNode.getDefinition().getSource();
                JcrResourceInputProvider jcrResourceInputProvider =
                        (JcrResourceInputProvider)source.getModule().getConfigResourceInputProvider();
                valueImpl.setInternalResourcePath(jcrResourceInputProvider.createResourcePath(property, valueIndex));
                valueImpl.setNewResource(true);
                return valueImpl;
            case LONG:
                return new ValueImpl(jcrValue.getLong());
            case DOUBLE:
                return new ValueImpl(jcrValue.getDouble());
            case DATE:
                return new ValueImpl(jcrValue.getDate());
            case BOOLEAN:
                return new ValueImpl(jcrValue.getBoolean());
            case URI:
            case NAME:
            case PATH:
            case REFERENCE:
            case WEAKREFERENCE:
                // REFERENCE and WEAKREFERENCE type values already are resolved to hold a validated uuid
                return new ValueImpl(jcrValue.getString());
            case DECIMAL:
                return new ValueImpl(jcrValue.getDecimal());
            default:
                final String msg = String.format("Unsupported jcrValue type '%s'.", jcrValue.getType());
                throw new RuntimeException(msg);
        }
    }

    public static List<Value> determineVerifiedValues(final ModelProperty property, final Session session)
            throws RepositoryException {

        final List<Value> verifiedValues = new ArrayList<>();
        if (property.isMultiple()) {
            for (Value value : property.getValues()) {
                collectVerifiedValue(property, value, verifiedValues, session);
            }
        } else {
            collectVerifiedValue(property, property.getValue(), verifiedValues, session);
        }
        return verifiedValues;
    }

    public static void collectVerifiedValue(final ModelProperty modelProperty, final Value value, final List<Value> modelValues, final Session session)
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

    public static boolean isReferenceTypeProperty(final ModelProperty modelProperty) {
        return (modelProperty.getValueType() == ValueType.REFERENCE ||
                modelProperty.getValueType() == ValueType.WEAKREFERENCE);
    }

    protected static String getVerifiedReferenceIdentifier(final ModelProperty modelProperty, final Value modelValue, final Session session)
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
                log.warn(String.format("Path reference '%s' for property '%s' defined in %s not found: skipping.",
                        nodePath, modelProperty.getPath(), modelProperty.getOrigin()));
                return null;
            }
        } else {
            try {
                session.getNodeByIdentifier(identifier);
            } catch (ItemNotFoundException e) {
                log.warn(String.format("Reference %s for property '%s' defined in %s not found: skipping.",
                        identifier, modelProperty.getPath(), modelProperty.getOrigin()));
                return null;
            }
        }
        return identifier;
    }

    public static boolean isUuidInUse(final String uuid, final Session session) throws RepositoryException {
        try {
            session.getNodeByIdentifier(uuid);
            return true;
        } catch (ItemNotFoundException e) {
            return false;
        }
    }

    public static boolean propertyIsIdentical(final Property jcrProperty, final ModelProperty modelProperty)
            throws RepositoryException, IOException {
        return propertyIsIdentical(jcrProperty, modelProperty,
                determineVerifiedValues(modelProperty, jcrProperty.getSession()));
    }

    public static boolean propertyIsIdentical(final Property jcrProperty, final ModelProperty modelProperty,
                                              final List<Value> modelValues) throws RepositoryException, IOException {
        return modelProperty.getValueType().ordinal() == jcrProperty.getType()
                && modelProperty.isMultiple() == jcrProperty.isMultiple()
                && valuesAreIdentical(modelProperty, modelValues, jcrProperty);
    }

    public static boolean valuesAreIdentical(final ModelProperty modelProperty, final List<Value> modelValues,
                                             final Property jcrProperty) throws RepositoryException, IOException {
        if (modelProperty.isMultiple()) {
            final javax.jcr.Value[] jcrValues = jcrProperty.getValues();
            if (modelValues.size() != jcrValues.length) {
                return false;
            }
            for (int i = 0; i < jcrValues.length; i++) {
                if (!valueIsIdentical(modelProperty, modelValues.get(i), jcrValues[i])) {
                    return false;
                }
            }
            return true;
        } else {
            if (modelValues.size() > 0) {
                return valueIsIdentical(modelProperty, modelProperty.getValue(), jcrProperty.getValue());
            } else {
                // No modelValue indicates that a reference failed verification (of UUID or path).
                // We leave the current reference (existing or not) unchanged and return true to
                // short-circuit further processing of this modelProperty.
                return true;
            }
        }
    }
}