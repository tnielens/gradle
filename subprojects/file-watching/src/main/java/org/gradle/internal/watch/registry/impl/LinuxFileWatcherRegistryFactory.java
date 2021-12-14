/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.watch.registry.impl;

import net.rubygrapefruit.platform.NativeIntegrationUnavailableException;
import net.rubygrapefruit.platform.file.FileEvents;
import net.rubygrapefruit.platform.file.FileWatchEvent;
import net.rubygrapefruit.platform.internal.jni.LinuxFileEventFunctions;
import net.rubygrapefruit.platform.internal.jni.LinuxFileEventFunctions.LinuxFileWatcher;
import org.gradle.internal.file.FileType;
import org.gradle.internal.snapshot.SnapshotHierarchy;
import org.gradle.internal.watch.registry.FileWatcherProbeRegistry;
import org.gradle.internal.watch.registry.FileWatcherUpdater;

import java.io.File;
import java.util.Collection;
import java.util.concurrent.BlockingQueue;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class LinuxFileWatcherRegistryFactory extends AbstractFileWatcherRegistryFactory<LinuxFileEventFunctions, LinuxFileWatcher> {

    public LinuxFileWatcherRegistryFactory(Predicate<String> watchFilter) throws NativeIntegrationUnavailableException {
        super(FileEvents.get(LinuxFileEventFunctions.class), watchFilter);
    }

    @Override
    protected LinuxFileWatcher createFileWatcher(BlockingQueue<FileWatchEvent> fileEvents) throws InterruptedException {
        return fileEventFunctions.newWatcher(fileEvents)
            .start();
    }

    @Override
    protected FileWatcherUpdater createFileWatcherUpdater(LinuxFileWatcher watcher, FileWatcherProbeRegistry probeRegistry, WatchableHierarchies watchableHierarchies) {
        return new NonHierarchicalFileWatcherUpdater(watcher, probeRegistry, watchableHierarchies, new LinuxMovedWatchedDirectoriesSupplier(watcher, watchableHierarchies));
    }

    private static class LinuxMovedWatchedDirectoriesSupplier implements AbstractFileWatcherUpdater.MovedWatchedDirectoriesSupplier {
        private final LinuxFileWatcher watcher;
        private final WatchableHierarchies watchableHierarchies;

        public LinuxMovedWatchedDirectoriesSupplier(LinuxFileWatcher watcher, WatchableHierarchies watchableHierarchies) {
            this.watcher = watcher;
            this.watchableHierarchies = watchableHierarchies;
        }

        @Override
        public Collection<File> stopWatchingMovedPaths(SnapshotHierarchy vfsRoot) {
            Collection<File> pathsToCheck = vfsRoot.rootSnapshots()
                .filter(snapshot -> snapshot.getType() != FileType.Missing)
                .filter(watchableHierarchies::shouldWatch)
                .map(snapshot -> {
                    switch (snapshot.getType()) {
                        case RegularFile:
                            return new File(snapshot.getAbsolutePath()).getParentFile();
                        case Directory:
                            return new File(snapshot.getAbsolutePath());
                        default:
                            throw new IllegalArgumentException("Unexpected file type:" + snapshot.getType());
                    }
                })
                .collect(Collectors.toList());
            return watcher.stopWatchingMovedPaths(pathsToCheck);
        }
    }
}
