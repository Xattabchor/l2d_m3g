package rmn20.j2me;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Hashtable;
import java.util.Vector;

/**
 * Small, CLDC 1.1-compatible OBJ to 3D2 converter.
 * Supports static OBJ geometry (v, vt, vn, f); n-gons are fan-triangulated.
 */
public final class Obj3D2Converter {
    private static final int POSITION_SCALE = 256;
    private static final int UV_SCALE = 256;
    private static final int MAX_SOURCE_ATTRIBUTES = 8192;
    private static final int MAX_OUTPUT_VERTICES = 4096;
    private static final int MAX_TRIANGLES = 8192;
    private static final int MAX_FACE_VERTICES = 128;
    private static final int MAX_LINE_LENGTH = 2048;

    private Obj3D2Converter() {
    }

    public static final class Result {
        public final int vertices;
        public final int triangles;
        public final int bytes;

        private Result(int vertices, int triangles, int bytes) {
            this.vertices = vertices;
            this.triangles = triangles;
            this.bytes = bytes;
        }
    }

    private static final class Vec3 {
        float x;
        float y;
        float z;

        Vec3(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private static final class Vertex {
        int x;
        int y;
        int z;
        int nx;
        int ny;
        int nz;
        int u;
        int v;
        boolean hasNormal;
        boolean hasUV;
    }

    private static final class Triangle {
        int a;
        int b;
        int c;

        Triangle(int a, int b, int c) {
            this.a = a;
            this.b = b;
            this.c = c;
        }
    }

    public static Result convert(InputStream input, OutputStream output) throws IOException {
        Vector positions = new Vector();
        Vector uvs = new Vector();
        Vector normals = new Vector();
        Vector vertices = new Vector();
        Vector triangles = new Vector();
        Hashtable vertexIds = new Hashtable();
        LineReader reader = new LineReader(input);
        String line;
        int lineNumber = 0;
        boolean hasNormals = false;
        boolean hasUVs = false;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int minU = Integer.MAX_VALUE, minV = Integer.MAX_VALUE;
        int maxU = Integer.MIN_VALUE, maxV = Integer.MIN_VALUE;

        while((line = reader.readLine()) != null) {
            lineNumber++;
            int comment = line.indexOf('#');
            if(comment >= 0) line = line.substring(0, comment);
            TokenReader tokens = new TokenReader(line);
            if(!tokens.hasMoreTokens()) continue;

            String kind = tokens.nextToken();
            if(kind.equals("v")) {
                float x = parseFloat(requiredToken(tokens, lineNumber, "vertex x"), lineNumber);
                float y = parseFloat(requiredToken(tokens, lineNumber, "vertex y"), lineNumber);
                float z = parseFloat(requiredToken(tokens, lineNumber, "vertex z"), lineNumber);
                positions.addElement(new Vec3(x, y, z));
                checkSourceLimit(positions.size(), lineNumber);
            } else if(kind.equals("vt")) {
                float u = parseFloat(requiredToken(tokens, lineNumber, "texture u"), lineNumber);
                float v = 1.0F - parseFloat(requiredToken(tokens, lineNumber, "texture v"), lineNumber);
                uvs.addElement(new Vec3(u, v, 0.0F));
                checkSourceLimit(uvs.size(), lineNumber);
            } else if(kind.equals("vn")) {
                float x = parseFloat(requiredToken(tokens, lineNumber, "normal x"), lineNumber);
                float y = parseFloat(requiredToken(tokens, lineNumber, "normal y"), lineNumber);
                float z = parseFloat(requiredToken(tokens, lineNumber, "normal z"), lineNumber);
                normals.addElement(normalize(x, y, z));
                checkSourceLimit(normals.size(), lineNumber);
            } else if(kind.equals("f")) {
                int[] polygon = new int[MAX_FACE_VERTICES];
                int count = 0;
                while(tokens.hasMoreTokens()) {
                    String token = tokens.nextToken();
                    if(token.charAt(0) == '#') break;
                    if(count == polygon.length) throw error(lineNumber, "face has too many vertices");
                    int[] ref = parseFaceRef(token, positions.size(), uvs.size(), normals.size(), lineNumber);
                    String key = ref[0] + "/" + ref[1] + "/" + ref[2];
                    Integer id = (Integer) vertexIds.get(key);
                    int vertexId;
                    if(id == null) {
                        if(vertices.size() >= MAX_OUTPUT_VERTICES) {
                            throw error(lineNumber, "model exceeds " + MAX_OUTPUT_VERTICES + " unique vertices");
                        }
                        Vertex vertex = makeVertex(
                                (Vec3) positions.elementAt(ref[0]),
                                ref[1] < 0 ? null : (Vec3) uvs.elementAt(ref[1]),
                                ref[2] < 0 ? null : (Vec3) normals.elementAt(ref[2]),
                                lineNumber);
                        vertexId = vertices.size();
                        vertices.addElement(vertex);
                        vertexIds.put(key, new Integer(vertexId));
                        hasNormals |= vertex.hasNormal;
                        hasUVs |= vertex.hasUV;
                        minX = Math.min(minX, vertex.x); minY = Math.min(minY, vertex.y); minZ = Math.min(minZ, vertex.z);
                        maxX = Math.max(maxX, vertex.x); maxY = Math.max(maxY, vertex.y); maxZ = Math.max(maxZ, vertex.z);
                        minU = Math.min(minU, vertex.u); minV = Math.min(minV, vertex.v);
                        maxU = Math.max(maxU, vertex.u); maxV = Math.max(maxV, vertex.v);
                    } else {
                        vertexId = id.intValue();
                    }
                    polygon[count++] = vertexId;
                }
                if(count < 3) throw error(lineNumber, "face needs at least 3 vertices");
                for(int i = 1; i < count - 1; i++) {
                    if(triangles.size() >= MAX_TRIANGLES) {
                        throw error(lineNumber, "model exceeds " + MAX_TRIANGLES + " triangles");
                    }
                    triangles.addElement(new Triangle(polygon[0], polygon[i], polygon[i + 1]));
                }
            }
        }

        if(positions.size() == 0) throw new IOException("OBJ contains no vertices");
        if(triangles.size() == 0 || vertices.size() == 0) throw new IOException("OBJ contains no triangle faces");
        if(vertices.size() > 65535 || triangles.size() > 65535) throw new IOException("3D2 count limit exceeded");

        // Include zero-valued defaults when only some vertices have UVs/normals.
        if(hasUVs) {
            minU = Math.min(minU, 0); minV = Math.min(minV, 0);
            maxU = Math.max(maxU, 0); maxV = Math.max(maxV, 0);
        }

        CountingOutputStream counted = new CountingOutputStream(output);
        DataOutputStream data = new DataOutputStream(counted);
        writeModel(data, vertices, triangles, hasNormals, hasUVs,
                minX, minY, minZ, maxX, maxY, maxZ, minU, minV, maxU, maxV);
        data.flush();
        return new Result(vertices.size(), triangles.size(), counted.count);
    }

    private static void writeModel(DataOutputStream out, Vector vertices, Vector triangles,
            boolean hasNormals, boolean hasUVs,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            int minU, int minV, int maxU, int maxV) throws IOException {
        boolean xByte = maxX - minX < 256;
        boolean yByte = maxY - minY < 256;
        boolean zByte = maxZ - minZ < 256;
        boolean uByte = hasUVs && maxU - minU < 256;
        boolean vByte = hasUVs && maxV - minV < 256;
        int posOffsetX = xByte ? minX + 128 : 0;
        int posOffsetY = yByte ? minY + 128 : 0;
        int posOffsetZ = zByte ? minZ + 128 : 0;
        int uvOffsetU = uByte ? minU + 128 : 0;
        int uvOffsetV = vByte ? minV + 128 : 0;
        int flags = (hasNormals ? 1 : 0) | (hasUVs ? 2 : 0) | (uByte ? 8 : 0) | (vByte ? 16 : 0);

        out.writeByte('3');
        out.writeByte('D');
        out.writeByte('2');
        out.writeByte(0);
        out.writeShort(0);
        out.writeFloat(POSITION_SCALE);
        out.writeFloat(UV_SCALE);
        writeBounds(out, minX, minY, minZ, maxX, maxY, maxZ);
        out.writeShort(1); // one mesh

        out.writeInt(flags);
        writeBounds(out, minX, minY, minZ, maxX, maxY, maxZ);
        if(uByte) out.writeShort(minU);
        if(vByte) out.writeShort(minV);

        out.writeShort(vertices.size());
        for(int i = 0; i < vertices.size(); i++) {
            Vertex vertex = (Vertex) vertices.elementAt(i);
            writeComponent(out, vertex.x - posOffsetX, xByte);
            writeComponent(out, vertex.y - posOffsetY, yByte);
            writeComponent(out, vertex.z - posOffsetZ, zByte);
        }
        if(hasNormals) {
            for(int i = 0; i < vertices.size(); i++) {
                Vertex vertex = (Vertex) vertices.elementAt(i);
                out.writeByte(vertex.hasNormal ? vertex.nx : 0);
                out.writeByte(vertex.hasNormal ? vertex.ny : 0);
                out.writeByte(vertex.hasNormal ? vertex.nz : 0);
            }
        }
        if(hasUVs) {
            for(int i = 0; i < vertices.size(); i++) {
                Vertex vertex = (Vertex) vertices.elementAt(i);
                writeComponent(out, vertex.u - uvOffsetU, uByte);
                writeComponent(out, vertex.v - uvOffsetV, vByte);
            }
        }

        out.writeShort(0); // quads
        out.writeShort(triangles.size());
        out.writeShort(1); // one submesh
        out.writeShort(0); // submesh quads
        out.writeShort(triangles.size());
        for(int i = 0; i < triangles.size(); i++) {
            Triangle triangle = (Triangle) triangles.elementAt(i);
            writeIndex(out, triangle.a, vertices.size());
            writeIndex(out, triangle.b, vertices.size());
            writeIndex(out, triangle.c, vertices.size());
        }
    }

    private static void writeBounds(DataOutputStream out, int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ) throws IOException {
        out.writeShort(minX); out.writeShort(minY); out.writeShort(minZ);
        out.writeShort(maxX); out.writeShort(maxY); out.writeShort(maxZ);
    }

    private static void writeComponent(DataOutputStream out, int value, boolean asByte) throws IOException {
        if(asByte) out.writeByte(value);
        else out.writeShort(value);
    }

    private static void writeIndex(DataOutputStream out, int index, int vertexCount) throws IOException {
        if(vertexCount <= 256) out.writeByte(index);
        else out.writeShort(index);
    }

    private static Vertex makeVertex(Vec3 position, Vec3 uv, Vec3 normal, int lineNumber) throws IOException {
        Vertex result = new Vertex();
        result.x = quantize(position.x, POSITION_SCALE, lineNumber);
        result.y = quantize(position.y, POSITION_SCALE, lineNumber);
        result.z = quantize(position.z, POSITION_SCALE, lineNumber);
        checkShort(result.x, lineNumber, "position");
        checkShort(result.y, lineNumber, "position");
        checkShort(result.z, lineNumber, "position");
        if(uv != null) {
            result.u = quantize(uv.x, UV_SCALE, lineNumber);
            result.v = quantize(uv.y, UV_SCALE, lineNumber);
            checkShort(result.u, lineNumber, "UV");
            checkShort(result.v, lineNumber, "UV");
            result.hasUV = true;
        }
        if(normal != null) {
            result.nx = quantize(normal.x, 127, lineNumber);
            result.ny = quantize(normal.y, 127, lineNumber);
            result.nz = quantize(normal.z, 127, lineNumber);
            result.nx = clamp(result.nx, -127, 127);
            result.ny = clamp(result.ny, -127, 127);
            result.nz = clamp(result.nz, -127, 127);
            result.hasNormal = true;
        }
        return result;
    }

    private static Vec3 normalize(float x, float y, float z) {
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if(length == 0.0F) return new Vec3(0.0F, 0.0F, 0.0F);
        return new Vec3(x / length, y / length, z / length);
    }

    private static int[] parseFaceRef(String token, int posCount, int uvCount, int normalCount,
            int lineNumber) throws IOException {
        int first = token.indexOf('/');
        int second = first < 0 ? -1 : token.indexOf('/', first + 1);
        String posText = first < 0 ? token : token.substring(0, first);
        String uvText = first < 0 ? "" : token.substring(first + 1, second < 0 ? token.length() : second);
        String normText = second < 0 ? "" : token.substring(second + 1);
        int pos = resolveIndex(parseInt(posText, lineNumber), posCount, lineNumber, "position");
        int uv = uvText.length() == 0 ? -1 : resolveIndex(parseInt(uvText, lineNumber), uvCount, lineNumber, "UV");
        int normal = normText.length() == 0 ? -1 : resolveIndex(parseInt(normText, lineNumber), normalCount, lineNumber, "normal");
        return new int[] {pos, uv, normal};
    }

    private static int resolveIndex(int index, int count, int lineNumber, String type) throws IOException {
        if(index == 0) throw error(lineNumber, "OBJ indices are one-based");
        int resolved = index > 0 ? index - 1 : count + index;
        if(resolved < 0 || resolved >= count) throw error(lineNumber, "invalid " + type + " index " + index);
        return resolved;
    }

    private static String requiredToken(TokenReader tokens, int lineNumber, String field) throws IOException {
        if(!tokens.hasMoreTokens()) throw error(lineNumber, "missing " + field);
        return tokens.nextToken();
    }

    private static float parseFloat(String value, int lineNumber) throws IOException {
        try {
            float result = Float.parseFloat(value);
            if(result != result || result > Float.MAX_VALUE || result < -Float.MAX_VALUE) {
                throw error(lineNumber, "non-finite number");
            }
            return result;
        } catch(NumberFormatException ex) {
            throw error(lineNumber, "invalid number " + value);
        }
    }

    private static int parseInt(String value, int lineNumber) throws IOException {
        try {
            return Integer.parseInt(value);
        } catch(NumberFormatException ex) {
            throw error(lineNumber, "invalid OBJ index " + value);
        }
    }

    private static int quantize(float value, int scale, int lineNumber) throws IOException {
        float scaled = value * scale;
        if(scaled > 32767.0F || scaled < -32768.0F) throw error(lineNumber, "value outside 3D2 signed-short range");
        return scaled >= 0.0F ? (int) (scaled + 0.5F) : (int) (scaled - 0.5F);
    }

    private static void checkShort(int value, int lineNumber, String type) throws IOException {
        if(value < -32768 || value > 32767) throw error(lineNumber, type + " outside 3D2 signed-short range");
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : value > max ? max : value;
    }

    private static void checkSourceLimit(int count, int lineNumber) throws IOException {
        if(count > MAX_SOURCE_ATTRIBUTES) throw error(lineNumber, "source exceeds " + MAX_SOURCE_ATTRIBUTES + " attributes");
    }

    private static IOException error(int lineNumber, String message) {
        return new IOException("OBJ line " + lineNumber + ": " + message);
    }

    private static final class TokenReader {
        private final String text;
        private int position;

        TokenReader(String text) {
            this.text = text;
        }

        boolean hasMoreTokens() {
            skipWhitespace();
            return position < text.length();
        }

        String nextToken() {
            skipWhitespace();
            if(position >= text.length()) return null;
            int start = position;
            while(position < text.length() && !isWhitespace(text.charAt(position))) position++;
            return text.substring(start, position);
        }

        private void skipWhitespace() {
            while(position < text.length() && isWhitespace(text.charAt(position))) position++;
        }

        private static boolean isWhitespace(char ch) {
            return ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n';
        }
    }

    private static final class LineReader {
        private final InputStream input;
        private final byte[] buffer = new byte[MAX_LINE_LENGTH];
        private int pending = -1;
        private boolean skipLineFeed;
        private boolean firstLine = true;

        LineReader(InputStream input) {
            this.input = input;
        }

        String readLine() throws IOException {
            if(skipLineFeed) {
                int next = input.read();
                if(next != '\n') pending = next;
                skipLineFeed = false;
            }
            int length = 0;
            int value;
            while(true) {
                if(pending != -1) {
                    value = pending;
                    pending = -1;
                } else {
                    value = input.read();
                }
                if(value == -1) break;
                if(value == '\n') break;
                if(value == '\r') {
                    skipLineFeed = true;
                    break;
                }
                if(length >= buffer.length) throw new IOException("OBJ line exceeds " + MAX_LINE_LENGTH + " bytes");
                buffer[length++] = (byte) value;
            }
            if(value == -1 && length == 0) return null;
            String line = new String(buffer, 0, length, "UTF-8");
            if(firstLine) {
                firstLine = false;
                if(line.length() > 0 && line.charAt(0) == '\uFEFF') line = line.substring(1);
            }
            return line;
        }
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream output;
        int count;

        CountingOutputStream(OutputStream output) {
            this.output = output;
        }

        public void write(int value) throws IOException {
            output.write(value);
            count++;
        }

        public void write(byte[] data, int offset, int length) throws IOException {
            output.write(data, offset, length);
            count += length;
        }

        public void flush() throws IOException {
            output.flush();
        }

        public void close() throws IOException {
            output.close();
        }
    }
}
