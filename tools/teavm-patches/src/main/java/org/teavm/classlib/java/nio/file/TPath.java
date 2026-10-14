/*
 *  Copyright 2025 Alexey Andreev.
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
 *
 *  Modified for Incari (tools/teavm-patches/README.md): of(URI) asks the scheme's provider for the
 *  path, as the JDK does, instead of first asking for the file system, which the default provider
 *  grants only for the root URI.
 */
package org.teavm.classlib.java.nio.file;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystemNotFoundException;
import org.teavm.classlib.java.net.TURI;
import org.teavm.classlib.java.nio.file.spi.TFileSystemProvider;

public interface TPath extends Comparable<TPath>, Iterable<TPath> {
    static TPath of(String first, String... more) {
        return TFileSystems.getDefault().getPath(first, more);
    }

    static TPath of(TURI uri) {
        for (var provider : TFileSystemProvider.installedProviders()) {
            if (provider.getScheme().equalsIgnoreCase(uri.getScheme())) {
                return provider.getPath(uri);
            }
        }
        throw new FileSystemNotFoundException();
    }

    TFileSystem getFileSystem();

    boolean isAbsolute();

    TPath getRoot();

    TPath getFileName();

    TPath getParent();

    int getNameCount();

    TPath getName(int index);

    TPath subpath(int beginIndex, int endIndex);

    boolean startsWith(TPath other);

    default boolean startsWith(String other) {
        return startsWith(getFileSystem().getPath(other));
    }

    boolean endsWith(TPath other);

    default boolean endsWith(String other) {
        return endsWith(getFileSystem().getPath(other));
    }

    TPath normalize();

    TPath resolve(TPath other);

    default TPath resolve(String other) {
        return resolve(getFileSystem().getPath(other));
    }

    default TPath resolveSibling(TPath other) {
        return getParent() == null ? other : getParent().resolve(other);
    }

    default TPath resolveSibling(String other) {
        return resolveSibling(getFileSystem().getPath(other));
    }

    TPath relativize(TPath other);

    TURI toUri();

    TPath toAbsolutePath();

    TPath toRealPath(TLinkOption... options) throws IOException;

    @Override
    int compareTo(TPath o);

    default File toFile() {
        if (getFileSystem() != TFileSystems.getDefault()) {
            throw new UnsupportedOperationException();
        }
        return new File(toString());
    }
}
