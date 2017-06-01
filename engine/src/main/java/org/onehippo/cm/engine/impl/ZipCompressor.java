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
package org.onehippo.cm.engine.impl;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.IOUtils;

/**
 * Zip utility
 */
public class ZipCompressor {

    /**
     * Zip directory's contents
     *
     * @param dir directory to compress
     * @param targetZip target path of the zip file
     */
    public void zipDirectory(Path dir, String targetZip) throws IOException {
        final List<String> paths = getFilesInDirectory(dir);
        try (final FileOutputStream fos = new FileOutputStream(targetZip);
             final ZipOutputStream zos = new ZipOutputStream(fos)) {

            for (final String filePath : paths) {
                final ZipEntry ze = new ZipEntry(filePath.substring(dir.toAbsolutePath().toString().length() + 1, filePath.length()));
                zos.putNextEntry(ze);

                try (FileInputStream fis = new FileInputStream(filePath)) {
                    IOUtils.copy(fis, zos);
                    zos.closeEntry();
                }
            }
        }
    }

    /**
     * Collect all the files in a directory and its subdirectories
     *
     * @param dir directory
     * @throws IOException
     */
    private List<String> getFilesInDirectory(final Path dir) throws IOException {
        final List<String> paths = new ArrayList<>();
        final BiPredicate<Path, BasicFileAttributes> matcher =
                (filePath, fileAttr) -> fileAttr.isRegularFile();
        Files.find(dir, Integer.MAX_VALUE, matcher).forEachOrdered(p -> paths.add(p.toString()));
        return paths;
    }
}

