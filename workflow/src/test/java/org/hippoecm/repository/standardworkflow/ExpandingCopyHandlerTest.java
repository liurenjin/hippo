/*
 *  Copyright 2015 Hippo B.V. (http://www.onehippo.com)
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
package org.hippoecm.repository.standardworkflow;

import java.util.HashMap;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.Value;

import org.hippoecm.repository.util.JcrUtils;
import org.junit.Test;
import org.onehippo.repository.testutils.RepositoryTestCase;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ExpandingCopyHandlerTest extends RepositoryTestCase {

    private Node source;
    private Node target;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        final Node test = session.getRootNode().addNode("test");
        source = test.addNode("source");
        source.addMixin("hippo:translated");
        source.setProperty("prop", "value");
        source.setProperty("multiprop", new String[] { "value1", "value2" });
        source.addNode("node").setProperty("prop", "value");
        target = test.addNode("target");
    }

    @Test
    public void testCopyWithNodeNameSubstitutes() throws Exception {
        final Map<String, String[]> substitutes = new HashMap<String, String[]>() {{
            put("./_name", new String[] { "substitute" });
            put("./_name/_name", new String[] { "substitute" });
        }};
        ExpandingCopyHandler handler = new ExpandingCopyHandler(target, substitutes, session.getValueFactory());
        JcrUtils.copyTo(source, handler);
        assertTrue(session.nodeExists("/test/target/substitute"));
        assertTrue(session.nodeExists("/test/target/substitute/substitute"));
    }

    @Test
    public void testCopyWithPropertyValueSubstitutes() throws Exception {
        final Map<String, String[]> substitutes = new HashMap<String, String[]>() {{
            put("./source/prop", new String[] { "substitute" });
            put("./source/node/prop", new String[] { "substitute" });
        }};
        ExpandingCopyHandler handler = new ExpandingCopyHandler(target, substitutes, session.getValueFactory());
        JcrUtils.copyTo(source, handler);
        assertEquals("substitute", session.getProperty("/test/target/source/prop").getString());
        assertEquals("substitute", session.getProperty("/test/target/source/node/prop").getString());
    }

    /**
     * For backward compatibility, the properties of the source root can be relative to . instead of ./source
     */
    @Test
    public void testCopyWithPropertyValueSubstitutesBackwardCompatibility() throws Exception {
        final Map<String, String[]> substitutes = new HashMap<String, String[]>() {{
            put("./prop", new String[] { "substitute" });
        }};
        ExpandingCopyHandler handler = new ExpandingCopyHandler(target, substitutes, session.getValueFactory());
        JcrUtils.copyTo(source, handler);
        assertEquals("substitute", session.getProperty("/test/target/source/prop").getString());
    }

    @Test
    public void testCopyWithBothNodeNameAndPropertyValueSubstitutes() throws Exception {
        final Map<String, String[]> substitutes = new HashMap<String, String[]>() {{
            put("./_name", new String[] { "substitute" });
            put("./_name/prop", new String[] { "substitute" });
            put("./_name/_name", new String[] { "substitute" });
            put("./_name/_name/prop", new String[] { "substitute" });
        }};
        ExpandingCopyHandler handler = new ExpandingCopyHandler(target, substitutes, session.getValueFactory());
        JcrUtils.copyTo(source, handler);
        assertTrue(session.nodeExists("/test/target/substitute"));
        assertTrue(session.nodeExists("/test/target/substitute/substitute"));
        assertEquals("substitute", session.getProperty("/test/target/substitute/prop").getString());
        assertEquals("substitute", session.getProperty("/test/target/substitute/substitute/prop").getString());
    }

    @Test
    public void testCopyWithBothNodeNameAndPropertyValueSubstitutesAlsoMatchesOriginalNodeNames() throws Exception {
        final Map<String, String[]> substitutes = new HashMap<String, String[]>() {{
            put("./_name", new String[] { "substitute" });
            put("./source/prop", new String[] { "substitute" });
            put("./source/_name", new String[] { "substitute" });
            put("./source/node/prop", new String[] { "substitute" });
        }};
        ExpandingCopyHandler handler = new ExpandingCopyHandler(target, substitutes, session.getValueFactory());
        JcrUtils.copyTo(source, handler);
        assertTrue(session.nodeExists("/test/target/substitute"));
        assertTrue(session.nodeExists("/test/target/substitute/substitute"));
        assertEquals("substitute", session.getProperty("/test/target/substitute/prop").getString());
        assertEquals("substitute", session.getProperty("/test/target/substitute/substitute/prop").getString());
    }

    @Test
    public void testCopyWithBothNodeNameAndPropertyValueSubstitutesMatchAny() throws Exception {
        final Map<String, String[]> substitutes = new HashMap<String, String[]>() {{
            put("./_name", new String[] { "substitute" });
            put("./_node/prop", new String[] { "substitute" });
            put("./_node/_name", new String[] { "substitute" });
            put("./_node/_node/prop", new String[] { "substitute" });
        }};
        ExpandingCopyHandler handler = new ExpandingCopyHandler(target, substitutes, session.getValueFactory());
        JcrUtils.copyTo(source, handler);
        assertTrue(session.nodeExists("/test/target/substitute"));
        assertTrue(session.nodeExists("/test/target/substitute/substitute"));
        assertEquals("substitute", session.getProperty("/test/target/substitute/prop").getString());
        assertEquals("substitute", session.getProperty("/test/target/substitute/substitute/prop").getString());
    }

    @Test
    public void testCopyWithMultiPropertyValueSubstitution() throws Exception {
        final Map<String, String[]> substitutes = new HashMap<String, String[]>() {{
            put("./source/multiprop", new String[] { "substitute1", "substitute2" });
        }};
        ExpandingCopyHandler handler = new ExpandingCopyHandler(target, substitutes, session.getValueFactory());
        JcrUtils.copyTo(source, handler);
        final Value[] values = session.getProperty("/test/target/source/multiprop").getValues();
        assertEquals(2, values.length);
        assertEquals("substitute1", values[0].getString());
        assertEquals("substitute2", values[1].getString());
    }

    @Test
    public void testCopyWithMultiPropertyValueIndexedSubstitution() throws Exception {
        final Map<String, String[]> substitutes = new HashMap<String, String[]>() {{
            put("./source/multiprop[1]", new String[] { "substitute" });
        }};
        ExpandingCopyHandler handler = new ExpandingCopyHandler(target, substitutes, session.getValueFactory());
        JcrUtils.copyTo(source, handler);
        final Value[] values = session.getProperty("/test/target/source/multiprop").getValues();
        assertEquals(2, values.length);
        assertEquals("substitute", values[1].getString());
    }
}
