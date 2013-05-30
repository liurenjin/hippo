/*
 *  Copyright 2013 Hippo.
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
package org.hippoecm.repository.upgrade;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.ValueFormatException;

import org.apache.commons.compress.utils.IOUtils;
import org.hippoecm.repository.ext.UpdaterContext;
import org.hippoecm.repository.ext.UpdaterItemVisitor;
import org.hippoecm.repository.ext.UpdaterModule;
import org.hippoecm.repository.impl.WorkflowManagerImpl;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class Upgrader22a implements UpdaterModule {

    private static final Logger log = LoggerFactory.getLogger(Upgrader22a.class);

    private static final String INVOCATION_CLASSNAME = WorkflowManagerImpl.WorkflowInvocationImpl.class.getName();
    private static final String OLD_CLASS_RESOURCE = "/org/hippoecm/repository/impl/ReadObjectWorkflowManagerImpl$WorkflowInvocationImpl.klass";
    private static final String NEW_CLASS_RESOURCE = "/org/hippoecm/repository/impl/WriteObjectWorkflowManagerImpl$WorkflowInvocationImpl.klass";


    @Override
    public void register(final UpdaterContext context) {
        context.registerName("repository-upgrade-v22a");
        context.registerStartTag("v19a");
        context.registerEndTag("v22a");
        context.registerVisitor(new UpdaterItemVisitor.QueryVisitor("//element(*, hipposched:job)", "xpath") {

            private boolean disabled = false;

            @Override
            protected void leaving(final Node node, final int level) throws RepositoryException {
                if (disabled) {
                    log.debug("Skipping upgrading serialized data " + node.getPath());
                }

                try {

                    final byte[] oldClass = readClass(OLD_CLASS_RESOURCE);
                    final byte[] newClass = readClass(NEW_CLASS_RESOURCE);

                    final Class oldInvocation = new ClassLoader(this.getClass().getClassLoader()) {
                        @Override
                        public Class<?> loadClass(String name) throws ClassNotFoundException {
                            if (INVOCATION_CLASSNAME.equals(name)) {
                                Class c = findLoadedClass(name);
                                if (c == null) {
                                    c = defineClass(name, oldClass, 0, oldClass.length);
                                }
                                return c;
                            }
                            return super.loadClass(name);
                        }
                    }.loadClass(INVOCATION_CLASSNAME);

                    final Class newInvocation = new ClassLoader(this.getClass().getClassLoader()) {
                        @Override
                        public Class<?> loadClass(String name) throws ClassNotFoundException {
                            if (INVOCATION_CLASSNAME.equals(name)) {
                                Class c = findLoadedClass(name);
                                if (c == null) {
                                    c = defineClass(name, newClass, 0, newClass.length);
                                }
                                return c;
                            }
                            return super.loadClass(name);
                        }
                    }.loadClass(INVOCATION_CLASSNAME);

                    final Property property = node.getProperty("hipposched:data");
                    final InputStream stream = property.getBinary().getStream();
                    ObjectInputStream ois = new ClassloaderOverridingObjectInputStream(oldInvocation, stream);

                    final JobDetail detail = (JobDetail) ois.readObject();
                    ois.close();

                    final JobDataMap jobDataMap = detail.getJobDataMap();
                    Map read = (Map) jobDataMap.get("invocation");
                    Map write = (Map) newInvocation.newInstance();
                    for (Object o : read.keySet()) {
                        write.put(o, read.get(o));
                    }
                    jobDataMap.put("invocation", write);

                    final byte[] buf = objectToBytes(detail);
                    property.setValue(new ByteArrayInputStream(buf));

                } catch (InvalidClassException e) {
                    log.debug("Not upgrading pre 7.7.9 version: " + e.toString());
                    disabled = true;
                } catch (Exception e) {
                    log.error("Failed to upgrade serialized quartz job", e);
                }

            }

            private byte[] readClass(String resource) throws IOException {
                final InputStream stream = getClass().getResourceAsStream(resource);
                final ByteArrayOutputStream output = new ByteArrayOutputStream();
                IOUtils.copy(stream, output);
                stream.close();
                return output.toByteArray();
            }

            private byte[] objectToBytes(Object o) throws RepositoryException {
                try {
                    ByteArrayOutputStream store = new ByteArrayOutputStream();
                    ObjectOutputStream ostream = new ObjectOutputStream(store);
                    ostream.writeObject(o);
                    ostream.flush();
                    ostream.close();
                    return store.toByteArray();
                } catch (IOException ex) {
                    throw new ValueFormatException(ex);
                }
            }

        });
    }

    private static class ClassloaderOverridingObjectInputStream extends ObjectInputStream {

        private Class cl;

        public ClassloaderOverridingObjectInputStream(Class cl, InputStream stream) throws IOException {
            super(stream);
            this.cl = cl;
        }

        @Override
        protected Class<?> resolveClass(final ObjectStreamClass desc) throws IOException, ClassNotFoundException {
            if (INVOCATION_CLASSNAME.equals(desc.getName())) {
                return cl;
            }
            return super.resolveClass(desc);
        }
    }

}
