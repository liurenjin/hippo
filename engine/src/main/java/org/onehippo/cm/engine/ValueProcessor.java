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
import java.util.Calendar;
import java.util.List;

import javax.jcr.Binary;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.ValueFactory;

import org.apache.commons.io.IOUtils;
import org.onehippo.cm.model.DefinitionProperty;
import org.onehippo.cm.model.ModelItem;
import org.onehippo.cm.model.Value;
import org.onehippo.cm.model.ValueType;
import org.onehippo.cm.model.impl.DefinitionNodeImpl;
import org.onehippo.cm.model.impl.SourceImpl;
import org.onehippo.cm.model.impl.ValueImpl;

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

/**
 * Config {@link Value} -> JCR {@link javax.jcr.Value} converter
 */
public class ValueProcessor {

    /**
     * Creates array of {@link javax.jcr.Value} based on {@link Value} list
     * @param modelValues - list of model values
     * @return
     * @throws Exception
     */
    public javax.jcr.Value[] valuesFrom(final List<Value> modelValues, final Session session) throws RepositoryException, IOException {
        final javax.jcr.Value[] jcrValues = new javax.jcr.Value[modelValues.size()];
        for (int i = 0; i < jcrValues.length; i++) {
            jcrValues[i] = valueFrom(modelValues.get(i), session);
        }
        return jcrValues;
    }

    public javax.jcr.Value[] valuesFrom(final ModelItem modelItem,
                                         final List<Value> modelValues,
                                         final Session session) throws RepositoryException, IOException {
        final javax.jcr.Value[] jcrValues = new javax.jcr.Value[modelValues.size()];
        for (int i = 0; i < jcrValues.length; i++) {
            jcrValues[i] = valueFrom(modelItem, modelValues.get(i), session);
        }
        return jcrValues;
    }

    public javax.jcr.Value valueFrom(final ModelItem modelItem,
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

    public boolean valueIsIdentical(final ModelItem modelItem,
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
    public javax.jcr.Value valueFrom(final Value modelValue, final Session session) throws RepositoryException, IOException {
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

    private boolean valueIsIdentical(final Value modelValue,
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

    public boolean valueIsIdentical(final Value v1, final Value v2) throws IOException {
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

    private String getStringValue(final Value modelValue) throws IOException {
        if (modelValue.isResource()) {
            try (final InputStream inputStream = modelValue.getResourceInputStream()) {
                return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
            }
        } else {
            return modelValue.getString();
        }
    }

    private InputStream getBinaryInputStream(final Value modelValue) throws IOException {
        return modelValue.isResource() ? modelValue.getResourceInputStream() : new ByteArrayInputStream((byte[]) modelValue.getObject());
    }

    public ValueImpl[] valuesFrom(final Property property, final DefinitionNodeImpl definitionNode) throws RepositoryException {
        final javax.jcr.Value[] jcrValues = property.getValues();
        final ValueImpl[] values = new ValueImpl[jcrValues.length];
        for (int i = 0; i < jcrValues.length; i++) {
            values[i] = valueFrom(property, jcrValues[i], i, definitionNode);
        }
        return values;
    }

    public ValueImpl valueFrom(final Property property, final DefinitionNodeImpl definitionNode) throws RepositoryException {
        return valueFrom(property, property.getValue(), 0, definitionNode);
    }

    private ValueImpl valueFrom(final Property property, final javax.jcr.Value jcrValue, final int valueIndex,
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
}