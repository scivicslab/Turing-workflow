/*
 * Copyright 2025 SCIVICS Lab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.scivicslab.turingworkflow.workflow;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Resolves a path a workflow names against a base directory, and refuses one that is not under it.
 *
 * <p>The path is resolved against the base and normalised, so {@code ..} is taken out and an
 * absolute path stays absolute; one that ends up outside the base is refused before anything is
 * opened. The file's real path is then taken, which resolves symlinks, and compared again, so a
 * link inside the base that points outside it is refused too. The base itself is compared by its
 * real path. An interpreter with no base directory set has nothing to compare against: the path is
 * then opened as it is written, as before.</p>
 *
 * <pre>{@code
 * Path file = PathUnderBase.resolve(workflowBaseDir, "sub/task.yaml");   // throws if outside
 * }</pre>
 */
public final class PathUnderBase {

    private PathUnderBase() {}

    /** A path that, once normalised and with its links resolved, is not under the base directory. */
    public static final class OutsideBaseException extends IOException {
        private static final long serialVersionUID = 1L;

        OutsideBaseException(String requested, Path base) {
            super("outside the base directory: " + requested + " (base directory " + base + ")");
        }
    }

    /**
     * The real path of {@code requested} under {@code baseDir}.
     *
     * @param baseDir   the base directory; null when none is set, and then {@code requested} is
     *                  returned as written, unchecked
     * @param requested the path as the workflow wrote it, relative to the base or absolute
     * @return the file's real path, which is under the base's real path
     * @throws OutsideBaseException          when the path, normalised or with its links resolved, is outside
     * @throws java.io.FileNotFoundException when there is no such file under the base
     * @throws IOException                   when the path cannot be resolved
     */
    public static Path resolve(String baseDir, String requested) throws IOException {
        if (baseDir == null) {
            return Path.of(requested);
        }
        Path base = Path.of(baseDir).toRealPath();
        Path candidate = base.resolve(requested).normalize();
        if (!candidate.startsWith(base)) {
            throw new OutsideBaseException(requested, base);
        }
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (java.nio.file.NoSuchFileException missing) {
            throw new java.io.FileNotFoundException(candidate + " (no such file under the base directory)");
        }
        if (!real.startsWith(base)) {
            throw new OutsideBaseException(requested, base);
        }
        return real;
    }
}
