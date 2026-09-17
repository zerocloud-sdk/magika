/* Copyright 2026 ZeroCloud SDK contributors. SPDX-License-Identifier: Apache-2.0 */
package net.zerocloud.magika;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Observes the public NIO provider boundary while doing real filesystem I/O.
 * Tests can coordinate truncation, replacement or channel closure without sleeps
 * or access to SDK internals. Every opened channel still belongs to the SDK.
 */
final class ObservedFile extends FileSystemProvider {
    interface BeforeRead { void run(SeekableByteChannel channel) throws IOException; }

    private final FileSystem realSystem;
    private final FileSystemProvider delegate;
    private final BeforeRead beforeRead;
    private final FileSystem system;
    final Path path;
    int opens;
    int reads;
    int closes;
    long bytesRead;
    int maxRead = Integer.MAX_VALUE;

    ObservedFile(Path file, BeforeRead beforeRead) {
        this.realSystem = file.getFileSystem();
        this.delegate = realSystem.provider();
        this.beforeRead = beforeRead;
        this.system = new FileSystem() {
            @Override public FileSystemProvider provider() { return ObservedFile.this; }
            @Override public void close() { /* The default filesystem is not owned by this view. */ }
            @Override public boolean isOpen() { return realSystem.isOpen(); }
            @Override public boolean isReadOnly() { return realSystem.isReadOnly(); }
            @Override public String getSeparator() { return realSystem.getSeparator(); }
            @Override public Iterable<Path> getRootDirectories() { return realSystem.getRootDirectories(); }
            @Override public Iterable<FileStore> getFileStores() { return realSystem.getFileStores(); }
            @Override public Set<String> supportedFileAttributeViews() { return realSystem.supportedFileAttributeViews(); }
            @Override public Path getPath(String first, String... more) { return wrap(realSystem.getPath(first, more)); }
            @Override public PathMatcher getPathMatcher(String syntax) {
                PathMatcher matcher = realSystem.getPathMatcher(syntax);
                return candidate -> matcher.matches(unwrap(candidate));
            }
            @Override public UserPrincipalLookupService getUserPrincipalLookupService() {
                return realSystem.getUserPrincipalLookupService();
            }
            @Override public WatchService newWatchService() throws IOException { return realSystem.newWatchService(); }
        };
        path = wrap(file);
    }

    @Override public SeekableByteChannel newByteChannel(Path file, Set<? extends OpenOption> options,
                                                       FileAttribute<?>... attrs) throws IOException {
        SeekableByteChannel channel = delegate.newByteChannel(unwrap(file), options, attrs);
        opens++;
        return new SeekableByteChannel() {
            private boolean closed;
            @Override public int read(ByteBuffer buffer) throws IOException {
                reads++;
                beforeRead.run(channel);
                int limit = buffer.limit();
                buffer.limit(buffer.position() + Math.min(buffer.remaining(), maxRead));
                try {
                    int count = channel.read(buffer);
                    bytesRead += Math.max(count, 0);
                    return count;
                } finally { buffer.limit(limit); }
            }
            @Override public int write(ByteBuffer buffer) throws IOException { return channel.write(buffer); }
            @Override public long position() throws IOException { return channel.position(); }
            @Override public SeekableByteChannel position(long value) throws IOException { channel.position(value); return this; }
            @Override public long size() throws IOException { return channel.size(); }
            @Override public SeekableByteChannel truncate(long value) throws IOException { channel.truncate(value); return this; }
            @Override public boolean isOpen() { return channel.isOpen(); }
            @Override public void close() throws IOException {
                if (!closed) {
                    closed = true;
                    closes++;
                    channel.close();
                }
            }
        };
    }

    private Path wrap(Path file) { return file == null ? null : new View(file); }
    private static Path unwrap(Path file) { return file instanceof View ? ((View) file).real : file; }

    private final class View implements Path {
        private final Path real;
        private View(Path real) { this.real = real; }
        @Override public FileSystem getFileSystem() { return system; }
        @Override public boolean isAbsolute() { return real.isAbsolute(); }
        @Override public Path getRoot() { return wrap(real.getRoot()); }
        @Override public Path getFileName() { return wrap(real.getFileName()); }
        @Override public Path getParent() { return wrap(real.getParent()); }
        @Override public int getNameCount() { return real.getNameCount(); }
        @Override public Path getName(int i) { return wrap(real.getName(i)); }
        @Override public Path subpath(int start, int end) { return wrap(real.subpath(start, end)); }
        @Override public boolean startsWith(Path other) { return real.startsWith(unwrap(other)); }
        @Override public boolean startsWith(String other) { return real.startsWith(other); }
        @Override public boolean endsWith(Path other) { return real.endsWith(unwrap(other)); }
        @Override public boolean endsWith(String other) { return real.endsWith(other); }
        @Override public Path normalize() { return wrap(real.normalize()); }
        @Override public Path resolve(Path other) { return wrap(real.resolve(unwrap(other))); }
        @Override public Path resolve(String other) { return wrap(real.resolve(other)); }
        @Override public Path resolveSibling(Path other) { return wrap(real.resolveSibling(unwrap(other))); }
        @Override public Path resolveSibling(String other) { return wrap(real.resolveSibling(other)); }
        @Override public Path relativize(Path other) { return wrap(real.relativize(unwrap(other))); }
        @Override public URI toUri() { return real.toUri(); }
        @Override public Path toAbsolutePath() { return wrap(real.toAbsolutePath()); }
        @Override public Path toRealPath(LinkOption... options) throws IOException { return wrap(real.toRealPath(options)); }
        @Override public File toFile() { return real.toFile(); }
        @Override public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events,
                                           WatchEvent.Modifier... modifiers) throws IOException {
            return real.register(watcher, events, modifiers);
        }
        @Override public WatchKey register(WatchService watcher, WatchEvent.Kind<?>... events) throws IOException {
            return real.register(watcher, events);
        }
        @Override public Iterator<Path> iterator() {
            Iterator<Path> iterator = real.iterator();
            return new Iterator<Path>() {
                @Override public boolean hasNext() { return iterator.hasNext(); }
                @Override public Path next() { return wrap(iterator.next()); }
            };
        }
        @Override public int compareTo(Path other) { return real.compareTo(unwrap(other)); }
        @Override public boolean equals(Object other) {
            return other instanceof View && ((View) other).getFileSystem() == system && real.equals(((View) other).real);
        }
        @Override public int hashCode() { return real.hashCode(); }
        @Override public String toString() { return real.toString(); }
    }

    @Override public String getScheme() { return delegate.getScheme(); }
    @Override public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        return delegate.newFileSystem(uri, env);
    }
    @Override public FileSystem getFileSystem(URI uri) { return system; }
    @Override public Path getPath(URI uri) { return wrap(delegate.getPath(uri)); }
    @Override public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter)
            throws IOException { return delegate.newDirectoryStream(unwrap(dir), filter); }
    @Override public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        delegate.createDirectory(unwrap(dir), attrs);
    }
    @Override public void delete(Path file) throws IOException { delegate.delete(unwrap(file)); }
    @Override public void copy(Path source, Path target, CopyOption... options) throws IOException {
        delegate.copy(unwrap(source), unwrap(target), options);
    }
    @Override public void move(Path source, Path target, CopyOption... options) throws IOException {
        delegate.move(unwrap(source), unwrap(target), options);
    }
    @Override public boolean isSameFile(Path a, Path b) throws IOException { return delegate.isSameFile(unwrap(a), unwrap(b)); }
    @Override public boolean isHidden(Path file) throws IOException { return delegate.isHidden(unwrap(file)); }
    @Override public FileStore getFileStore(Path file) throws IOException { return delegate.getFileStore(unwrap(file)); }
    @Override public void checkAccess(Path file, AccessMode... modes) throws IOException { delegate.checkAccess(unwrap(file), modes); }
    @Override public <V extends FileAttributeView> V getFileAttributeView(Path file, Class<V> type, LinkOption... options) {
        return delegate.getFileAttributeView(unwrap(file), type, options);
    }
    @Override public <A extends BasicFileAttributes> A readAttributes(Path file, Class<A> type, LinkOption... options)
            throws IOException { return delegate.readAttributes(unwrap(file), type, options); }
    @Override public Map<String, Object> readAttributes(Path file, String attrs, LinkOption... options) throws IOException {
        return delegate.readAttributes(unwrap(file), attrs, options);
    }
    @Override public void setAttribute(Path file, String attr, Object value, LinkOption... options) throws IOException {
        delegate.setAttribute(unwrap(file), attr, value, options);
    }
}
