/*
 *  Copyright 2008-2016 Hippo B.V. (http://www.onehippo.com)
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
package org.hippoecm.repository.decorating;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Calendar;
import javax.jcr.Binary;
import javax.jcr.ItemNotFoundException;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFormatException;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.PropertyDefinition;
import javax.jcr.version.VersionException;

/**
 */
public class PropertyDecorator extends ItemDecorator implements Property {

    protected final Property property;

    protected PropertyDecorator(DecoratorFactory factory, Session session, Property property) {
        super(factory, session, property);
        this.property = property;
    }

    /**
     * @inheritDoc
     */
    public void setValue(Value value) throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        property.setValue(value);
    }

    /**
     * @inheritDoc
     */
    public void setValue(Value[] values) throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        property.setValue(values);
    }

    /**
     * @inheritDoc
     */
    public void setValue(String s) throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        property.setValue(s);
    }

    /**
     * @inheritDoc
     */
    public void setValue(String[] strings) throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        property.setValue(strings);
    }

    /**
     * @inheritDoc
     */
    public void setValue(InputStream inputStream) throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        property.setValue(inputStream);
    }

    /**
     * @inheritDoc
     */
    public void setValue(long l) throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        property.setValue(l);
    }

    /**
     * @inheritDoc
     */
    public void setValue(double v) throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        property.setValue(v);
    }

    /**
     * @inheritDoc
     */
    public void setValue(Calendar calendar) throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        property.setValue(calendar);
    }

    /**
     * @inheritDoc
     */
    public void setValue(boolean b) throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        property.setValue(b);
    }

    /**
     * @inheritDoc
     */
    public void setValue(Node node) throws ValueFormatException, VersionException, LockException,
            ConstraintViolationException, RepositoryException {
        property.setValue(NodeDecorator.unwrap(node));
    }

    /**
     * @inheritDoc
     */
    public Value getValue() throws ValueFormatException, RepositoryException {
        return property.getValue();
    }

    /**
     * @inheritDoc
     */
    public Value[] getValues() throws ValueFormatException, RepositoryException {
        return property.getValues();
    }

    /**
     * @inheritDoc
     */
    public String getString() throws ValueFormatException, RepositoryException {
        return property.getString();
    }

    /**
     * @inheritDoc
     */
    public InputStream getStream() throws ValueFormatException, RepositoryException {
        return property.getStream();
    }

    /**
     * @inheritDoc
     */
    public long getLong() throws ValueFormatException, RepositoryException {
        return property.getLong();
    }

    /**
     * @inheritDoc
     */
    public double getDouble() throws ValueFormatException, RepositoryException {
        return property.getDouble();
    }

    /**
     * @inheritDoc
     */
    public Calendar getDate() throws ValueFormatException, RepositoryException {
        return property.getDate();
    }

    /**
     * @inheritDoc
     */
    public boolean getBoolean() throws ValueFormatException, RepositoryException {
        return property.getBoolean();
    }

    /**
     * @inheritDoc
     */
    public Node getNode() throws ValueFormatException, RepositoryException {
        return factory.getNodeDecorator(session, property.getNode());
    }

    /**
     * @inheritDoc
     */
    public long getLength() throws ValueFormatException, RepositoryException {
        return property.getLength();
    }

    /**
     * @inheritDoc
     */
    public long[] getLengths() throws ValueFormatException, RepositoryException {
        return property.getLengths();
    }

    /**
     * @inheritDoc
     */
    public PropertyDefinition getDefinition() throws RepositoryException {
        return property.getDefinition();
    }

    /**
     * @inheritDoc
     */
    public int getType() throws RepositoryException {
        return property.getType();
    }

    public void setValue(Binary value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        property.setValue(value);
    }

    public void setValue(BigDecimal value) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
        property.setValue(value);
    }

    public Binary getBinary() throws ValueFormatException, RepositoryException {
        return property.getBinary();
    }

    public BigDecimal getDecimal() throws ValueFormatException, RepositoryException {
        return property.getDecimal();
    }

    public Property getProperty() throws ItemNotFoundException, ValueFormatException, RepositoryException {
        return factory.getPropertyDecorator(session, property.getProperty());
    }

    public boolean isMultiple() throws RepositoryException {
        return property.isMultiple();
    }
}
