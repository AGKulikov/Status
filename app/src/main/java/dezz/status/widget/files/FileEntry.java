/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.files;

/** Immutable entry. Paths are relative to one explicitly selected storage root. */
public final class FileEntry {
    public final String path, name;
    public final boolean directory;
    public final long size, modified;
    public FileEntry(String path, String name, boolean directory, long size, long modified) {
        this.path=path; this.name=name; this.directory=directory; this.size=size; this.modified=modified;
    }
}
