/*
 * Copyright (C) 2018-2020. Huawei Technologies Co., Ltd. All rights reserved.
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

package io.hetu.core.seedstore.filebased;

import io.airlift.log.Logger;
import io.prestosql.spi.filesystem.FileBasedLock;
import io.prestosql.spi.filesystem.HetuFileSystemClient;
import io.prestosql.spi.seedstore.Seed;
import io.prestosql.spi.seedstore.SeedStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.StandardOpenOption.CREATE_NEW;

/**
 * FileBasedSeedStore
 *
 * @since 2020-03-08
 */
public class FileBasedSeedStore
        implements SeedStore
{
    private static final Logger LOG = Logger.get(FileBasedSeedStore.class);
    private static final String COMMA = ",";
    private HetuFileSystemClient fs;
    private Map<String, String> config;
    // seed dir
    private String name;
    // seedFilePath = <seedDir>/<name>/seeds.txt
    private Path seedDir;
    private Path seedFilePath;

    /**
     * Constructor for file based seed store
     *
     * @param name seed store name
     * @param config seed store config
     */
    public FileBasedSeedStore(String name, HetuFileSystemClient fs, Map<String, String> config)
    {
        this.name = name;
        this.fs = fs;
        this.config = config;

        seedDir = Paths.get(config.get(FileBasedSeedConstants.SEED_STORE_FILESYSTEM_DIR).trim());
        seedFilePath = seedDir.resolve(name).resolve(FileBasedSeedConstants.SEED_FILE_NAME);
    }

    @Override
    public Set<Seed> add(Collection<Seed> seeds)
            throws IOException
    {
        Lock lock = new FileBasedLock(fs, seedDir.resolve(name));
        try {
            lock.lock();
            Set<String> writtens = new HashSet<>(0);
            Set<Seed> outputs = getSeeds();
            // overwrite all seeds
            outputs.removeAll(seeds);
            outputs.addAll(seeds);

            for (Seed seed : outputs) {
                writtens.add(seed.serialize());
            }
            writeToFile(seedFilePath, String.join(COMMA, writtens), true);
            return outputs;
        }
        catch (UncheckedIOException e) {
            throw new IOException(e);
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public Set<Seed> get()
            throws IOException
    {
        try {
            return getSeeds();
        }
        catch (UncheckedIOException e) {
            throw new IOException(e);
        }
    }

    @Override
    public Set<Seed> remove(Collection<Seed> seeds)
            throws IOException
    {
        Lock lock = new FileBasedLock(fs, seedDir.resolve(name));
        try {
            lock.lock();
            Set<String> writtens = new HashSet<>(0);
            Set<Seed> outputs = getSeeds();
            outputs.removeAll(seeds);

            for (Seed seed : outputs) {
                writtens.add(seed.serialize());
            }
            writeToFile(seedFilePath, String.join(COMMA, writtens), true);
            return outputs;
        }
        catch (UncheckedIOException e) {
            throw new IOException(e);
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public Seed create(Map<String, String> properties)
    {
        String location = properties.get(Seed.LOCATION_PROPERTY_NAME);
        String timestamp = properties.get(Seed.TIMESTAMP_PROPERTY_NAME);
        // add more properties if interfaces change
        if (location == null || location.isEmpty()) {
            throw new NullPointerException("Cannot create filebased seed since location is null or empty");
        }

        return new FileBasedSeed.FileBasedSeedBuilder(location).setTimestamp(Long.parseLong(timestamp)).build();
    }

    @Override
    public String getName()
    {
        return name;
    }

    @Override
    public void setName(String name)
    {
        this.name = name;
        this.seedFilePath = seedDir.resolve(name).resolve(FileBasedSeedConstants.SEED_FILE_NAME);
    }

    private Set<Seed> getSeeds()
            throws IOException
    {
        Set<Seed> outputs = new HashSet<>(0);

        StringBuilder content = new StringBuilder(0);
        if (fs.exists(seedFilePath)) {
            try (InputStream in = fs.newInputStream(seedFilePath)) {
                while (in.available() > 0) {
                    content.append((char) in.read());
                }
            }
        }

        Set<String> seedsStrings = Stream.of(content.toString().split(COMMA)).filter(e -> !e.isEmpty())
                .collect(Collectors.toSet());

        for (String str : seedsStrings) {
            try {
                outputs.add(FileBasedSeed.deserialize(str));
            }
            catch (ClassNotFoundException e) {
                LOG.error("Seed string: %s cannot be deserialized", str);
            }
        }
        return outputs;
    }

    private void writeToFile(Path file, String content, boolean overwrite)
            throws IOException
    {
        OutputStream os;
        if (overwrite) {
            os = fs.newOutputStream(file);
        }
        else {
            os = fs.newOutputStream(file, CREATE_NEW);
        }
        os.write(content.getBytes());
        os.close();
    }
}
