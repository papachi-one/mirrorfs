package one.papachi.mirrorfs;

import one.papachi.dokany4j.DokanOptions;
import one.papachi.dokany4j.Dokany4j;
import one.papachi.fuse4j.FuseOptions;
import one.papachi.vfs4j.VirtualFileSystem;
import one.papachi.vfs4j.dokany.DokanyFileSystem;
import one.papachi.vfs4j.fuse.FuseFileSystem;
import one.papachi.vfs4j.macfuse.MacFuseFileSystem;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class MirrorFileSystem implements VirtualFileSystem {

    public static void main(String[] args) throws Exception {
        String from = args[0];
        String to = args[1];
        switch (Utils.getOperatingSystemFamily()) {
            case UNKNOWN -> {
                MirrorFileSystem vfs = new MirrorFileSystem(Path.of(from));
                DokanyFileSystem dokanyFileSystem = new DokanyFileSystem(vfs);
                Runtime.getRuntime().addShutdownHook(new Thread(() -> Dokany4j.unmount(to)));
                int mount = dokanyFileSystem.mount(205, true, new DokanOptions.Builder().build().dokanOptions(), to, "", 120, 4096, 4096);
                System.out.println(mount);
            }
            case LINUX -> {
                MirrorFileSystem vfs = new MirrorFileSystem(Path.of(from));
                FuseFileSystem fuseFileSystem = new FuseFileSystem(vfs);
                String[] fuseArgs = new FuseOptions().setDebug().setForegroundOperation().setAutoUnmount().setMountPoint(to).build();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        Runtime.getRuntime().exec(new String[] {"fusermount", "-u", to}).waitFor();
                    } catch (IOException | InterruptedException e) {
                    }
                }));
                int mount = fuseFileSystem.mount(fuseArgs);
                System.out.println(mount);
            }
            case MAC -> {
                MirrorFileSystem vfs = new MirrorFileSystem(Path.of(from));
                MacFuseFileSystem fuseFileSystem = new MacFuseFileSystem(vfs);
                String[] fuseArgs = new FuseOptions().setDebug().setMountPoint(to).build();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        Runtime.getRuntime().exec(new String[] {"umount", "-f", to}).waitFor();
                    } catch (IOException | InterruptedException e) {
                    }
                }));
                int mount = fuseFileSystem.mount(fuseArgs);
                System.out.println(mount);
            }
        }
    }

    private final Path path;

    private final Map<String, FileChannel> channelMap = new HashMap<>();

    public MirrorFileSystem(Path path) {
        this.path = path;
    }

    private static long size(Path path) {
        long size = 0L;
        try {
            size = Files.size(path);
        } catch (Exception e) {
        }
        return size;
    }

    private static FileTime lastModifiedTime(Path path) {
        FileTime fileTime = FileTime.from(Instant.now());
        try {
            fileTime = Files.getLastModifiedTime(path);
        } catch (Exception e) {
        }
        return fileTime;
    }

    @Override
    public List<FileInfo> listDirectory(String filename) throws FileSystemException, IOException {
        Path p = path.resolve(filename.substring(1));
        if (!Files.exists(p))
            throw new FileNotFoundException("");
        if (!Files.isDirectory(p))
            throw new NotDirectoryException("");
        return Stream.concat(Stream.of(new FileInfo(".", true, 0L, 0, 0, System.currentTimeMillis(), System.currentTimeMillis(), System.currentTimeMillis())),
                Files.list(p).map(path -> new FileInfo(path.getFileName().toString(), Files.isDirectory(path), size(path), 0, 0, lastModifiedTime(path).toMillis(), lastModifiedTime(path).toMillis(), lastModifiedTime(path).toMillis()))).toList();
    }

    @Override
    public FileInfo listFile(String filename) throws FileSystemException, IOException {
        Path p = path.resolve(filename.substring(1));
        if (!Files.exists(p))
            throw new FileNotFoundException("");
        return new FileInfo(p.getFileName().toString(), Files.isDirectory(p), size(p), 0, 0, lastModifiedTime(p).toMillis(), lastModifiedTime(p).toMillis(), lastModifiedTime(p).toMillis());
    }

    @Override
    public boolean isDirectoryEmpty(String filename) throws FileSystemException, IOException {
        Path p = path.resolve(filename.substring(1));
        if (!Files.exists(p))
            throw new FileNotFoundException("");
        if (!Files.isDirectory(p))
            throw new NotDirectoryException("");
        return Files.list(p).count() == 0;
    }

    @Override
    public void createDirectory(String filename) throws FileSystemException, IOException {
        Path p = path.resolve(filename.substring(1));
        if (Files.exists(p))
            throw new FileAlreadyExistsException("");
        Files.createDirectories(p);
    }

    @Override
    public void createRegularFile(String filename) throws FileSystemException, IOException {
        Path p = path.resolve(filename.substring(1));
        if (Files.exists(p))
            throw new FileAlreadyExistsException("");
        Files.createFile(p);
    }

    @Override
    public void renameFile(String filename, String newFilename) throws FileSystemException, IOException {
        Path p = path.resolve(filename.substring(1));
        Path pNew = path.resolve(newFilename.substring(1));
        Files.move(p, pNew);
    }

    @Override
    public void deleteFile(String filename) throws FileSystemException, IOException {
        Path p = path.resolve(filename.substring(1));
        if (!Files.exists(p))
            throw new FileNotFoundException();
        if (Files.isDirectory(p) && Files.list(p).count() > 0)
            throw new DirectoryNotEmptyException("");
        Files.delete(p);
    }

    @Override
    public void openFile(String filename, boolean write) throws FileSystemException, IOException {
    }

    @Override
    public void closeFile(String filename) throws FileSystemException, IOException {
        channelMap.remove(filename).close();
    }

    @Override
    public int readFile(String filename, ByteBuffer buffer, long position) throws FileSystemException, IOException {
        Path p = path.resolve(filename.substring(1));
        if (!Files.exists(p))
            throw new FileNotFoundException();
        if (Files.isDirectory(p))
            throw new IOException();
        FileChannel channel = channelMap.get(filename);
        if (channel == null)
            channelMap.put(filename, channel = FileChannel.open(p, StandardOpenOption.READ, StandardOpenOption.WRITE));
        return channel.read(buffer, position);
    }

    @Override
    public int writeFile(String filename, ByteBuffer buffer, long position) throws FileSystemException, IOException {
        Path p = path.resolve(filename.substring(1));
        if (!Files.exists(p))
            throw new FileNotFoundException();
        if (Files.isDirectory(p))
            throw new IOException();
        FileChannel channel = channelMap.get(filename);
        if (channel == null)
            channelMap.put(filename, channel = FileChannel.open(p, StandardOpenOption.READ, StandardOpenOption.WRITE));
        return channel.write(buffer, position);
    }

    @Override
    public void setFileSize(String filename, long size) throws FileSystemException, IOException {
        Path p = path.resolve(filename.substring(1));
        if (!Files.exists(p))
            throw new FileNotFoundException();
        if (Files.isDirectory(p))
            throw new IOException();
        FileChannel channel = channelMap.get(filename);
        if (channel == null)
            channelMap.put(filename, channel = FileChannel.open(p, StandardOpenOption.READ, StandardOpenOption.WRITE));
        channel.truncate(size);
    }

}
