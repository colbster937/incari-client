package incari.platform.web.storage;

import java.io.IOException;

/** A read-only file whose bytes are an entry of a mounted zip, inflated each time it's opened. */
final class MountedFile extends FileNode {

    private final ZipArchive archive;
    private final ZipArchive.Entry entry;

    MountedFile(String name, long lastModified, ZipArchive archive, ZipArchive.Entry entry) {
        super(name, lastModified);
        this.archive = archive;
        this.entry = entry;
        this.readOnly = true;
    }

    @Override
    int length() {
        return entry.size();
    }

    @Override
    Bytes contents() throws IOException {
        byte[] bytes = archive.read(entry);
        return new Bytes(bytes, bytes.length);
    }
}
