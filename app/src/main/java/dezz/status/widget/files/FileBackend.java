/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.files;

import java.io.*;
import java.util.List;

/** All methods run on a worker; write must never replace an existing destination. */
public interface FileBackend {
    String identity();
    boolean writable();
    List<FileEntry> list(String path) throws Exception;
    FileEntry stat(String path) throws Exception;
    InputStream read(String path) throws Exception;
    void write(String path, InputStream input, long length, FileOperations.Control control) throws Exception;
    void mkdir(String path) throws Exception;
    void delete(String path) throws Exception;
    default void delete(String path,FileOperations.Control control)throws Exception {control.check();delete(path);}
}
