package org.droiddialogue.wake;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Saves and loads wake word templates in a small binary file. */
public final class TemplateStore {
    private static final int MAGIC = 0x57574431; // "WWD1"

    private TemplateStore() {
    }

    public static void save(List<float[][]> templates, File file) throws IOException {
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)));
        try {
            out.writeInt(MAGIC);
            out.writeInt(templates.size());
            for (float[][] t : templates) {
                out.writeInt(t.length);
                out.writeInt(FrameExtractor.NCEP);
                for (float[] frame : t) {
                    for (int k = 0; k < FrameExtractor.NCEP; k++) {
                        out.writeFloat(frame[k]);
                    }
                }
            }
        } finally {
            out.close();
        }
    }

    /** Returns an empty list if the file is missing or damaged. */
    public static List<float[][]> load(File file) {
        List<float[][]> result = new ArrayList<float[][]>();
        if (!file.exists()) {
            return result;
        }
        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
            if (in.readInt() != MAGIC) {
                return result;
            }
            int count = in.readInt();
            if (count < 0 || count > 50) {
                return result;
            }
            List<float[][]> loaded = new ArrayList<float[][]>();
            for (int i = 0; i < count; i++) {
                int frames = in.readInt();
                int dims = in.readInt();
                if (frames < 1 || frames > 1000 || dims != FrameExtractor.NCEP) {
                    return result;
                }
                float[][] t = new float[frames][dims];
                for (int f = 0; f < frames; f++) {
                    for (int k = 0; k < dims; k++) {
                        t[f][k] = in.readFloat();
                    }
                }
                loaded.add(t);
            }
            return loaded;
        } catch (IOException e) {
            return new ArrayList<float[][]>();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
    }
}
